package pwshield;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Reservations cover both queues and extracted batches, until the packet is recycled. */
public final class BacklogBudget {
    public record Reservation(long guid, int bytes) {}
    private final long globalLimit, peerLimit;
    private final Map<Long, Long> peers = new HashMap<>();
    private final IdentityHashMap<Object, Reservation> packets = new IdentityHashMap<>();
    private long bytes, peak;

    public BacklogBudget(long globalLimit, long peerLimit) {
        this.globalLimit = globalLimit;
        this.peerLimit = peerLimit;
    }
    public synchronized Reservation reserve(long guid, int capacity) {
        int cost = Math.addExact(capacity, 128);
        long peer = peers.getOrDefault(guid, 0L);
        if (cost > globalLimit - bytes || cost > peerLimit - peer) return null;
        bytes += cost;
        peak = Math.max(peak, bytes);
        peers.put(guid, peer + cost);
        return new Reservation(guid, cost);
    }
    public synchronized void attach(Object packet, Reservation reservation) {
        if (packets.containsKey(packet)) throw new IllegalStateException("Packet is already reserved");
        packets.put(packet, reservation);
    }
    public synchronized void release(Object packet) {
        Reservation reservation = packets.remove(packet);
        if (reservation != null) cancel(reservation);
    }
    public synchronized void cancel(Reservation reservation) {
        bytes -= reservation.bytes();
        long left = peers.get(reservation.guid()) - reservation.bytes();
        if (left == 0) peers.remove(reservation.guid());
        else peers.put(reservation.guid(), left);
    }
    public synchronized long bytes() { return bytes; }
    public synchronized long peak() { return peak; }
    public synchronized int trackedPeers() { return peers.size(); }
}
