package it.matteo.pausa.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TcpDnsSessionTest {
    @Test public void handshakeSplitQueryAndGracefulClose() throws Exception {
        for (boolean v6 : new boolean[]{false, true}) {
            List<byte[]> output = new ArrayList<>();
            TcpDnsSession session = new TcpDnsSession(TestPackets.packet(v6, true, new byte[0], 100, 0, 2), 1000, output::add,
                (query, response) -> { try { response.accept(new DnsMessage(query).error(3)); } catch (Exception e) { throw new AssertionError(e); } });
            IpPacket synAck = parse(output.get(0)); assertEquals(0x12, synAck.flags()); assertEquals(101, synAck.acknowledgement());
            long ack = (synAck.sequence() + 1) & 0xffffffffL;
            session.accept(TestPackets.packet(v6, true, new byte[0], 101, ack, 16), 1010);
            byte[] query = TestPackets.query("ads.example.com", 1), framed = new byte[query.length + 2];
            DnsMessage.put16(framed, 0, query.length); System.arraycopy(query, 0, framed, 2, query.length);
            session.accept(TestPackets.packet(v6, true, Arrays.copyOfRange(framed, 0, 1), 101, ack, 24), 1020);
            session.accept(TestPackets.packet(v6, true, Arrays.copyOfRange(framed, 1, framed.length), 102, ack, 24), 1030);
            IpPacket answer = parse(output.get(output.size() - 1)); assertEquals(24, answer.flags());
            assertEquals(query.length, DnsMessage.u16(answer.payload, 0)); assertEquals(3, answer.payload[5] & 15);
            long rx = 101 + framed.length; ack = (answer.sequence() + answer.payload.length) & 0xffffffffL;
            session.accept(TestPackets.packet(v6, true, new byte[0], rx, ack, 17), 1040);
            IpPacket fin = parse(output.get(output.size() - 1)); assertEquals(17, fin.flags());
            session.accept(TestPackets.packet(v6, true, new byte[0], rx + 1, (fin.sequence() + 1) & 0xffffffffL, 16), 1050);
            assertFalse(session.tick(1060));
        }
    }
    @Test public void retransmitsHandshakeThenExpires() throws Exception {
        List<byte[]> output = new ArrayList<>();
        TcpDnsSession session = new TcpDnsSession(TestPackets.packet(false, true, new byte[0], 1, 0, 2), 0, output::add, (q, c) -> c.accept(q));
        assertTrue(session.tick(1100)); assertEquals(2, output.size()); assertArrayEquals(output.get(0), output.get(1));
        assertFalse(session.tick(61_001));
    }
    @Test public void outOfOrderPayloadIsAcknowledgedWithoutResolving() throws Exception {
        List<byte[]> output = new ArrayList<>(); int[] queries = {0};
        TcpDnsSession session = new TcpDnsSession(TestPackets.packet(false, true, new byte[0], 100, 0, 2), 0, output::add, (q, c) -> queries[0]++);
        long ack = (parse(output.get(0)).sequence() + 1) & 0xffffffffL;
        session.accept(TestPackets.packet(false, true, new byte[0], 101, ack, 16), 10);
        session.accept(TestPackets.packet(false, true, new byte[]{0, 20, 1}, 110, ack, 24), 20);
        assertEquals(0, queries[0]); assertEquals(101, parse(output.get(output.size() - 1)).acknowledgement());
    }
    private IpPacket parse(byte[] bytes) throws Exception { return new IpPacket(bytes, bytes.length); }
}
