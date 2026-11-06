package it.matteo.pausa.core;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.*;

/** DNS wireformat over authenticated HTTPS only. No bootstrap DNS or plaintext fallback. */
public final class DohResolver implements Closeable {
    @FunctionalInterface public interface SocketProtector { boolean protect(Socket socket); }
    private static final String MEDIA_TYPE = "application/dns-message";
    private static final int MAX_RESPONSE = 65535;
    private final URL[] endpoints;
    private final int timeout;
    private final Set<Socket> rawSockets = ConcurrentHashMap.newKeySet();
    private final Set<Socket> tlsSockets = ConcurrentHashMap.newKeySet();
    private final SSLSocketFactory tls;
    private volatile boolean closed;

    public DohResolver(SocketProtector protector) {
        this(protector, (SSLSocketFactory) SSLSocketFactory.getDefault(), new String[]{
            "https://1.1.1.1/dns-query", "https://[2606:4700:4700::1111]/dns-query",
            "https://1.0.0.1/dns-query", "https://[2606:4700:4700::1001]/dns-query"
        }, 2500);
    }

    // Package-private seam for real local TLS tests, never a user-configurable trust override.
    DohResolver(SocketProtector protector, SSLSocketFactory delegate, String[] urls, int timeout) {
        this.timeout = timeout;
        endpoints = new URL[urls.length];
        try {
            for (int i = 0; i < urls.length; i++) {
                endpoints[i] = new URL(urls[i]);
                if (!"https".equals(endpoints[i].getProtocol())) throw new IllegalArgumentException("HTTPS required");
            }
        } catch (MalformedURLException e) { throw new IllegalArgumentException(e); }
        tls = new SSLSocketFactory() {
            private Socket secure(Socket socket, boolean protect) throws IOException {
                try {
                    if (!(socket instanceof SSLSocket)) throw new IOException("TLS required");
                    track(tlsSockets, socket);
                    if (protect && !protector.protect(socket)) throw new IOException("Cannot protect DNS socket");
                    SSLSocket ssl = (SSLSocket) socket;
                    List<String> enabled = new ArrayList<>();
                    for (String protocol : ssl.getSupportedProtocols())
                        if (protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3")) enabled.add(protocol);
                    if (enabled.isEmpty()) throw new IOException("Modern TLS unavailable");
                    ssl.setEnabledProtocols(enabled.toArray(new String[0]));
                    SSLParameters parameters = ssl.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(parameters);
                    return ssl;
                } catch (IOException | RuntimeException e) { socket.close(); throw e; }
            }
            @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
            @Override public Socket createSocket() throws IOException { return secure(delegate.createSocket(), true); }
            @Override public Socket createSocket(Socket raw, String host, int port, boolean autoClose) throws IOException {
                track(rawSockets, raw);
                if (!protector.protect(raw)) { raw.close(); throw new IOException("Cannot protect DNS socket"); }
                return secure(delegate.createSocket(raw, host, port, autoClose), false);
            }
            private Socket dial(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
                Socket raw = new Socket();
                try {
                    track(rawSockets, raw);
                    if (!protector.protect(raw)) throw new IOException("Cannot protect DNS socket");
                    if (local != null) raw.bind(new InetSocketAddress(local, localPort));
                    raw.connect(new InetSocketAddress(host, port), timeout);
                    raw.setSoTimeout(timeout);
                    return secure(delegate.createSocket(raw, host.getHostAddress(), port, true), false);
                } catch (IOException | RuntimeException e) { raw.close(); throw e; }
            }
            @Override public Socket createSocket(String host, int port) throws IOException { return dial(InetAddress.getByName(host), port, null, 0); }
            @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return dial(InetAddress.getByName(host), port, local, localPort); }
            @Override public Socket createSocket(InetAddress host, int port) throws IOException { return dial(host, port, null, 0); }
            @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return dial(host, port, local, localPort); }

        };
    }

    public byte[] resolve(DnsMessage query) throws IOException {
        for (URL endpoint : endpoints) {
            checkOpen();
            try { return exchange(endpoint, query); }
            catch (IOException ignored) { /* Retry only another authenticated HTTPS endpoint. */ }
        }
        checkOpen();
        throw new IOException("Encrypted DNS unavailable");
    }

    private byte[] exchange(URL endpoint, DnsMessage query) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(tls);
        // Keep the platform hostname verifier and system CA trust. Never follow a redirect.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", MEDIA_TYPE);
        connection.setRequestProperty("Content-Type", MEDIA_TYPE);
        connection.setFixedLengthStreamingMode(query.bytes.length);
        connection.setDoOutput(true);
        try {
            checkOpen();
            try (OutputStream out = connection.getOutputStream()) { out.write(query.bytes); }
            if (connection.getResponseCode() != 200) throw new IOException("Invalid DoH status");
            String type = connection.getContentType();
            if (type == null || !MEDIA_TYPE.equalsIgnoreCase(type.split(";", 2)[0].trim()))
                throw new IOException("Invalid DoH content type");
            long length = connection.getContentLengthLong();
            if (length > MAX_RESPONSE || (length >= 0 && length < 17)) throw new IOException("Invalid DoH length");
            byte[] answer;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkOpen();
                    if (count > MAX_RESPONSE - body.size()) throw new IOException("Oversize DoH response");
                    body.write(buffer, 0, count);
                }
                answer = body.toByteArray();
            }
            if ((length >= 0 && answer.length != length) || !query.accepts(answer) || (answer[2] & 2) != 0)
                throw new IOException("Invalid DoH DNS response");
            checkOpen();
            return answer;
        } finally {
            connection.disconnect();
            prune();
        }
    }

    private void checkOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("DNS stopped");
    }
    private void track(Set<Socket> sockets, Socket socket) throws IOException {
        prune();
        sockets.add(socket);
        if (closed) { socket.close(); sockets.remove(socket); throw new IOException("DNS stopped"); }
    }
    private void prune() {
        rawSockets.removeIf(Socket::isClosed);
        tlsSockets.removeIf(Socket::isClosed);
    }
    @Override public void close() {
        closed = true;
        // Close raw transport first, so cancelling a TLS handshake/read cannot wait for close_notify.
        for (Socket socket : rawSockets) try { socket.close(); } catch (IOException ignored) { }
        for (Socket socket : tlsSockets) try { socket.close(); } catch (IOException ignored) { }
        rawSockets.clear(); tlsSockets.clear();
    }
}
