package it.matteo.pausa;

import android.content.*;
import java.util.*;
import it.matteo.pausa.core.DomainRules;

public final class Settings {
    private final android.content.SharedPreferences prefs;
    public Settings(Context context) { prefs = context.getSharedPreferences("pausa", Context.MODE_PRIVATE); }
    public Set<String> set(String key) { return new HashSet<>(prefs.getStringSet(key, Collections.emptySet())); }
    public String text(String key, String fallback) { return prefs.getString(key, fallback); }
    public long number(String key) { return prefs.getLong(key, 0); }
    public void putText(String key, String value) { prefs.edit().putString(key, value).apply(); }
    public void putNumber(String key, long value) { prefs.edit().putLong(key, value).apply(); }
    public void saveStatistics(long total, long blocked, String statistics) {
        prefs.edit().putLong("queries", total).putLong("blocked", blocked).putString("statistics", statistics).apply();
    }
    public void set(String key, Set<String> values) { prefs.edit().putStringSet(key, values).apply(); }
    public void rule(String key, String domain, boolean add) {
        Set<String> rules = set(key);
        String normalized = DomainRules.normalize(domain);
        if (add) rules.add(normalized); else rules.remove(normalized);
        set(key, rules);
    }
}
