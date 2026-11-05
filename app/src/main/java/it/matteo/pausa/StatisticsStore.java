package it.matteo.pausa;

import android.content.Context;
import it.matteo.pausa.core.BlockStatistics;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

/** Only aggregate counts and app labels are persisted; never a browsing history. */
final class StatisticsStore {
    private static BlockStatistics statistics;
    private static synchronized BlockStatistics get(Context context) {
        if (statistics != null) return statistics;
        Settings prefs = new Settings(context); Map<String, Long> apps = new HashMap<>(), days = new HashMap<>(); Map<String, String> labels = new HashMap<>();
        try {
            JSONObject saved = new JSONObject(prefs.text("statistics", "{}")); JSONObject appData = saved.optJSONObject("apps");
            if (appData != null) {
                Iterator<String> keys = appData.keys();
                while (keys.hasNext() && apps.size() < 501) { String key = keys.next(); JSONObject entry = appData.getJSONObject(key); apps.put(key, entry.optLong("count")); labels.put(key, entry.optString("label")); }
            }
            JSONObject dayData = saved.optJSONObject("days");
            if (dayData != null) { Iterator<String> keys = dayData.keys(); while (keys.hasNext() && days.size() < 31) { String key = keys.next(); days.put(key, dayData.optLong(key)); } }
        } catch (JSONException ignored) { /* historical total is still recoverable */ }
        if (prefs.text("statistics_since", "").isEmpty()) prefs.putText("statistics_since", LocalDate.now().toString());
        statistics = new BlockStatistics(apps, labels, days, prefs.number("blocked")); return statistics;
    }
    static BlockStatistics.Snapshot snapshot(Context context) {
        LocalDate now = LocalDate.now(); BlockStatistics.Snapshot snapshot = get(context).snapshot(now);
        try {
            LocalDate first = LocalDate.parse(new Settings(context).text("statistics_since", now.toString()));
            for (int i = 0; i < 7; i++) if (now.minusDays(6 - i).isBefore(first)) snapshot.week[i] = -1;
        } catch (java.time.format.DateTimeParseException ignored) { }
        return snapshot;
    }
    static void record(Context context, String key, String label) { get(context).record(key, label, LocalDate.now()); }
    static void save(Context context, long analyzed) {
        BlockStatistics model = get(context);
        try {
            BlockStatistics.Snapshot snapshot; Map<String, Long> daily;
            synchronized (model) { snapshot = model.snapshot(LocalDate.now()); daily = model.days(); }
            JSONObject root = new JSONObject(), apps = new JSONObject(), days = new JSONObject();
            for (BlockStatistics.AppCount count : snapshot.apps) {
                JSONObject app = new JSONObject(); app.put("count", count.count); app.put("label", count.label); apps.put(count.key, app);
            }
            for (Map.Entry<String, Long> entry : daily.entrySet()) days.put(entry.getKey(), entry.getValue());
            root.put("apps", apps); root.put("days", days);
            new Settings(context).saveStatistics(Math.max(analyzed, snapshot.total), snapshot.total, root.toString());
        } catch (JSONException ignored) { }
    }
    private StatisticsStore() { }
}
