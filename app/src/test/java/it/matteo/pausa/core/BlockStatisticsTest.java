package it.matteo.pausa.core;

import org.junit.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

public class BlockStatisticsTest {
    private final LocalDate today = LocalDate.of(2026, 10, 28);
    private BlockStatistics fresh(long history) { return new BlockStatistics(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), history); }
    @Test public void historicalBlocksAreNotInventedAsTodayOrAttributedToApps() {
        BlockStatistics.Snapshot snapshot = fresh(133).snapshot(today);
        assertEquals(133, snapshot.total); assertEquals(0, snapshot.today);
        assertEquals(BlockStatistics.UNKNOWN, snapshot.apps.get(0).key); assertArrayEquals(new long[7], snapshot.week);
    }
    @Test public void appCountsAndDailyBucketsTrackActualEvents() {
        BlockStatistics stats = fresh(0);
        stats.record("example.game", "Game", today.minusDays(1)); stats.record("example.game", "Game", today);
        stats.record("example.other", "Other", today); stats.record(null, "", today);
        BlockStatistics.Snapshot snapshot = stats.snapshot(today);
        assertEquals(4, snapshot.total); assertEquals(3, snapshot.today); assertEquals(1, snapshot.week[5]);
        assertEquals("example.game", snapshot.apps.get(0).key); assertEquals(2, snapshot.apps.get(0).count);
    }
    @Test public void restartDoesNotDoubleCountHistoricalTotals() {
        Map<String, Long> apps = new HashMap<>(); apps.put("game.app", 10L); apps.put(BlockStatistics.UNKNOWN, 3L);
        BlockStatistics stats = new BlockStatistics(apps, Collections.emptyMap(), Collections.emptyMap(), 13);
        assertEquals(13, stats.snapshot(today).total);
        stats.record("game.app", "Game", today); assertEquals(14, stats.snapshot(today).total);
    }
    @Test public void migrationRepairsOnlyMissingCounts() {
        Map<String, Long> apps = new HashMap<>(); apps.put("game.app", 5L);
        BlockStatistics stats = new BlockStatistics(apps, Collections.emptyMap(), Collections.emptyMap(), 8);
        assertEquals(8, stats.snapshot(today).total);
        long unknown = stats.snapshot(today).apps.stream().filter(a -> a.key.equals(BlockStatistics.UNKNOWN)).findFirst().get().count;
        assertEquals(3, unknown);
    }
    @Test public void oldDaysExpireWithoutLosingLifetimeTotals() {
        BlockStatistics stats = fresh(0); stats.record("game.app", "Game", today.minusDays(60)); stats.record("game.app", "Game", today);
        assertEquals(1, stats.days().size()); assertEquals(2, stats.snapshot(today).total); assertEquals(1, stats.snapshot(today).today);
    }
    @Test public void malformedDatesAndNegativeSavedCountsAreIgnored() {
        Map<String, Long> days = new HashMap<>(); days.put("broken", 13L); days.put(today.toString(), -1L);
        Map<String, Long> apps = new HashMap<>(); apps.put("game.app", -3L);
        BlockStatistics stats = new BlockStatistics(apps, Collections.emptyMap(), days, 4);
        assertEquals(4, stats.snapshot(today).total); assertEquals(0, stats.snapshot(today).today);
    }
    @Test public void snapshotsCannotMutateTheRunningTotals() {
        BlockStatistics stats = fresh(0); stats.record("game.app", "Game", today);
        BlockStatistics.Snapshot snapshot = stats.snapshot(today); snapshot.week[6] = 100;
        assertEquals(1, stats.snapshot(today).today);
        try { snapshot.apps.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
}
