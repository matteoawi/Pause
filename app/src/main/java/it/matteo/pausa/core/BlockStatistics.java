package it.matteo.pausa.core;

import java.time.LocalDate;
import java.util.*;

/** Counts actual blocked queries, retaining only a small local daily history. */
public final class BlockStatistics {
    public static final String UNKNOWN = "?";
    private final Map<String, Long> apps = new HashMap<>(), days = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private long total;
    public BlockStatistics(Map<String, Long> apps, Map<String, String> labels, Map<String, Long> days, long historicalTotal) {
        for (Map.Entry<String, Long> entry : apps.entrySet()) {
            if (entry.getValue() >= 0 && entry.getKey().length() <= 256) { this.apps.put(entry.getKey(), entry.getValue()); total += entry.getValue(); }
        }
        this.labels.putAll(labels);
        for (Map.Entry<String, Long> entry : days.entrySet()) {
            try { LocalDate.parse(entry.getKey()); if (entry.getValue() >= 0) this.days.put(entry.getKey(), entry.getValue()); }
            catch (java.time.format.DateTimeParseException ignored) { }
        }
        if (historicalTotal > total) { this.apps.put(UNKNOWN, this.apps.getOrDefault(UNKNOWN, 0L) + historicalTotal - total); total = historicalTotal; }
    }
    public synchronized void record(String key, String label, LocalDate date) {
        if (key == null || key.isEmpty() || key.length() > 256 || (!apps.containsKey(key) && apps.size() >= 500)) key = UNKNOWN;
        apps.put(key, apps.getOrDefault(key, 0L) + 1);
        if (label != null && !label.isEmpty()) labels.put(key, label.length() > 160 ? label.substring(0, 160) : label);
        days.put(date.toString(), days.getOrDefault(date.toString(), 0L) + 1); total++;
        LocalDate cutoff = date.minusDays(29); days.keySet().removeIf(day -> LocalDate.parse(day).isBefore(cutoff));
    }
    public synchronized Snapshot snapshot(LocalDate date) {
        long[] week = new long[7]; for (int i = 0; i < 7; i++) week[i] = days.getOrDefault(date.minusDays(6 - i).toString(), 0L);
        List<AppCount> list = new ArrayList<>();
        for (Map.Entry<String, Long> entry : apps.entrySet()) if (entry.getValue() > 0) list.add(new AppCount(entry.getKey(), labels.getOrDefault(entry.getKey(), ""), entry.getValue()));
        list.sort((a, b) -> { int byCount = Long.compare(b.count, a.count); return byCount == 0 ? a.key.compareTo(b.key) : byCount; });
        return new Snapshot(total, week[6], week, Collections.unmodifiableList(list));
    }
    public synchronized Map<String, Long> days() { return new HashMap<>(days); }
    public static final class Snapshot {
        public final long total, today; public final long[] week; public final List<AppCount> apps;
        Snapshot(long total, long today, long[] week, List<AppCount> apps) { this.total = total; this.today = today; this.week = week; this.apps = apps; }
    }
    public static final class AppCount {
        public final String key, label; public final long count;
        AppCount(String key, String label, long count) { this.key = key; this.label = label; this.count = count; }
    }
}
