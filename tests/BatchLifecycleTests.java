package pzshield;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import zombie.network.IZomboidPacket;
import zombie.network.ZomboidNetData;
import zombie.network.ZomboidNetDataPool;

/** Real buffers and the patched pool; intentionally does not initialize a server. */
public final class BatchLifecycleTests {
    private static int checks;
    private static BacklogBudget budget;
    private static ConcurrentLinkedQueue<ZomboidNetData> idlePool;
    private static final int COST = 2048 + 128;

    private static final class Control implements IZomboidPacket {
        final boolean connect;
        int callbacks;
        Control(boolean connect) { this.connect = connect; }
        public boolean isConnect() { return connect; }
        public boolean isDisconnect() { return !connect; }
        void dispatch(boolean fail) {
            callbacks++;
            if (fail) throw new IllegalStateException("injected control failure");
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        boolean scheduler = ShieldConfig.ENABLED && ShieldConfig.QUEUES;
        if (args.length != 1 || !(args[0].equals("scheduler") || args[0].equals("legacy")))
            throw new IllegalArgumentException("Expected scheduler or legacy mode");
        check(scheduler == args[0].equals("scheduler"), "requested mode matches configuration");
        Field f = ShieldHooks.class.getDeclaredField("BUDGET"); f.setAccessible(true);
        budget = (BacklogBudget) f.get(null);
        Field poolField = ZomboidNetDataPool.class.getDeclaredField("pool"); poolField.setAccessible(true);
        idlePool = (ConcurrentLinkedQueue<ZomboidNetData>) poolField.get(ZomboidNetDataPool.instance);
        check(ShieldConfig.POOL_LIMIT > 0, "reuse test requires at least one retained buffer");
        patchedPoolReleases();
        processedReuseCannotBeReleasedAgain(scheduler);
        if (scheduler) {
            exceptionTailRecovery();
            abortBeforeFirstDispatch();
            controlsRetainedOnAbort();
            deadlineThenClearDoesNotRecycleDeferredTail();
        } else legacyDropReleasesTail();
        cleanBudget("test suite end");
        System.out.println("PASS: " + checks + " batch lifecycle checks (" + args[0]
            + "); actual ZomboidNetData/patched pool, FIFO recovery, reservation release and producer reuse");
    }

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static void cleanBudget(String where) {
        check(budget.bytes() == 0 && budget.trackedPeers() == 0, "no reservations/peer state at " + where);
    }

    private static void emptyIdlePool() {
        while (idlePool.poll() != null) { }
        check(idlePool.size() == 0, "idle pool drained through accounted poll");
    }

    private static ZomboidNetData packet(int marker, long guid) {
        return reserve(new ZomboidNetData(), marker, guid);
    }

    private static ZomboidNetData reserve(ZomboidNetData data, int marker, long guid) {
        BacklogBudget.Reservation reservation = budget.reserve(guid, data.buffer.capacity());
        check(reservation != null, "test packet reservation accepted");
        budget.attach(data, reservation);
        data.connection = guid;
        data.time = marker;
        data.buffer.bb.putInt(marker);
        return data;
    }

    private static void drain(FairQueue<IZomboidPacket> queue, ArrayList<IZomboidPacket> batch) {
        for (IZomboidPacket item; (item = queue.poll()) != null;)
            check(ShieldHooks.collect(batch, item, queue), "batch collect accepts packet");
    }

    private static ArrayList<IZomboidPacket> drain(FairQueue<IZomboidPacket> queue) {
        ArrayList<IZomboidPacket> batch = new ArrayList<>();
        drain(queue, batch);
        return batch;
    }

    private static void finish(ArrayList<IZomboidPacket> batch) {
        for (int i = 0; i < batch.size(); i++) {
            Object item = ShieldHooks.takeBatch(batch, i);
            if (item instanceof ZomboidNetData data) ZomboidNetDataPool.instance.discard(data);
            else if (item instanceof Control control) control.dispatch(false);
            else throw new AssertionError("unexpected batch entry");
        }
        ShieldHooks.clearBatch(batch);
        check(batch.isEmpty(), "completed batch cleared");
    }

    private static void patchedPoolReleases() {
        cleanBudget("pool smoke start"); emptyIdlePool();
        ZomboidNetData data = packet(10, 10);
        check(budget.bytes() == COST, "real buffer accounted");
        ZomboidNetDataPool.instance.discard(data);
        cleanBudget("actual patched pool discard");
        check(ZomboidNetDataPool.instance.get() == data, "actual idle pool reuses discarded buffer");
        check(data.buffer.bb.position() == 0 && data.connection == 0, "actual discard resets buffer");
    }

    private static void exceptionTailRecovery() {
        cleanBudget("exception recovery start");
        FairQueue<IZomboidPacket> queue = new FairQueue<>(0);
        ZomboidNetData first = packet(1, 1), second = packet(2, 1), third = packet(3, 1);
        queue.add(first); queue.add(second); queue.add(third);
        ArrayList<IZomboidPacket> batch = drain(queue);
        try {
            ZomboidNetData current = (ZomboidNetData) ShieldHooks.takeBatch(batch, 0);
            check(current == first && batch.get(0) == null, "current reference cleared before handler");
            try { throw new IllegalStateException("injected dispatch failure"); }
            finally { ZomboidNetDataPool.instance.discard(current); }
        } catch (IllegalStateException expected) { }
        ZomboidNetData incoming = packet(4, 1); queue.add(incoming);
        ShieldHooks.clearBatch(batch);
        check(batch.isEmpty() && queue.size() == 3, "aborted suffix recovered, shared batch cleared");
        check(budget.bytes() == COST * 3L, "tail and new arrival remain accounted");
        ArrayList<IZomboidPacket> retry = drain(queue);
        check(retry.equals(List.of(second, third, incoming)), "exception suffix precedes new arrivals in FIFO");
        finish(retry); cleanBudget("exception recovery completion");
        check(queue.isEmpty(), "exception recovery empties queue");
    }

    private static void abortBeforeFirstDispatch() {
        cleanBudget("maintenance abort start");
        FairQueue<IZomboidPacket> queue = new FairQueue<>(0);
        ZomboidNetData first = packet(11, 11), second = packet(12, 11);
        queue.add(first); queue.add(second);
        ArrayList<IZomboidPacket> batch = drain(queue);
        // Delayed-disconnect maintenance can abort after extraction, before takeBatch/phaseSize.
        try { throw new IllegalStateException("injected pre-dispatch maintenance failure"); }
        catch (IllegalStateException expected) { }
        ShieldHooks.clearBatch(batch);
        check(queue.size() == 2 && budget.bytes() == COST * 2L, "collect establishes queue before first dispatch");
        ArrayList<IZomboidPacket> retry = drain(queue);
        check(retry.equals(List.of(first, second)), "entire untouched batch recovered in FIFO");
        finish(retry); cleanBudget("maintenance abort completion");
    }

    private static void controlsRetainedOnAbort() {
        cleanBudget("control abort start");
        FairQueue<IZomboidPacket> queue = new FairQueue<>(0);
        Control failing = new Control(true), connect = new Control(true), disconnect = new Control(false);
        ZomboidNetData data = packet(21, 21);
        queue.add(failing); queue.add(data); queue.add(connect); queue.add(disconnect);
        ArrayList<IZomboidPacket> batch = drain(queue);
        try { ((Control) ShieldHooks.takeBatch(batch, 0)).dispatch(true); }
        catch (IllegalStateException expected) { }
        ZomboidNetData incoming = packet(22, 21); queue.add(incoming);
        ShieldHooks.clearBatch(batch);
        ArrayList<IZomboidPacket> retry = drain(queue);
        check(retry.equals(List.of(data, connect, disconnect, incoming)), "pending controls retained with FIFO suffix");
        check(failing.callbacks == 1 && connect.callbacks == 0 && disconnect.callbacks == 0, "failed consumed callback is not retried");
        finish(retry); cleanBudget("control abort completion");
        check(failing.callbacks == 1 && connect.callbacks == 1 && disconnect.callbacks == 1, "pending controls each dispatch once");
    }

    private static void processedReuseCannotBeReleasedAgain(boolean scheduler) throws Exception {
        cleanBudget("producer reuse start"); emptyIdlePool();
        FairQueue<IZomboidPacket> oldQueue = new FairQueue<>(0), newQueue = new FairQueue<>(1);
        ZomboidNetData old = packet(31, 31), tail = packet(32, 31);
        oldQueue.add(old); oldQueue.add(tail);
        ArrayList<IZomboidPacket> oldBatch = drain(oldQueue);
        check(ShieldHooks.takeBatch(oldBatch, 0) == old, "consume packet destined for producer reuse");
        ZomboidNetDataPool.instance.discard(old);
        AtomicReference<ZomboidNetData> reused = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                ZomboidNetData data = ZomboidNetDataPool.instance.get();
                if (data != old) throw new AssertionError("real pool did not supply just-recycled identity");
                reserve(data, 333, 33); reused.set(data); newQueue.add(data);
            } catch (Throwable failure) { problem.set(failure); }
        }, "batch-lifecycle-reuse-producer");
        producer.start(); producer.join();
        if (problem.get() != null) throw new AssertionError("producer reuse failure", problem.get());
        ShieldHooks.clearBatch(oldBatch);
        check(reused.get() == old && oldBatch.isEmpty(), "old batch reference cleared before reuse");
        check(old.connection == 33 && old.time == 333 && old.buffer.bb.position() == 4
            && old.buffer.bb.getInt(0) == 333, "cleanup preserves newly owned buffer contents/state");
        check(budget.bytes() == COST * (scheduler ? 2L : 1L), "cleanup does not release reused packet reservation");
        for (ZomboidNetData idle : idlePool) check(idle != old, "reused live packet was not returned to pool again");
        if (scheduler) {
            ArrayList<IZomboidPacket> remaining = drain(oldQueue);
            check(remaining.equals(List.of(tail)), "old unprocessed suffix recovered");
            finish(remaining);
        } else check(oldQueue.isEmpty(), "legacy fallback discarded old suffix");
        check(budget.bytes() == COST, "new producer reservation survives old batch cleanup");
        ArrayList<IZomboidPacket> fresh = drain(newQueue);
        check(fresh.equals(List.of(old)), "producer's reused packet delivered exactly once");
        finish(fresh); cleanBudget("producer reuse completion");
    }

    private static void deadlineThenClearDoesNotRecycleDeferredTail() throws Exception {
        cleanBudget("deadline start");
        FairQueue<IZomboidPacket> queue = new FairQueue<>(2);
        ZomboidNetData first = packet(41, 41), second = packet(42, 41);
        queue.add(first); queue.add(second);
        ArrayList<IZomboidPacket> batch = drain(queue);
        ZomboidNetDataPool.instance.discard((ZomboidNetData) ShieldHooks.takeBatch(batch, 0));
        // Set a deterministic expired deadline rather than relying on host scheduling.
        Field cycle = ShieldHooks.class.getDeclaredField("cycleStart"); cycle.setAccessible(true);
        cycle.setLong(null, System.nanoTime() - ShieldConfig.BUDGET_NS - 1_000_000L);
        check(ShieldHooks.phaseSize(batch, 1, queue) == 1, "scheduler defers tail at deadline");
        ShieldHooks.clearBatch(batch);
        check(queue.size() == 1 && budget.bytes() == COST, "clear does not recycle already deferred tail");
        ArrayList<IZomboidPacket> retry = drain(queue);
        check(retry.equals(List.of(second)), "deadline tail delivered exactly once");
        finish(retry); cleanBudget("deadline completion");
    }

    private static void legacyDropReleasesTail() {
        cleanBudget("legacy drop start");
        FairQueue<IZomboidPacket> queue = new FairQueue<>(2);
        ZomboidNetData first = packet(51, 51), second = packet(52, 51), third = packet(53, 51);
        queue.add(first); queue.add(second); queue.add(third);
        ArrayList<IZomboidPacket> batch = drain(queue);
        ZomboidNetDataPool.instance.discard((ZomboidNetData) ShieldHooks.takeBatch(batch, 0));
        check(budget.bytes() == COST * 2L, "legacy suffix is still reserved before cleanup");
        // Original vehicle time-limit branch breaks here, then invokes patched clear.
        ShieldHooks.clearBatch(batch);
        check(batch.isEmpty() && queue.isEmpty(), "legacy dropped suffix is not requeued");
        cleanBudget("legacy dropped suffix recycled");
        check(second.connection == 0 && third.connection == 0
            && second.buffer.bb.position() == 0 && third.buffer.bb.position() == 0, "legacy cleanup really resets unprocessed buffers");
        ShieldHooks.clearBatch(batch); cleanBudget("legacy repeated empty clear");
    }
}
