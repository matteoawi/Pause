package it.matteo.pausa.core;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class RuleReloadQueueTest {
    private static void await(CountDownLatch latch) throws Exception { assertTrue("Timed out", latch.await(5, TimeUnit.SECONDS)); }
    @Test public void reloadRunsEvenWhenDnsPoolIsSaturated() throws Exception {
        ThreadPoolExecutor dns = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1), applied = new CountDownLatch(1);
        try (RuleReloadQueue<Integer> reload = new RuleReloadQueue<>(() -> 7, value -> { if (value == 7) applied.countDown(); }, ignored -> { })) {
            dns.execute(() -> { busy.countDown(); try { release.await(); } catch (InterruptedException ignored) { } }); await(busy);
            dns.execute(() -> { });
            try { dns.execute(() -> { }); fail("DNS pool should be full"); } catch (RejectedExecutionException expected) { }
            reload.request(); await(applied);
        } finally { release.countDown(); dns.shutdownNow(); }
    }
    @Test public void burstRequestsCoalesceAndApplyLatestRules() throws Exception {
        AtomicInteger loads = new AtomicInteger(), version = new AtomicInteger(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), latest = new CountDownLatch(1);
        List<Integer> applied = new CopyOnWriteArrayList<>();
        try (RuleReloadQueue<Integer> reload = new RuleReloadQueue<>(() -> {
            int n = loads.incrementAndGet(), value = version.get();
            if (n == 1) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new IOException(e); } }
            return value;
        }, value -> { applied.add(value); if (value == 2) latest.countDown(); }, ignored -> { })) {
            reload.request(); await(entered); version.set(2);
            for (int i = 0; i < 1000; i++) reload.request();
            release.countDown(); await(latest);
            assertEquals(Arrays.asList(1, 2), applied); assertEquals(2, loads.get());
        } finally { release.countDown(); }
    }
    @Test public void failureStaysPendingAndRetriesAfterOneMinute() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger loads = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1), applied = new CountDownLatch(1);
        try (RuleReloadQueue<Integer> reload = new RuleReloadQueue<>(() -> {
            if (loads.incrementAndGet() == 1) throw new IOException("temporarily unavailable"); return 9;
        }, value -> applied.countDown(), ignored -> failed.countDown(), clock::get)) {
            reload.request(); await(failed); clock.set(30_000); reload.tick(); assertEquals(1, loads.get());
            clock.set(60_000); reload.tick(); await(applied); assertEquals(2, loads.get());
        }
    }
    @Test public void closedSessionDoesNotApplyDelayedRules() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), applied = new CountDownLatch(1);
        RuleReloadQueue<Integer> reload = new RuleReloadQueue<>(() -> {
            entered.countDown(); boolean done = false;
            while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
            return 1;
        }, value -> applied.countDown(), ignored -> { });
        try { reload.request(); await(entered); reload.close(); release.countDown(); assertFalse(applied.await(150, TimeUnit.MILLISECONDS)); }
        finally { release.countDown(); reload.close(); }
    }
}
