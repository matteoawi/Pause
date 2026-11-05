package it.matteo.pausa.core;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class IpPacketTest {
    @Test public void udpRepliesReverseAddressesAndHaveValidChecksums() throws Exception {
        for (boolean v6 : new boolean[]{false, true}) {
            IpPacket query = TestPackets.packet(v6, false, TestPackets.query("example.com", 1), 0, 0, 0);
            byte[] dns = new DnsMessage(query.payload).error(3); byte[] raw = query.udpReply(dns);
            IpPacket reply = new IpPacket(raw, raw.length);
            assertArrayEquals(query.source, reply.destination); assertArrayEquals(query.destination, reply.source);
            assertEquals(53, reply.sourcePort); assertEquals(45678, reply.destinationPort); assertArrayEquals(dns, reply.payload);
            if (!v6) assertEquals(0, IpPacket.checksum(raw, 0, 20, 0));
            int pseudo = 17 + 8 + dns.length;
            for (int i = 0; i < query.source.length; i += 2) pseudo += DnsMessage.u16(query.source, i) + DnsMessage.u16(query.destination, i);
            assertEquals(0, IpPacket.checksum(raw, reply.transportOffset, 8 + dns.length, pseudo));
        }
    }
    @Test public void tcpReplyRetainsSequenceAndChecksum() throws Exception {
        IpPacket request = TestPackets.packet(true, true, new byte[0], 50, 0, 2);
        byte[] data = request.tcpReply(new byte[]{1, 2, 3}, 0xfffffff0L, 51, 0x18);
        IpPacket reply = new IpPacket(data, data.length);
        assertEquals(0xfffffff0L, reply.sequence()); assertEquals(51, reply.acknowledgement()); assertEquals(0x18, reply.flags());
        int pseudo = 6 + 23;
        for (int i = 0; i < request.source.length; i += 2) pseudo += DnsMessage.u16(request.source, i) + DnsMessage.u16(request.destination, i);
        assertEquals(0, IpPacket.checksum(data, 40, 23, pseudo));
    }
    @Test public void fragmentedAndTruncatedPacketsAreRejected() throws Exception {
        byte[] packet = TestPackets.packet(false, false, new byte[20], 0, 0, 0).raw; packet[6] = 0x20;
        try { new IpPacket(packet, packet.length); fail(); } catch (IOException expected) { }
        packet = Arrays.copyOf(packet, 28); packet[6] = 0; packet[9] = 6; DnsMessage.put16(packet, 2, 28);
        try { new IpPacket(packet, packet.length); fail(); } catch (IOException expected) { }
    }
    @Test public void randomPacketsNeverCrashParser() throws Exception {
        Random random = new Random(27);
        for (int i = 0; i < 20_000; i++) {
            byte[] data = new byte[random.nextInt(300)]; random.nextBytes(data);
            try { new IpPacket(data, data.length); } catch (IOException expected) { }
        }
    }
}
