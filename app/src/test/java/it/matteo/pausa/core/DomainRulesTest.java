package it.matteo.pausa.core;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class DomainRulesTest {
    private Set<String> domains(String... names) { return new HashSet<>(Arrays.asList(names)); }
    @Test public void blocksDomainAndSubdomainsButNotSimilarNames() {
        DomainRules rules = new DomainRules(domains("ads.example.com"), domains(), domains());
        assertTrue(rules.blocks("ads.example.com")); assertTrue(rules.blocks("video.ads.example.com"));
        assertFalse(rules.blocks("notads.example.com")); assertFalse(rules.blocks("ads.example.com.evil.net"));
        assertFalse(rules.blocks("example.com"));
    }
    @Test public void explicitAllowWinsAndDoesNotAllowSiblings() {
        DomainRules rules = new DomainRules(domains("example.com"), domains("safe.example.com"), domains("safe.example.com"));
        assertFalse(rules.blocks("a.safe.example.com")); assertTrue(rules.blocks("other.example.com"));
    }
    @Test public void normalizedCaseTrailingDotAndIdn() {
        assertEquals("ads.example.com", DomainRules.normalize(" ADS.Example.COM. "));
        assertEquals("xn--bcher-kva.de", DomainRules.normalize("bücher.de"));
    }
    @Test public void asciiFastPathMatchesPlatformIdnForValidNames() {
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            String name = "AD" + random.nextInt(1_000_000) + "-tracker.sub" + i + ".Example.COM";
            assertEquals(java.net.IDN.toASCII(name.toLowerCase(Locale.ROOT), java.net.IDN.USE_STD3_ASCII_RULES), DomainRules.normalize(name));
        }
    }
    @Test public void labelLimitsAndIllegalAsciiRemainRejected() {
        for (String invalid : new String[]{"a_.com", "a$.com", "a+.com", "a .com", "a..com", "a.com..", String.join("", Collections.nCopies(64, "a")) + ".com"}) {
            try { DomainRules.normalize(invalid); fail(invalid); } catch (IllegalArgumentException expected) { }
        }
        assertNotNull(DomainRules.normalize(String.join("", Collections.nCopies(63, "a")) + ".com"));
    }
    @Test public void rejectsUrlsWildcardsIpsAndInvalidLabels() {
        for (String bad : new String[]{"https://ads.com", "ads.com/path", "*.example.com", "1.1.1.1", "com", "a..com", "-a.com", "a-.com", ""}) {
            try { DomainRules.normalize(bad); fail(bad); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void hostsParsingKeepsOnlyValidEntries() throws Exception {
        Set<String> parsed = DomainRules.read(new StringReader("# source\n0.0.0.0 ads.example.com ads2.example.com # extra\n127.0.0.1 localhost\n:: tracker.example.com\nplain.example.com\n::1 localhost\n"));
        assertEquals(domains("ads.example.com", "ads2.example.com", "tracker.example.com", "plain.example.com"), parsed);
    }
    @Test public void rejectsOversizedLine() throws Exception {
        try { DomainRules.read(new StringReader(String.join("", Collections.nCopies(17_000, "a")))); fail(); }
        catch (IOException expected) { }
    }
    @Test public void parsingStopsAtDomainBudget() throws Exception {
        try { DomainRules.read(new StringReader("one.example\ntwo.example\nthree.example\n"), 2); fail(); }
        catch (IOException expected) { }
    }
    @Test public void parsingStopsAtCharacterBudgetAndCountsDuplicatesOnce() throws Exception {
        try { DomainRules.read(new StringReader("one.example\ntwo.example\n"), 100, 12); fail(); }
        catch (IOException expected) { }
        assertEquals(1, DomainRules.read(new StringReader("one.example\none.example\n"), 100, 12).size());
    }
    @Test public void localNamesNeverCrashOrBlock() {
        DomainRules rules = new DomainRules(domains("example.com"), domains(), domains());
        assertFalse(rules.blocks("")); assertFalse(rules.blocks("localhost"));
    }
    @Test public void rulesAreImmutableSnapshots() {
        Set<String> input = domains("example.com"); DomainRules rules = new DomainRules(input, domains(), domains());
        input.clear(); assertTrue(rules.blocks("example.com"));
    }
}
