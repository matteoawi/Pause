package it.matteo.pausa.core;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;

/** Bounded stop-and-wait TCP endpoint for DNS, including retries and segmented queries. */
public final class TcpDnsSession {
    public interface Resolver { void resolve(byte[] query, Consumer<byte[]> callback); }
    private final IpPacket peer;
    private final Consumer<byte[]> output;
    private final Resolver resolver;
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();
    private final Queue<byte[]> outgoing = new ArrayDeque<>();
    private long rx, tx, pendingEnd, lastSend, lastActivity;
    private byte[] pending;
    private int retries;
    private boolean established, finishing, finSent, closed;
    private int resolving;

    public TcpDnsSession(IpPacket syn, long now, Consumer<byte[]> output, Resolver resolver) {
        peer = syn; this.output = output; this.resolver = resolver;
        rx = wrap(syn.sequence() + 1);
        tx = new java.security.SecureRandom().nextInt() & 0xffffffffL;
        lastActivity = now;
        send(new byte[0], 0x12, now); // SYN + ACK
    }
    public synchronized void accept(IpPacket packet, long now) {
        if (closed) return;
        lastActivity = now;
        if ((packet.flags() & 4) != 0) { closed = true; return; }
        if ((packet.flags() & 2) != 0) { if (pending != null) output.accept(pending); return; }
        if ((packet.flags() & 16) == 0) return;
        if (pending != null && packet.acknowledgement() == pendingEnd) {
            pending = null;
            if (!established) established = true;
            if (finSent) { closed = true; return; }
        }
        if (!established) return;
        if (packet.sequence() != rx) { ack(); pump(now); return; }
        if (packet.payload.length > 0) {
            if (finishing || received.size() + packet.payload.length > 65537) { reset(); return; }
            received.write(packet.payload, 0, packet.payload.length);
            rx = wrap(rx + packet.payload.length);
            ack();
            drainQueries();
        }
        if ((packet.flags() & 1) != 0) { rx = wrap(rx + 1); finishing = true; ack(); }
        pump(now);
    }
    private void drainQueries() {
        byte[] bytes = received.toByteArray();
        int at = 0;
        while (bytes.length - at >= 2) {
            int length = DnsMessage.u16(bytes, at);
            if (length < 17) { reset(); return; }
            if (bytes.length - at < length + 2) break;
            if (++resolving > 8) { reset(); return; }
            byte[] query = Arrays.copyOfRange(bytes, at + 2, at + 2 + length);
            resolver.resolve(query, answer -> answer(answer, System.nanoTime() / 1_000_000));
            at += length + 2;
        }
        received.reset();
        received.write(bytes, at, bytes.length - at);
    }
    private synchronized void answer(byte[] bytes, long now) {
        resolving--;
        if (closed || bytes == null) { if (bytes == null) reset(); return; }
        if (outgoing.size() > 512) { reset(); return; }
        byte[] framed = new byte[bytes.length + 2];
        DnsMessage.put16(framed, 0, bytes.length);
        System.arraycopy(bytes, 0, framed, 2, bytes.length);
        // No MSS option is advertised: stay under IPv4's default 536-byte MSS.
        for (int at = 0; at < framed.length; at += 500)
            outgoing.add(Arrays.copyOfRange(framed, at, Math.min(framed.length, at + 500)));
        pump(now);
    }
    private void pump(long now) {
        if (pending != null || closed) return;
        if (!outgoing.isEmpty()) send(outgoing.remove(), 0x18, now);
        else if (finishing && resolving == 0 && !finSent) { finSent = true; send(new byte[0], 0x11, now); }
    }
    private void send(byte[] payload, int flags, long now) {
        pending = peer.tcpReply(payload, tx, rx, flags);
        tx = wrap(tx + payload.length + ((flags & 3) != 0 ? 1 : 0));
        pendingEnd = tx; lastSend = now; retries = 0;
        output.accept(pending);
    }
    private void ack() { output.accept(peer.tcpReply(new byte[0], tx, rx, 0x10)); }
    private void reset() { closed = true; output.accept(peer.tcpReply(new byte[0], tx, rx, 0x14)); }
    public synchronized boolean tick(long now) {
        if (now - lastActivity > 60_000) closed = true;
        if (!closed && pending != null && now - lastSend > 1000) {
            if (++retries > 5) reset();
            else { lastSend = now; output.accept(pending); }
        }
        return !closed;
    }
    private static long wrap(long value) { return value & 0xffffffffL; }
}
