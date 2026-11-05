package it.matteo.pausa.core;

import java.io.IOException;
import java.util.Arrays;

/** DNS-only TUN codec for unfragmented IPv4 and IPv6 UDP/TCP packets. */
public final class IpPacket {
    public final boolean ipv6;
    public final int protocol, sourcePort, destinationPort, transportOffset;
    public final byte[] source, destination, payload, raw;
    public IpPacket(byte[] data, int length) throws IOException {
        if (length < 20 || length > data.length) throw new IOException("Short IP packet");
        raw = Arrays.copyOf(data, length);
        int version = (data[0] & 255) >>> 4;
        ipv6 = version == 6;
        int total;
        if (version == 4) {
            transportOffset = (data[0] & 15) * 4;
            total = DnsMessage.u16(data, 2);
            if (transportOffset < 20 || (DnsMessage.u16(data, 6) & 0x3fff) != 0) throw new IOException("Fragmented IPv4");
            protocol = data[9] & 255;
            source = Arrays.copyOfRange(data, 12, 16);
            destination = Arrays.copyOfRange(data, 16, 20);
        } else if (ipv6 && length >= 40) {
            transportOffset = 40;
            total = 40 + DnsMessage.u16(data, 4);
            protocol = data[6] & 255;
            source = Arrays.copyOfRange(data, 8, 24);
            destination = Arrays.copyOfRange(data, 24, 40);
        } else throw new IOException("Unsupported IP version");
        if (total != length || transportOffset + 8 > total || (protocol != 17 && protocol != 6)) throw new IOException("Unsupported IP transport");
        sourcePort = DnsMessage.u16(data, transportOffset);
        destinationPort = DnsMessage.u16(data, transportOffset + 2);
        if (protocol == 6 && transportOffset + 20 > total) throw new IOException("Short TCP header");
        int header = protocol == 17 ? 8 : ((data[transportOffset + 12] & 255) >>> 4) * 4;
        if (header < (protocol == 17 ? 8 : 20) || transportOffset + header > total)
            throw new IOException("Invalid transport header");
        if (protocol == 17 && DnsMessage.u16(data, transportOffset + 4) != total - transportOffset)
            throw new IOException("Invalid UDP length");
        payload = Arrays.copyOfRange(data, transportOffset + header, total);
    }
    public byte[] udpReply(byte[] dns) { return reply(17, dns, 0, 0, 0); }
    public byte[] tcpReply(byte[] data, long sequence, long ack, int flags) { return reply(6, data, sequence, ack, flags); }
    public long sequence() { return u32(raw, transportOffset + 4); }
    public long acknowledgement() { return u32(raw, transportOffset + 8); }
    public int flags() { return raw[transportOffset + 13] & 255; }
    public String flowKey() { return Arrays.toString(source) + ":" + sourcePort + ":" + Arrays.toString(destination); }
    private byte[] reply(int proto, byte[] data, long seq, long ack, int flags) {
        int ip = ipv6 ? 40 : 20, transport = proto == 17 ? 8 : 20;
        byte[] out = new byte[ip + transport + data.length];
        if (ipv6) {
            out[0] = 0x60;
            DnsMessage.put16(out, 4, transport + data.length);
            out[6] = (byte) proto; out[7] = 64;
            System.arraycopy(destination, 0, out, 8, 16); System.arraycopy(source, 0, out, 24, 16);
        } else {
            out[0] = 0x45; out[8] = 64; out[9] = (byte) proto;
            DnsMessage.put16(out, 2, out.length);
            System.arraycopy(destination, 0, out, 12, 4); System.arraycopy(source, 0, out, 16, 4);
            DnsMessage.put16(out, 10, checksum(out, 0, ip, 0));
        }
        DnsMessage.put16(out, ip, destinationPort); DnsMessage.put16(out, ip + 2, sourcePort);
        if (proto == 17) DnsMessage.put16(out, ip + 4, transport + data.length);
        else {
            put32(out, ip + 4, seq); put32(out, ip + 8, ack);
            out[ip + 12] = 0x50; out[ip + 13] = (byte) flags;
            DnsMessage.put16(out, ip + 14, 65535);
        }
        System.arraycopy(data, 0, out, ip + transport, data.length);
        int seed = proto + transport + data.length;
        for (int i = 0; i < source.length; i += 2) seed += DnsMessage.u16(source, i) + DnsMessage.u16(destination, i);
        int sum = checksum(out, ip, transport + data.length, seed);
        DnsMessage.put16(out, ip + (proto == 17 ? 6 : 16), sum == 0 ? 65535 : sum);
        return out;
    }
    public static int checksum(byte[] data, int offset, int length, long initial) {
        long sum = initial;
        for (int i = 0; i < length; i += 2) sum += ((data[offset + i] & 255) << 8) | (i + 1 < length ? data[offset + i + 1] & 255 : 0);
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return (int) (~sum & 65535);
    }
    private static long u32(byte[] data, int p) { return ((long) DnsMessage.u16(data, p) << 16) | DnsMessage.u16(data, p + 2); }
    private static void put32(byte[] data, int p, long n) { DnsMessage.put16(data, p, (int) (n >>> 16)); DnsMessage.put16(data, p + 2, (int) n); }
}
