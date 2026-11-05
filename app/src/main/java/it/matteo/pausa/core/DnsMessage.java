package it.matteo.pausa.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Strict single-question DNS parser. Compressed query labels are rejected. */
public final class DnsMessage {
    public final byte[] bytes;
    public final String domain;
    public final int questionEnd;
    public DnsMessage(byte[] data) throws IOException {
        if (data.length < 17 || (data[2] & 0xf8) != 0 || u16(data, 4) != 1
                || u16(data, 6) != 0 || u16(data, 8) != 0) throw new IOException("Invalid DNS query");
        int p = 12;
        StringBuilder name = new StringBuilder();
        while (true) {
            if (p >= data.length) throw new IOException("Missing DNS label");
            int count = data[p++] & 255;
            if (count == 0) break;
            if (count > 63 || p + count > data.length || name.length() + count > 253)
                throw new IOException("Invalid DNS label");
            if (name.length() > 0) name.append('.');
            name.append(new String(data, p, count, StandardCharsets.US_ASCII));
            p += count;
        }
        if (p + 4 > data.length) throw new IOException("Missing DNS question");
        questionEnd = p + 4;
        domain = name.toString().toLowerCase(java.util.Locale.ROOT);
        bytes = data.clone();
    }
    public byte[] error(int code) {
        byte[] response = Arrays.copyOf(bytes, questionEnd);
        response[2] = (byte) (0x80 | (bytes[2] & 1)); // response, preserve recursion desired
        response[3] = (byte) (0x80 | code); // recursion available, no DNSSEC AD claim
        for (int i = 6; i < 12; i++) response[i] = 0;
        return response;
    }
    public boolean accepts(byte[] response) {
        return response.length >= questionEnd && response[0] == bytes[0] && response[1] == bytes[1]
            && (response[2] & 0x80) != 0 && (response[2] & 0x78) == 0 && u16(response, 4) == 1
            && Arrays.equals(Arrays.copyOfRange(bytes, 12, questionEnd), Arrays.copyOfRange(response, 12, questionEnd));
    }
    public static int u16(byte[] data, int p) { return ((data[p] & 255) << 8) | (data[p + 1] & 255); }
    public static void put16(byte[] data, int p, int value) { data[p] = (byte) (value >>> 8); data[p + 1] = (byte) value; }
}
