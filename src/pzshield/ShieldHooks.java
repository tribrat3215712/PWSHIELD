package pzshield;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.IZomboidPacket;
import zombie.network.PacketTypes;
import zombie.network.ZomboidNetData;
import zombie.network.ZomboidNetDataPool;
import zombie.network.anticheats.AntiCheat;
import zombie.network.IConnection;
import zombie.network.packets.vehicle.VehiclePhysicsPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.TransmissionNumber;

public final class ShieldHooks {
    private static final BacklogBudget BUDGET = new BacklogBudget(ShieldConfig.MAX_BYTES, ShieldConfig.MAX_PEER_BYTES);
    private static final GuidLimiter LIMITER = new GuidLimiter(ShieldConfig.PPS, ShieldConfig.PACKET_BURST,
        ShieldConfig.BPS, ShieldConfig.BYTE_BURST, ShieldConfig.RATE_STATES, ShieldConfig.RATE_TTL_NS);
    private static final LongAdder ACCEPTED = new LongAdder(), BYTES = new LongAdder(), INVALID = new LongAdder(),
        MEMORY = new LongAdder(), RATE = new LongAdder(), PREAUTH = new LongAdder(), OWNER = new LongAdder();
    private static final LongAdder[] DEFERRED = {new LongAdder(), new LongAdder(), new LongAdder()};
    private static volatile long cycleStart, lastCycleNs;
    private static boolean csvFailed;
    // Accessed exclusively by the single GameServer consumer, never by network producers.
    private static FairQueue<IZomboidPacket> batchQueue;

    static {
        System.err.println("[PZShield 1.1.1] compiled hooks loaded; enabled=" + ShieldConfig.ENABLED);
        if (ShieldConfig.ENABLED && ShieldConfig.METRICS) {
            var executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "PZShield-metrics"); thread.setDaemon(true); return thread;
            });
            executor.scheduleWithFixedDelay(ShieldHooks::report, ShieldConfig.INTERVAL, ShieldConfig.INTERVAL, TimeUnit.SECONDS);
        }
    }

    private ShieldHooks() {}
    public static void beginPhase(int phase) {
        if (phase == 0) cycleStart = System.nanoTime();
    }

    @SuppressWarnings("unchecked")
    public static boolean collect(ArrayList<IZomboidPacket> batch, Object item, ConcurrentLinkedQueue<IZomboidPacket> queue) {
        batchQueue = (FairQueue<IZomboidPacket>) queue;
        return batch.add((IZomboidPacket) item);
    }

    public static Object takeBatch(ArrayList<IZomboidPacket> batch, int index) {
        // Clear the reference before callbacks can recycle this packet and the receive thread reuses it.
        return batch.set(index, null);
    }

    public static void clearBatch(ArrayList<IZomboidPacket> batch) {
        try {
            int first = 0;
            while (first < batch.size() && batch.get(first) == null) ++first;
            if (first < batch.size() && ShieldConfig.ENABLED && ShieldConfig.QUEUES && batchQueue != null) {
                // Interrupted phase: retain the unprocessed contiguous FIFO suffix, including controls.
                batchQueue.defer(batch, first);
            } else {
                // Legacy drop or disabled policy still releases memory reservations and idle buffers.
                for (int i = first; i < batch.size(); ++i)
                    if (batch.get(i) instanceof ZomboidNetData data) ZomboidNetDataPool.instance.discard(data);
            }
        } finally { batch.clear(); batchQueue = null; }
    }

    @SuppressWarnings("unchecked")
    public static int phaseSize(ArrayList<IZomboidPacket> batch, int index, ConcurrentLinkedQueue<IZomboidPacket> queue) {
        int size = batch.size();
        if (!ShieldConfig.ENABLED || !ShieldConfig.QUEUES) return size;
        FairQueue<IZomboidPacket> fair = (FairQueue<IZomboidPacket>) queue;
        if (index >= size) {
            if (fair.phase == 2) lastCycleNs = System.nanoTime() - cycleStart;
            return size;
        }
        long slice = switch (fair.phase) {
            case 0 -> ShieldConfig.BUDGET_NS / 2;
            case 1 -> ShieldConfig.BUDGET_NS * 4 / 5;
            default -> ShieldConfig.BUDGET_NS;
        };
        // At least one packet per nonempty phase prevents permanent vehicle starvation.
        if (index > 0 && System.nanoTime() - cycleStart >= slice) {
            DEFERRED[fair.phase].add(size - index);
            fair.defer(batch, index);
            if (fair.phase == 2) lastCycleNs = System.nanoTime() - cycleStart;
            return index;
        }
        return size;
    }

    public static boolean allowDatagram(ByteBufferReader reader, boolean server) {
        if (!server || !ShieldConfig.ENABLED || !ShieldConfig.FIREWALL) return true;
        if (PacketGuards.datagram(reader.bb)) return true;
        INVALID.increment(); return false;
    }

    public static boolean allowIncoming(short id, ByteBufferReader reader, UdpConnection connection) {
        if (!ShieldConfig.ENABLED) return true;
        PacketTypes.PacketType type = PacketTypes.packetTypes.get(id);
        if (ShieldConfig.FIREWALL) {
            if (type == null) { reject(connection, "pzshield-unknown-packet", INVALID); return false; }
            if ((type.handlingType & 1) == 0) { INVALID.increment(); return false; }
            if (ShieldConfig.VEHICLE && (type == PacketTypes.PacketType.VehiclePhysicsReliable ||
                    type == PacketTypes.PacketType.VehiclePhysicsUnreliable) &&
                    !PacketGuards.vehicle(reader.bb, TransmissionNumber.values().length)) {
                reject(connection, "pzshield-invalid-vehicle-physics", INVALID); return false;
            }
        }
        if (ShieldConfig.RATE_ENABLED && !LIMITER.allow(connection.getConnectedGUID(), reader.bb.remaining(), System.nanoTime())) {
            RATE.increment();
            if (ShieldConfig.RATE_ENFORCE) { connection.forceDisconnect("pzshield-rate-limit"); return false; }
        }
        return true;
    }

    // Keep the vanilla pre-auth check on the server thread, after preceding Login packets.
    // Running it on the receive thread can reject legitimate queued handshake packets.
    public static void preAuthRejected() { PREAUTH.increment(); }

    public static boolean vehicleOwner(VehiclePhysicsPacket packet, UdpConnection connection) {
        if (!ShieldConfig.ENABLED || !ShieldConfig.FIREWALL) return true;
        BaseVehicle vehicle = packet.getVehicle();
        if (vehicle != null && validVehicleState(packet, vehicle) && vehicle.hasAuthorization(connection)) return true;
        OWNER.increment(); return false;
    }

    private static boolean validVehicleState(VehiclePhysicsPacket packet, BaseVehicle vehicle) {
        return packet instanceof VehicleStateView view && view.pzshieldValidState(vehicle.wheelInfo.length);
    }

    public static boolean vehicleState(int wheels, int capacity, float qx, float qy, float qz, float qw) {
        return wheels >= 0 && wheels <= capacity && PacketGuards.quaternion(qx, qy, qz, qw);
    }

    public static void setVehicleSpeed(BaseVehicle vehicle, float speed, IConnection connection, VehiclePhysicsPacket packet) {
        if (!ShieldConfig.ENABLED || !ShieldConfig.FIREWALL ||
                connection instanceof UdpConnection udp && validVehicleState(packet, vehicle) &&
                Float.isFinite(speed) && vehicle.hasAuthorization(udp)) vehicle.setSpeedKmHour(speed);
        else OWNER.increment();
    }

    private static void reject(UdpConnection connection, String reason, LongAdder counter) {
        counter.increment(); connection.forceDisconnect(reason);
    }

    /** Called before a network-controlled allocation, with the actual remaining payload size. */
    public static ZomboidNetData allocate(ByteBufferReader reader, UdpConnection connection) {
        int payload = reader.bb.remaining();
        int capacity = payload <= 2048 ? 2048 : payload;
        if (!ShieldConfig.ENABLED) return capacity == 2048 ? ZomboidNetDataPool.instance.get() : ZomboidNetDataPool.instance.getLong(capacity);
        BacklogBudget.Reservation reservation = BUDGET.reserve(connection.getConnectedGUID(), capacity);
        if (reservation == null) { reject(connection, "pzshield-backlog-limit", MEMORY); return null; }
        try {
            ZomboidNetData data = capacity == 2048 ? ZomboidNetDataPool.instance.get() : ZomboidNetDataPool.instance.getLong(capacity);
            BUDGET.attach(data, reservation);
            ACCEPTED.increment(); BYTES.add(payload);
            return data;
        } catch (RuntimeException | Error failure) { BUDGET.cancel(reservation); throw failure; }
    }

    public static void release(ZomboidNetData data) { BUDGET.release(data); }

    public static void addIncoming(short id, ByteBufferReader reader, UdpConnection connection,
            ConcurrentLinkedQueue<IZomboidPacket> high, ConcurrentLinkedQueue<IZomboidPacket> player,
            ConcurrentLinkedQueue<IZomboidPacket> vehicle) {
        if (!allowIncoming(id, reader, connection)) return;
        ZomboidNetData data = allocate(reader, connection);
        if (data == null) return;
        try {
            data.read(id, reader, connection);
            if (data.type == null) {
                try { AntiCheat.PacketType.act(connection, id + ": unknown Zed packet"); }
                catch (RuntimeException exception) { INVALID.increment(); }
                ZomboidNetDataPool.instance.discard(data);
                return;
            }
            data.time = System.currentTimeMillis();
            if (data.type == PacketTypes.PacketType.PlayerUpdateUnreliable || data.type == PacketTypes.PacketType.PlayerUpdateReliable)
                player.add(data);
            else if (data.type == PacketTypes.PacketType.VehiclePhysicsReliable || data.type == PacketTypes.PacketType.VehiclePhysicsUnreliable)
                vehicle.add(data);
            else high.add(data);
        } catch (RuntimeException | Error failure) {
            ZomboidNetDataPool.instance.discard(data);
            throw failure;
        }
    }

    private static void report() {
        try {
            String row = Instant.now() + "," + ACCEPTED.sum() + "," + BYTES.sum() + "," + INVALID.sum() + "," + PREAUTH.sum() +
                "," + MEMORY.sum() + "," + RATE.sum() + "," + OWNER.sum() + "," + LIMITER.overflows + "," + BUDGET.bytes() + "," + BUDGET.peak() +
                "," + DEFERRED[0].sum() + "," + DEFERRED[1].sum() + "," + DEFERRED[2].sum() + "," + lastCycleNs;
            if (ShieldConfig.CONSOLE) System.err.println("[PZShield] accepted=" + ACCEPTED.sum() + " invalid=" + INVALID.sum() +
                " backlogBytes=" + BUDGET.bytes() + " memoryDisconnects=" + MEMORY.sum() + " rateWouldLimit=" + RATE.sum());
            if (!csvFailed) {
                var path = ShieldConfig.CSV;
                if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
                if (Files.exists(path) && Files.size(path) >= 10_485_760)
                    Files.move(path, path.resolveSibling(path.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                if (!Files.exists(path) || Files.size(path) == 0) Files.writeString(path,
                    "utc,accepted_total,payload_bytes_total,invalid_total,preauth_total,memory_disconnects_total,rate_would_limit_total," +
                    "vehicle_owner_rejected_total,rate_state_overflow_total,backlog_bytes,peak_backlog_bytes,deferred_high_total,deferred_player_total,deferred_vehicle_total,network_cycle_ns\n");
                Files.writeString(path, row + "\n", StandardOpenOption.APPEND);
            }
        } catch (IOException exception) {
            csvFailed = true;
            System.err.println("[PZShield] CSV unavailable: " + exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            // Keep the daemon alive even if a filesystem/security policy changes while the server runs.
            System.err.println("[PZShield] metrics error: " + exception.getClass().getSimpleName());
        }
    }
}
