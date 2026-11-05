package it.matteo.pausa.core;

import java.io.*;
import java.net.IDN;
import java.util.*;

/** Immutable, suffix-aware domain policy. Explicit allow rules always win. */
public final class DomainRules {
    private final Set<String> base, blocked, allowed;
    public DomainRules(Set<String> base, Set<String> blocked, Set<String> allowed) {
        this.base = Collections.unmodifiableSet(new HashSet<>(base));
        this.blocked = Collections.unmodifiableSet(new HashSet<>(blocked));
        this.allowed = Collections.unmodifiableSet(new HashSet<>(allowed));
    }
    public boolean blocks(String domain) {
        String normalized;
        try { normalized = normalize(domain); } catch (IllegalArgumentException ignored) { return false; }
        return !matches(allowed, normalized) && (matches(blocked, normalized) || matches(base, normalized));
    }
    public int size() { return base.size(); }
    private static boolean matches(Set<String> entries, String name) {
        while (!name.isEmpty()) {
            if (entries.contains(name)) return true;
            int dot = name.indexOf('.');
            if (dot < 0) break;
            name = name.substring(dot + 1);
        }
        return false;
    }
    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("Dominio mancante");
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        // Lists and DNS wire names are almost entirely ASCII. Avoid ICU/IDN and regular-expression
        // allocations on every entry/query; international names still use the platform IDN implementation.
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) > 127) {
            name = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES); break;
        }
        if (name.length() > 253) throw new IllegalArgumentException("Dominio non valido");
        int labelLength = 0; boolean dotted = false, numeric = true; char previous = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                if (labelLength == 0 || previous == '-') throw new IllegalArgumentException("Dominio non valido");
                dotted = true; labelLength = 0;
            } else {
                if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-')
                    throw new IllegalArgumentException("Dominio non valido");
                if ((labelLength == 0 && c == '-') || ++labelLength > 63) throw new IllegalArgumentException("Dominio non valido");
                if (c < '0' || c > '9') numeric = false;
            }
            previous = c;
        }
        if (!dotted || numeric || labelLength == 0 || previous == '-') throw new IllegalArgumentException("Dominio non valido");
        return name;
    }
    public static Set<String> read(Reader input) throws IOException {
        return read(input, 500_000);
    }
    public static Set<String> read(Reader input, int maximumDomains) throws IOException {
        return read(input, maximumDomains, Integer.MAX_VALUE);
    }
    public static Set<String> read(Reader input, int maximumDomains, int maximumCharacters) throws IOException {
        Set<String> out = new HashSet<>();
        BufferedReader reader = new BufferedReader(input);
        String line;
        int lines = 0;
        long characters = 0;
        while ((line = reader.readLine()) != null) {
            if (++lines > 500_000 || line.length() > 16_384) throw new IOException("Lista troppo grande");
            int comment = line.indexOf('#'); if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            if (line.isEmpty()) continue;
            StringTokenizer words = new StringTokenizer(line, " \t\r\n\f" + (char) 11);
            String first = words.nextToken();
            boolean hosts = first.equals("0.0.0.0") || first.equals("127.0.0.1") || first.equals("::");
            if (hosts ? !words.hasMoreTokens() : words.hasMoreTokens()) continue;
            do {
                String word = hosts ? words.nextToken() : first;
                try { String domain = normalize(word); if (out.add(domain)) characters += domain.length(); }
                catch (IllegalArgumentException ignored) { /* hosts metadata */ }
                if (out.size() > maximumDomains) throw new IOException("Too many domains");
                if (characters > maximumCharacters) throw new IOException("Domain names exceed memory budget");
            } while (hosts && words.hasMoreTokens());
        }
        return out;
    }
}
