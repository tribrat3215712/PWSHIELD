package pzshield;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Many producers, one server-loop consumer, matching GameServer's existing queues. */
public final class FairQueue<E> extends ConcurrentLinkedQueue<E> {
    private final AtomicInteger depth = new AtomicInteger();
    private final ArrayDeque<E> deferred = new ArrayDeque<>();
    private int remaining = -1;
    public final int phase;

    public FairQueue(int phase) { this.phase = phase; }

    @Override public boolean add(E item) { return offer(item); }

    @Override public boolean offer(E item) {
        Objects.requireNonNull(item);
        depth.incrementAndGet();
        try { return super.offer(item); }
        catch (RuntimeException | Error failure) { depth.decrementAndGet(); throw failure; }
    }

    @Override public E poll() {
        if (!ShieldConfig.ENABLED || !ShieldConfig.QUEUES) {
            E item = deferred.isEmpty() ? super.poll() : deferred.removeFirst();
            if (item != null) depth.decrementAndGet();
            return item;
        }
        if (remaining < 0) {
            ShieldHooks.beginPhase(phase);
            remaining = depth.get();
        }
        if (remaining == 0) { remaining = -1; return null; }
        E item = deferred.isEmpty() ? super.poll() : deferred.removeFirst();
        if (item == null) { remaining = -1; return null; }
        --remaining;
        depth.decrementAndGet();
        return item;
    }

    /** Put the unprocessed FIFO suffix ahead of packets arriving during this phase. */
    public void defer(List<E> batch, int from) {
        for (int i = batch.size() - 1; i >= from; --i) deferred.addFirst(batch.get(i));
        depth.addAndGet(batch.size() - from);
        batch.subList(from, batch.size()).clear();
    }

    @Override public int size() { return depth.get(); }
    @Override public boolean isEmpty() { return depth.get() == 0; }
}
