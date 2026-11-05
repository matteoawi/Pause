package it.matteo.pausa.core;

import java.io.*;
import java.net.*;

final class TestPackets {
    static byte[] query(String name, int type) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        for (String label : name.split("\\.")) { out.write(label.length()); out.write(label.getBytes("US-ASCII")); }
        out.write(0); out.write(0); out.write(type); out.write(0); out.write(1); return out.toByteArray();
    }
    static IpPacket packet(boolean ipv6, boolean tcp, byte[] payload, long seq, long ack, int flags) throws Exception {
        int ip = ipv6 ? 40 : 20, transport = tcp ? 20 : 8;
        byte[] data = new byte[ip + transport + payload.length];
        if (ipv6) {
            data[0] = 0x60; DnsMessage.put16(data, 4, data.length - 40); data[6] = (byte) (tcp ? 6 : 17); data[7] = 64;
            System.arraycopy(InetAddress.getByName("fd00:7061:7573::1").getAddress(), 0, data, 8, 16);
            System.arraycopy(InetAddress.getByName("fd00:7061:7573::2").getAddress(), 0, data, 24, 16);
        } else {
            data[0] = 0x45; DnsMessage.put16(data, 2, data.length); data[8] = 64; data[9] = (byte) (tcp ? 6 : 17);
            data[12] = (byte) 198; data[13] = 18; data[15] = 1; data[16] = (byte) 198; data[17] = 18; data[19] = 2;
            DnsMessage.put16(data, 10, IpPacket.checksum(data, 0, 20, 0));
        }
        DnsMessage.put16(data, ip, 45678); DnsMessage.put16(data, ip + 2, 53);
        if (tcp) {
            DnsMessage.put16(data, ip + 4, (int) (seq >>> 16)); DnsMessage.put16(data, ip + 6, (int) seq);
            DnsMessage.put16(data, ip + 8, (int) (ack >>> 16)); DnsMessage.put16(data, ip + 10, (int) ack);
            data[ip + 12] = 0x50; data[ip + 13] = (byte) flags; DnsMessage.put16(data, ip + 14, 65535);
        } else DnsMessage.put16(data, ip + 4, transport + payload.length);
        System.arraycopy(payload, 0, data, ip + transport, payload.length);
        return new IpPacket(data, data.length);
    }
}
