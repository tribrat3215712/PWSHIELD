package pzshield;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** A full idle-buffer pool lets returned buffers be garbage collected. */
public final class BoundedPoolQueue<E> extends ConcurrentLinkedQueue<E> {
    private final AtomicInteger retained = new AtomicInteger();
    private final int limit;
    public BoundedPoolQueue() { this(ShieldConfig.ENABLED ? ShieldConfig.POOL_LIMIT : Integer.MAX_VALUE); }
    public BoundedPoolQueue(int limit) { this.limit = limit; }

    @Override public boolean add(E item) { return offer(item); }
    @Override public boolean offer(E item) {
        Objects.requireNonNull(item);
        int count;
        do {
            count = retained.get();
            if (count >= limit) return false;
        } while (!retained.compareAndSet(count, count + 1));
        try { return super.offer(item); }
        catch (RuntimeException | Error failure) { retained.decrementAndGet(); throw failure; }
    }
    @Override public E poll() {
        E item = super.poll();
        if (item != null) retained.decrementAndGet();
        return item;
    }
    @Override public int size() { return retained.get(); }
    @Override public boolean isEmpty() { return retained.get() == 0; }
}
