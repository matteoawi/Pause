package it.matteo.pausa.core;

import java.io.IOException;
import java.util.*;

/** Validates remote data and removes rules already covered by a parent domain. */
public final class ProtectionLists {
    public static final int MAX_DOMAINS = 280_000;
    public static final int MAX_CHARACTERS = 6_000_000;
    private static final String[] ESSENTIAL = {
        "google.com", "accounts.google.com", "gstatic.com", "googleapis.com", "googleusercontent.com",
        "youtube.com", "googlevideo.com", "ytimg.com", "instagram.com", "cdninstagram.com",
        "www.youtube.com", "youtubei.googleapis.com", "www.instagram.com", "i.instagram.com", "graph.instagram.com", "graph.facebook.com",
        "facebook.com", "fbcdn.net", "whatsapp.com", "whatsapp.net", "github.com", "raw.githubusercontent.com",
        "cloudflare.com", "apple.com", "microsoft.com", "microsoftonline.com", "example.com",
        "co.uk", "com.au", "co.jp", "com.br", "co.in"
    };
    public static void validate(Set<String> domains, int minimum) throws IOException {
        if (domains.size() < minimum || domains.size() > MAX_DOMAINS
                || !(covered(domains, "doubleclick.net") || covered(domains, "pagead2.googlesyndication.com")))
            throw new IOException("Invalid protection list");
        for (String domain : ESSENTIAL) if (covered(domains, domain))
            throw new IOException("Protection list blocks an essential service");
    }
    public static Set<String> combine(Set<String> first, Set<String> second) throws IOException {
        if ((long) first.size() + second.size() > MAX_DOMAINS) throw new IOException("Combined protection exceeds memory budget");
        long characters = 0;
        for (Set<String> source : Arrays.asList(first, second)) for (String domain : source) {
            characters += domain.length();
            if (characters > MAX_CHARACTERS) throw new IOException("Protection names exceed memory budget");
        }
        Set<String> all = new HashSet<>(first); all.addAll(second);
        for (Iterator<String> iterator = all.iterator(); iterator.hasNext();) {
            String domain = iterator.next();
            int dot = domain.indexOf('.');
            if (dot >= 0 && covered(all, domain.substring(dot + 1))) iterator.remove();
        }
        validate(all, 10_000);
        return all;
    }
    private static boolean covered(Set<String> domains, String name) {
        while (!name.isEmpty()) {
            if (domains.contains(name)) return true;
            int dot = name.indexOf('.'); if (dot < 0) break;
            name = name.substring(dot + 1);
        }
        return false;
    }
    private ProtectionLists() { }
}
