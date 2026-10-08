package pwshield;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GuidLimiter {
    private static final class State {
        double packets, bytes;
        long at;
        State(double packets, double bytes, long at) { this.packets = packets; this.bytes = bytes; this.at = at; }
    }
    private final double pps, packetBurst, bps, byteBurst;
    private final int maxStates;
    private final long ttl;
    private final LinkedHashMap<Long, State> states = new LinkedHashMap<>(16, .75f, true);
    public volatile long overflows;

    public GuidLimiter(double pps, double packetBurst, double bps, double byteBurst, int maxStates, long ttl) {
        this.pps = pps; this.packetBurst = packetBurst; this.bps = bps; this.byteBurst = byteBurst;
        this.maxStates = maxStates; this.ttl = ttl;
    }
    public synchronized boolean allow(long guid, int bytes, long now) {
        if (pps == 0 && bps == 0) return true;
        // Access order makes expiry proportional to removed entries, not total connected peers.
        var iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, State> entry = iterator.next();
            if (now - entry.getValue().at < ttl) break;
            iterator.remove();
        }
        State state = states.get(guid);
        if (state == null) {
            if (states.size() >= maxStates) { ++overflows; return true; }
            state = new State(packetBurst, byteBurst, now);
            states.put(guid, state);
        }
        double seconds = Math.max(0, now - state.at) / 1_000_000_000.0;
        state.packets = Math.min(packetBurst, state.packets + seconds * pps);
        state.bytes = Math.min(byteBurst, state.bytes + seconds * bps);
        state.at = now;
        if ((pps > 0 && state.packets < 1) || (bps > 0 && state.bytes < bytes)) return false;
        if (pps > 0) --state.packets;
        if (bps > 0) state.bytes -= bytes;
        return true;
    }
    public synchronized int size() { return states.size(); }
}
