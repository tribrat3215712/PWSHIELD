package pzshield;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.network.IZomboidPacket;

public final class CoreTests {
    private static int checks;
    private static final class Packet implements IZomboidPacket {
        final int id;
        Packet(int id) { this.id = id; }
        public boolean isConnect() { return false; }
        public boolean isDisconnect() { return false; }
    }
    public static void main(String[] args) throws Exception {
        snapshotAndFifo(); deadlineAndProgress(); concurrentFifo(); memoryBudget(); poolConcurrency(); limiter(); vehicleValidation();
        System.out.println("PASS: " + checks + " checks; FIFO snapshots, deadline deferral, producer stress, buffer quotas, pool cap, limiter and vehicle validation");
    }
    private static void check(boolean condition, String label) {
        ++checks; if (!condition) throw new AssertionError(label);
    }
    private static <E> ArrayList<E> drain(FairQueue<E> queue) {
        ArrayList<E> batch = new ArrayList<>(); E item;
        while ((item = queue.poll()) != null) batch.add(item);
        return batch;
    }
    private static void snapshotAndFifo() {
        FairQueue<Integer> queue = new FairQueue<>(0);
        queue.add(1); queue.add(2); queue.add(3);
        check(queue.poll() == 1, "first snapshot packet"); queue.add(4);
        check(queue.poll() == 2 && queue.poll() == 3 && queue.poll() == null, "new packets do not extend drain");
        check(drain(queue).equals(List.of(4)), "next phase receives new packet");
        queue.add(1); queue.add(2); queue.add(3);
        ArrayList<Integer> batch = drain(queue); queue.add(4); queue.add(5);
        queue.defer(batch, 1);
        check(batch.equals(List.of(1)), "processed batch prefix preserved");
        check(queue.size() == 4 && drain(queue).equals(List.of(2,3,4,5)), "deferred tail precedes arrivals");
        check(queue.size() == 0 && queue.isEmpty(), "accurate depth after deferral");
    }
    private static void deadlineAndProgress() throws Exception {
        for (int phase = 0; phase < 3; ++phase) {
            FairQueue<IZomboidPacket> queue = new FairQueue<>(phase);
            ShieldHooks.beginPhase(0);
            queue.add(new Packet(1)); queue.add(new Packet(2)); queue.add(new Packet(3));
            ArrayList<IZomboidPacket> batch = drain(queue);
            Thread.sleep(5);
            check(ShieldHooks.phaseSize(batch, 0, queue) == 3, "minimum progress phase " + phase);
            queue.add(new Packet(4));
            check(ShieldHooks.phaseSize(batch, 1, queue) == 1 && batch.size() == 1, "time bound phase " + phase);
            List<IZomboidPacket> next = drain(queue);
            check(next.size() == 3 && ((Packet)next.get(0)).id == 2 && ((Packet)next.get(2)).id == 4, "phase FIFO " + phase);
        }
    }
    private static void concurrentFifo() throws Exception {
        FairQueue<Integer> queue = new FairQueue<>(0);
        int count = 100_000; CountDownLatch start = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try { start.await(); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
            for (int i = 0; i < count; ++i) queue.add(i);
        });
        producer.start(); start.countDown(); int expected = 0;
        while (expected < count) {
            ArrayList<Integer> batch = drain(queue);
            for (int i = 0; i < batch.size(); ++i) {
                check(batch.get(i) == expected++, "concurrent FIFO");
                if (batch.size() > 3 && i == batch.size() / 2) { queue.defer(batch, i + 1); break; }
            }
            if (batch.isEmpty()) Thread.yield();
        }
        producer.join(); check(queue.size() == 0, "stress leaves no pending entries");
    }
    private static void memoryBudget() {
        BacklogBudget budget = new BacklogBudget(4096, 2304);
        Object packet = new Object(); var first = budget.reserve(1, 2048);
        check(first != null, "reservation below quota"); budget.attach(packet, first);
        check(budget.reserve(1, 2048) == null, "peer cap");
        check(budget.reserve(2, 2048) == null, "global cap");
        check(budget.bytes() == 2176, "reservation retained while packet is in extracted batch");
        budget.release(packet); budget.release(packet);
        check(budget.bytes() == 0 && budget.trackedPeers() == 0, "release idempotent and removes peer");
        var second = budget.reserve(2, 1024); budget.cancel(second);
        check(budget.bytes() == 0 && budget.peak() == 2176, "allocation failure reservation cancelled");
    }
    private static void poolConcurrency() throws Exception {
        BoundedPoolQueue<Integer> queue = new BoundedPoolQueue<>(100);
        Thread[] producers = new Thread[8]; AtomicInteger accepted = new AtomicInteger();
        for (int t = 0; t < producers.length; ++t) {
            int base = t * 10_000;
            producers[t] = new Thread(() -> { for (int i = 0; i < 10_000; ++i) if (queue.add(base + i)) accepted.incrementAndGet(); });
            producers[t].start();
        }
        for (Thread producer : producers) producer.join();
        check(queue.size() == 100 && accepted.get() == 100, "idle pool cap under concurrent return");
        int removed = 0; while (queue.poll() != null) ++removed;
        check(removed == 100 && queue.size() == 0 && queue.add(1), "pool capacity recovers");
        check(!new BoundedPoolQueue<Integer>(0).add(1), "zero pool limit");
    }
    private static void limiter() {
        GuidLimiter limiter = new GuidLimiter(2, 2, 10, 10, 2, 10_000_000_000L);
        long now = 1_000_000_000L;
        check(limiter.allow(1, 5, now) && limiter.allow(1, 5, now) && !limiter.allow(1, 1, now), "packet and byte burst");
        check(!limiter.allow(1, 5, now + 250_000_000L), "partial refill cannot accept packet");
        check(limiter.allow(1, 5, now + 500_000_000L), "combined bucket refill");
        check(!limiter.allow(1, 5, now + 500_000_000L), "same GUID retains state across calls");
        check(limiter.allow(2, 1, now) && limiter.allow(3, 1, now) && limiter.size() == 2 && limiter.overflows == 1, "state bound fail open");
        check(limiter.allow(1, 5, now + 20_000_000_000L) && limiter.size() == 1, "idle state TTL");
    }
    private static ByteBuffer vehicle(int wheels) {
        ByteBuffer buffer = ByteBuffer.allocate(69 + wheels * 16);
        buffer.putShort((short)1).putLong(123);
        for (int i = 0; i < 13; ++i) buffer.putFloat(i == 7 ? 1 : 0);
        buffer.put((byte)0).putShort((short)wheels);
        for (int i = 0; i < wheels * 4; ++i) buffer.putFloat(0);
        buffer.putFloat(20); buffer.flip(); return buffer;
    }
    private static void vehicleValidation() {
        ByteBuffer packet = vehicle(4);
        check(PacketGuards.vehicle(packet, 10) && packet.position() == 0, "vehicle validation non-consuming");
        for (int length = 0; length < packet.limit(); ++length) {
            ByteBuffer truncated = packet.duplicate(); truncated.limit(length);
            check(!PacketGuards.vehicle(truncated, 10), "truncated vehicle payload");
        }
        for (int i = 0; i < 13; ++i) {
            ByteBuffer poisoned = vehicle(4); poisoned.putFloat(10 + 4 * i, Float.NaN);
            check(!PacketGuards.vehicle(poisoned, 10), "NaN vehicle field");
        }
        ByteBuffer poisoned = vehicle(4); poisoned.putFloat(65, Float.POSITIVE_INFINITY);
        check(!PacketGuards.vehicle(poisoned, 10), "infinite wheel value");
        poisoned = vehicle(4); poisoned.putFloat(129, Float.NEGATIVE_INFINITY);
        check(!PacketGuards.vehicle(poisoned, 10), "infinite vehicle speed");
        poisoned = vehicle(4); poisoned.putShort(63, (short)-1);
        check(!PacketGuards.vehicle(poisoned, 10), "negative wheel count");
        poisoned = vehicle(4); poisoned.putShort(63, (short)32767);
        check(!PacketGuards.vehicle(poisoned, 10), "oversized wheel count");
        poisoned = vehicle(4); poisoned.put(62, (byte)255);
        check(!PacketGuards.vehicle(poisoned, 10), "invalid transmission enum");
        check(!PacketGuards.vehicle(vehicle(5), 10) && !PacketGuards.vehicle(vehicle(6), 10), "pinned JAR supports at most four wheels");
        for (float magnitude : new float[]{0, Float.MIN_VALUE, 1e-23f, Float.MAX_VALUE}) {
            poisoned = vehicle(4);
            for (int offset : new int[]{26,30,34,38}) poisoned.putFloat(offset, magnitude);
            check(!PacketGuards.vehicle(poisoned, 10), "non-normalizable quaternion");
        }
        check(!PacketGuards.datagram(ByteBuffer.allocate(0)), "empty datagram");
        check(!PacketGuards.datagram(ByteBuffer.wrap(new byte[]{(byte)134, 0})), "truncated Zed header");
        check(PacketGuards.datagram(ByteBuffer.wrap(new byte[]{(byte)134, 0, 1})), "complete Zed header");
        check(!PacketGuards.datagram(ByteBuffer.wrap(new byte[]{25})), "truncated version packet");
        check(!PacketGuards.datagram(ByteBuffer.wrap(new byte[]{25, -1, -1})), "negative version length");
        check(!PacketGuards.datagram(ByteBuffer.wrap(new byte[]{25, 0, 2, 1})), "truncated version body");
        check(PacketGuards.datagram(ByteBuffer.wrap(new byte[]{25, 0, 0})), "empty version string");
        check(PacketGuards.datagram(ByteBuffer.wrap(new byte[]{25, 0, 2, 1, 2})), "complete version string");
    }
}
