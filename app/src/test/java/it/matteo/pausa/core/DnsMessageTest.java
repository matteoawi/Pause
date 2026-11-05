package it.matteo.pausa.core;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class DnsMessageTest {
    @Test public void parsesAAndAaaaQueries() throws Exception {
        for (int type : new int[]{1, 28}) assertEquals("ads.example.com", new DnsMessage(TestPackets.query("ads.example.com", type)).domain);
    }
    @Test public void nxdomainPreservesQuestionAndIdWithoutDnssecClaim() throws Exception {
        byte[] input = TestPackets.query("ads.example.com", 28); input[3] = 0x20;
        DnsMessage query = new DnsMessage(input); byte[] answer = query.error(3);
        assertEquals(0x1234, DnsMessage.u16(answer, 0)); assertEquals(3, answer[3] & 15);
        assertEquals(0, answer[3] & 0x20); assertTrue(query.accepts(answer)); assertEquals(0, DnsMessage.u16(answer, 10));
    }
    @Test public void rejectsMismatchedTransactionsOrQuestions() throws Exception {
        DnsMessage query = new DnsMessage(TestPackets.query("example.com", 1)); byte[] reply = query.error(0);
        reply[0]++; assertFalse(query.accepts(reply)); reply[0]--; reply[13]++; assertFalse(query.accepts(reply));
    }
    @Test public void rejectsCompressedAndMultipleQuestions() throws Exception {
        byte[] query = TestPackets.query("example.com", 1); query[12] = (byte) 0xc0;
        try { new DnsMessage(query); fail(); } catch (IOException expected) { }
        query = TestPackets.query("example.com", 1); query[5] = 2;
        try { new DnsMessage(query); fail(); } catch (IOException expected) { }
    }
    @Test public void malformedPacketsCannotCrashParser() throws Exception {
        Random random = new Random(9);
        for (int i = 0; i < 20_000; i++) {
            byte[] data = new byte[random.nextInt(300)]; random.nextBytes(data);
            if (data.length >= 12) { data[2] = 1; data[4] = 0; data[5] = 1; for (int j = 6; j < 10; j++) data[j] = 0; }
            try { new DnsMessage(data); } catch (IOException expected) { }
        }
    }
}
