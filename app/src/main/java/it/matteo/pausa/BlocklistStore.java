package it.matteo.pausa;

import android.content.Context;
import android.util.AtomicFile;
import it.matteo.pausa.core.DomainRules;
import it.matteo.pausa.core.ProtectionLists;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class BlocklistStore {
    private static final Object DISK_LOCK = new Object();
    public static final String URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    public static final String EXPANDED_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/multi-onlydomains.txt";
    private final Context context;
    private final AtomicFile file;
    interface Source { byte[] read(String address) throws IOException; }
    private final Source source;
    public BlocklistStore(Context context) {
        this(context, null);
    }
    BlocklistStore(Context context, Source source) {
        this.context = context.getApplicationContext();
        this.source = source;
        // A v0.3 StevenBlack-only cache must never replace the new bundled coverage.
        file = new AtomicFile(new File(context.getFilesDir(), "hosts-v2"));
    }
    public DomainRules load() throws IOException {
        Set<String> domains;
        synchronized (DISK_LOCK) {
            try (InputStream in = file.openRead()) { domains = parse(in); ProtectionLists.validate(domains, 50_000); }
            catch (IOException badDownload) {
                Set<String> base, expanded;
                try (InputStream in = context.getAssets().open("hosts.txt")) { base = parse(in); }
                try (InputStream in = context.getAssets().open("expanded-domains.txt")) { expanded = parse(in); }
                ProtectionLists.validate(base, 10_000); ProtectionLists.validate(expanded, 50_000);
                domains = ProtectionLists.combine(base, expanded);
            }
        }
        try (InputStream in = context.getAssets().open("game-domains.txt")) { domains.addAll(parse(in)); }
        Settings settings = new Settings(context);
        return new DomainRules(domains, settings.set("deny"), settings.set("allow"));
    }
    private Set<String> parse(InputStream stream) throws IOException {
        return DomainRules.read(new InputStreamReader(stream, StandardCharsets.UTF_8), ProtectionLists.MAX_DOMAINS, ProtectionLists.MAX_CHARACTERS);
    }
    public int update() throws IOException {
        // Download and validate every source before replacing the last working snapshot.
        Set<String> base = download(URL, 10_000);
        Set<String> expanded = download(EXPANDED_URL, 50_000);
        Set<String> combined = ProtectionLists.combine(base, expanded);
        if (combined.size() < 50_000) throw new IOException("Incomplete protection list");
        List<String> sorted = new ArrayList<>(combined); Collections.sort(sorted);
        synchronized (DISK_LOCK) {
            FileOutputStream output = file.startWrite();
            try {
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
                writer.write("# Pause protection v2: StevenBlack + HaGeZi Normal\n");
                long bytes = 0;
                for (String domain : sorted) {
                    bytes += domain.length() + 1;
                    if (bytes > 24_000_000) throw new IOException("Protection snapshot too large");
                    writer.write(domain); writer.newLine();
                }
                writer.flush(); file.finishWrite(output);
            } catch (IOException | RuntimeException failure) { file.failWrite(output); throw failure; }
        }
        long now = System.currentTimeMillis(); Settings settings = new Settings(context);
        settings.putNumber("protection_v2_updated", now);
        settings.putText("updated_at", Long.toString(now));
        return combined.size();
    }
    private Set<String> download(String address, int minimum) throws IOException {
        byte[] bytes = source == null ? fetch(address) : source.read(address);
        if (bytes.length > 12_000_000) throw new IOException("Protection source too large");
        Set<String> validated = parse(new ByteArrayInputStream(bytes));
        ProtectionLists.validate(validated, minimum);
        return validated;
    }
    private byte[] fetch(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Protection source unavailable");
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            long deadline = android.os.SystemClock.elapsedRealtime() + 45_000;
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (content.size() + n > 12_000_000 || android.os.SystemClock.elapsedRealtime() > deadline)
                        throw new IOException("Protection download exceeds limits");
                    content.write(buffer, 0, n);
                }
            }
            return content.toByteArray();
        } finally { connection.disconnect(); }
    }
}
