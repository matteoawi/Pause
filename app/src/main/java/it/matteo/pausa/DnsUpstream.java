package it.matteo.pausa;

import android.net.VpnService;
import it.matteo.pausa.core.DnsMessage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Connected protected sockets prevent VPN loops and unrelated-source DNS replies. */
public final class DnsUpstream implements Closeable {
    private final VpnService service;
    private final Set<Closeable> sockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean closed;
    public DnsUpstream(VpnService service) { this.service = service; }
    public byte[] resolve(DnsMessage query) throws IOException {
        for (String host : new String[]{"1.1.1.1", "2606:4700:4700::1111", "1.0.0.1", "2606:4700:4700::1001"}) {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("DNS stopped");
            try (DatagramSocket socket = new DatagramSocket()) {
                track(socket);
                try {
                    if (!service.protect(socket)) throw new IOException("Cannot protect DNS socket");
                    socket.connect(InetAddress.getByName(host), 53);
                    socket.setSoTimeout(2500);
                    socket.send(new DatagramPacket(query.bytes, query.bytes.length));
                    byte[] buffer = new byte[65507];
                    DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                    socket.receive(incoming);
                    byte[] answer = Arrays.copyOf(buffer, incoming.getLength());
                    if (!query.accepts(answer)) throw new IOException("Unmatched DNS response");
                    if ((answer[2] & 2) != 0) return tcp(host, query);
                    return answer;
                } finally { sockets.remove(socket); }
            } catch (IOException ignored) { /* next resolver address */ }
        }
        throw new IOException("DNS non raggiungibile. Controlla la connessione.");
    }
    private byte[] tcp(String host, DnsMessage query) throws IOException {
        try (Socket socket = new Socket()) {
            track(socket);
            try {
                if (!service.protect(socket)) throw new IOException("Cannot protect TCP DNS socket");
                socket.connect(new InetSocketAddress(host, 53), 2500); socket.setSoTimeout(2500);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeShort(query.bytes.length); out.write(query.bytes); out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int count = in.readUnsignedShort();
                if (count < 17) throw new IOException("Invalid DNS length");
                byte[] response = new byte[count]; in.readFully(response);
                if (!query.accepts(response) || (response[2] & 2) != 0) throw new IOException("Invalid TCP DNS reply");
                return response;
            } finally { sockets.remove(socket); }
        }
    }
    private void track(Closeable socket) throws IOException {
        sockets.add(socket);
        if (closed) { sockets.remove(socket); socket.close(); throw new IOException("DNS stopped"); }
    }
    @Override public void close() {
        closed = true;
        for (Closeable socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        sockets.clear();
    }
}
