package it.matteo.pausa.core;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Coalesced list loading, independent of the bounded DNS request pool. */
public final class RuleReloadQueue<T> implements Closeable {
    public interface Loader<T> { T load() throws IOException; }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "pause-rules"));
    private final Loader<T> loader;
    private final Consumer<T> apply;
    private final Consumer<Throwable> failure;
    private final LongSupplier clock;
    private boolean closed, pending, loading;
    private long nextAttempt;
    public RuleReloadQueue(Loader<T> loader, Consumer<T> apply, Consumer<Throwable> failure) {
        this(loader, apply, failure, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }
    RuleReloadQueue(Loader<T> loader, Consumer<T> apply, Consumer<Throwable> failure, LongSupplier clock) {
        this.loader = loader; this.apply = apply; this.failure = failure; this.clock = clock;
    }
    public synchronized void request() {
        if (closed) return;
        pending = true; nextAttempt = 0; kick();
    }
    public synchronized void tick() { kick(); }
    private void kick() {
        if (closed || !pending || loading || clock.getAsLong() < nextAttempt) return;
        pending = false; loading = true;
        try { worker.execute(this::load); }
        catch (RejectedExecutionException stopped) { loading = false; pending = !closed; }
    }
    private void load() {
        try {
            T value = loader.load();
            synchronized (this) { if (closed) return; }
            // The consumer validates its owning session; never acquire session locks while holding this lock.
            apply.accept(value);
        } catch (IOException | RuntimeException | OutOfMemoryError problem) {
            synchronized (this) {
                if (closed) return;
                pending = true; nextAttempt = clock.getAsLong() + 60_000;
            }
            failure.accept(problem);
        } finally {
            synchronized (this) { loading = false; kick(); }
        }
    }
    @Override public synchronized void close() { closed = true; pending = false; worker.shutdownNow(); }
}
