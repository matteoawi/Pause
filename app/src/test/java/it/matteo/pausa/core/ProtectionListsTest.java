package it.matteo.pausa.core;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class ProtectionListsTest {
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    @Test public void rejectsServiceWideBlocksAndPublicSuffixes() throws Exception {
        for (String unsafe : new String[]{"youtube.com", "googlevideo.com", "instagram.com", "cdninstagram.com", "google.com", "co.uk", "github.com"}) {
            try { ProtectionLists.validate(set("doubleclick.net", unsafe), 1); fail(unsafe); }
            catch (IOException expected) { }
        }
        ProtectionLists.validate(set("doubleclick.net", "ads.youtube.com"), 1);
    }
    @Test public void rejectsIncompleteOrNonListContent() throws Exception {
        for (Set<String> invalid : Arrays.asList(set(), set("example.net"))) {
            try { ProtectionLists.validate(invalid, 1); fail(); } catch (IOException expected) { }
        }
        try { ProtectionLists.validate(set("doubleclick.net"), 2); fail(); } catch (IOException expected) { }
    }
    @Test public void rejectsAggregateBudgetBeforeAllocatingOrReadingInputs() throws Exception {
        Set<String> large = new AbstractSet<String>() {
            @Override public int size() { return 200_000; }
            @Override public Iterator<String> iterator() { throw new AssertionError("Should reject before iteration"); }
        };
        try { ProtectionLists.combine(large, large); fail(); } catch (IOException expected) { }
    }
    @Test public void activeAndReplacementSnapshotFitTestHeapOf128MiB() throws Exception {
        DomainRules active = new DomainRules(ProtectionLists.combine(read("hosts.txt"), read("expanded-domains.txt")), set(), set());
        DomainRules replacement = new DomainRules(ProtectionLists.combine(read("hosts.txt"), read("expanded-domains.txt")), set(), set());
        assertTrue(active.blocks("sst.013.nl")); assertTrue(replacement.blocks("sst.013.nl"));
        assertTrue(Runtime.getRuntime().maxMemory() <= 128L * 1024 * 1024);
    }
    @Test public void bundledSourcesExpandCoverageAndPreserveWebsites() throws Exception {
        Set<String> base = read("hosts.txt"), expanded = read("expanded-domains.txt");
        ProtectionLists.validate(base, 50_000); ProtectionLists.validate(expanded, 50_000);
        Set<String> compact = ProtectionLists.combine(base, expanded);
        DomainRules old = new DomainRules(base, set(), set());
        DomainRules next = new DomainRules(compact, set(), set());
        int newlyCovered = 0;
        for (String domain : expanded) {
            assertTrue(domain, next.blocks(domain)); assertTrue("subdomain: " + domain, next.blocks("sub." + domain));
            if (!old.blocks(domain)) newlyCovered++;
        }
        assertTrue("Meaningful additional coverage", newlyCovered > 25_000);
        for (String domain : base) assertTrue("Existing rule lost: " + domain, next.blocks(domain));
        for (String safe : new String[]{"www.youtube.com", "i.ytimg.com", "www.instagram.com", "scontent.cdninstagram.com", "www.example.com", "accounts.google.com", "github.com"})
            assertFalse("Essential site blocked: " + safe, next.blocks(safe));
        assertTrue(compact.size() < base.size() + expanded.size());
        String sample = expanded.stream().filter(d -> !old.blocks(d)).findFirst().get();
        assertFalse(new DomainRules(compact, set(sample), set(sample)).blocks("sub." + sample));
        System.out.println("Bundled coverage: base=" + base.size() + ", expanded=" + expanded.size() + ", compact=" + compact.size() + ", newly covered=" + newlyCovered);
    }
    private Set<String> read(String asset) throws IOException {
        try (Reader in = new InputStreamReader(new FileInputStream("src/main/assets/" + asset), java.nio.charset.StandardCharsets.UTF_8)) { return DomainRules.read(in); }
    }
}
