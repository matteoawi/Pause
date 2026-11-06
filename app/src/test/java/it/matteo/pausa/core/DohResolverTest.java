package it.matteo.pausa.core;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real TLS connections, with ephemeral test-only keys generated outside tracked source. */
public class DohResolverTest {
    private static SSLContext serverContext, trustedContext, wrongNameServer, wrongNameTrust;
    private TestServer server;
    private ExecutorService handlers;
    private final List<DohResolver> clients = new ArrayList<>();
    private final AtomicInteger received = new AtomicInteger();

    @BeforeClass public static void certificates() throws Exception {
        SSLContext[] valid = contexts("127.0.0.1"); serverContext = valid[0]; trustedContext = valid[1];
        SSLContext[] wrong = contexts("127.0.0.2"); wrongNameServer = wrong[0]; wrongNameTrust = wrong[1];
    }
    private static SSLContext[] contexts(String ip) throws Exception {
        Path directory = Files.createTempDirectory("pause-tls-test-");
        Path store = directory.resolve("test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "2", "-dname", "CN=Pause test only", "-ext", "SAN=ip:" + ip,
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "test-only-password",
            "-keypass", "test-only-password", "-noprompt").redirectErrorStream(true).start();
        process.getInputStream().transferTo(OutputStream.nullOutputStream());
        assertEquals(0, process.waitFor());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) { keys.load(in, "test-only-password".toCharArray()); }
        Files.delete(store); Files.delete(directory);
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "test-only-password".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        SSLContext serving = SSLContext.getInstance("TLS"); serving.init(km.getKeyManagers(), null, null);
        SSLContext trusting = SSLContext.getInstance("TLS"); trusting.init(null, tm.getTrustManagers(), null);
        return new SSLContext[]{serving, trusting};
    }
    private void start(SSLContext context, HttpHandler handler) throws IOException {
        handlers = Executors.newCachedThreadPool();
        server = new TestServer(context, exchange -> {
            received.incrementAndGet(); handler.handle(exchange);
        }, handlers);
    }
    private String endpoint() { return "https://127.0.0.1:" + server.getAddress().getPort() + "/dns-query"; }
    private DohResolver client(SSLContext trust, int timeout) {
        DohResolver resolver = new DohResolver(socket -> true, trust.getSocketFactory(), new String[]{endpoint()}, timeout);
        clients.add(resolver); return resolver;
    }
    private static DnsMessage query() throws IOException { return new DnsMessage(new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0,7,101,120,97,109,112,108,101,3,99,111,109,0,0,1,0,1}); }
    private static void answer(HttpExchange exchange, String type, byte[] body, long length) throws IOException {
        if (type != null) exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, length);
        if (length == 0) {
            exchange.socket.getOutputStream().write((Integer.toHexString(body.length) + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            exchange.socket.getOutputStream().write(body);
            exchange.socket.getOutputStream().write("\r\n0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } else exchange.socket.getOutputStream().write(body);
    }
    private static void fails(DohResolver resolver) throws Exception {
        try { resolver.resolve(query()); fail("Must reject unsafe/invalid response"); } catch (IOException expected) { }
    }
    @After public void cleanup() {
        for (DohResolver resolver : clients) resolver.close();
        if (server != null) server.close();
        if (handlers != null) handlers.shutdownNow();
    }
    @Test public void authenticatedPostPreservesDnsQuestionAndUsesModernTls() throws Exception {
        start(serverContext, exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/dns-message", exchange.getRequestHeaders().getFirst("Accept"));
            assertEquals("application/dns-message", exchange.getRequestHeaders().getFirst("Content-Type"));
            String protocol = exchange.socket.getSession().getProtocol();
            assertTrue(protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3"));
            DnsMessage request = new DnsMessage(exchange.getRequestBody().readAllBytes());
            assertEquals("example.com", request.domain);
            byte[] response = request.error(0); answer(exchange, "application/dns-message", response, response.length);
        });
        assertArrayEquals(query().error(0), client(trustedContext, 1500).resolve(query()));
        assertEquals(1, received.get());
    }
    @Test public void untrustedCertificateNeverReceivesDnsQuery() throws Exception {
        start(serverContext, exchange -> fail("Untrusted server received a query"));
        fails(client(SSLContext.getDefault(), 1000)); assertEquals(0, received.get());
    }
    @Test public void trustedCertificateWithWrongIpNeverReceivesDnsQuery() throws Exception {
        start(wrongNameServer, exchange -> fail("Wrong identity received a query"));
        fails(client(wrongNameTrust, 1000)); assertEquals(0, received.get());
    }
    @Test public void redirectsAreNotFollowedIncludingPlaintextDestinations() throws Exception {
        try (ServerSocket trap = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            trap.setSoTimeout(300);
            start(serverContext, exchange -> {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + trap.getLocalPort() + "/leak");
                exchange.sendResponseHeaders(302, -1);
            });
            fails(client(trustedContext, 1000));
            try (Socket ignored = trap.accept()) { fail("Followed plaintext redirect"); }
            catch (SocketTimeoutException expected) { }
        }
    }
    @Test public void rejectsWrongMediaTypeAndHttpErrors() throws Exception {
        start(serverContext, exchange -> {
            if (received.get() == 1) answer(exchange, "text/html", query().error(0), query().questionEnd);
            else exchange.sendResponseHeaders(503, -1);
        });
        fails(client(trustedContext, 1000)); fails(client(trustedContext, 1000));
    }
    @Test public void rejectsMismatchedTruncatedAndShortDnsReplies() throws Exception {
        start(serverContext, exchange -> {
            byte[] body = query().error(0);
            if (received.get() == 1) body[0]++;
            else if (received.get() == 2) body[2] |= 2;
            else body = new byte[5];
            answer(exchange, "application/dns-message", body, body.length);
        });
        for (int i = 0; i < 3; i++) fails(client(trustedContext, 1000));
    }
    @Test public void rejectsOversizeDeclaredAndChunkedResponses() throws Exception {
        start(serverContext, exchange -> {
            byte[] body = new byte[65536];
            answer(exchange, "application/dns-message", body, received.get() == 1 ? body.length : 0);
        });
        fails(client(trustedContext, 1000)); fails(client(trustedContext, 1000));
    }
    @Test public void failureRetriesOnlyAnotherHttpsEndpoint() throws Exception {
        start(serverContext, exchange -> {
            if (received.get() == 1) exchange.sendResponseHeaders(503, -1);
            else { byte[] body = query().error(0); answer(exchange, "application/dns-message", body, body.length); }
        });
        DohResolver resolver = new DohResolver(socket -> true, trustedContext.getSocketFactory(),
            new String[]{endpoint(), endpoint()}, 1000); clients.add(resolver);
        assertTrue(query().accepts(resolver.resolve(query()))); assertEquals(2, received.get());
    }
    @Test public void closeCancelsBlockedReadAndRejectsFutureRequests() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        start(serverContext, exchange -> {
            exchange.getRequestBody().readAllBytes(); reading.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        DohResolver resolver = client(trustedContext, 10000);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = worker.submit(() -> { try { fails(resolver); } catch (Exception e) { throw new RuntimeException(e); } });
            assertTrue(reading.await(3, TimeUnit.SECONDS)); resolver.close();
            result.get(2, TimeUnit.SECONDS); fails(resolver); assertEquals(1, received.get());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test public void readTimeoutDoesNotReturnAnUnverifiedAnswer() throws Exception {
        start(serverContext, exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        fails(client(trustedContext, 800)); assertEquals("Reached HTTP read phase", 1, received.get());
    }
    @Test public void closeCancelsAStalledTlsHandshake() throws Exception {
        try (ServerSocket trap = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            trap.setSoTimeout(3000);
            DohResolver resolver = new DohResolver(socket -> true, trustedContext.getSocketFactory(),
                new String[]{"https://127.0.0.1:" + trap.getLocalPort() + "/dns-query"}, 10000);
            clients.add(resolver);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = worker.submit(() -> { try { fails(resolver); } catch (Exception e) { throw new RuntimeException(e); } });
                try (Socket accepted = trap.accept()) {
                    accepted.setSoTimeout(3000);
                    assertTrue("Client started TLS", accepted.getInputStream().read() >= 0);
                    resolver.close(); result.get(2, TimeUnit.SECONDS);
                }
            } finally { worker.shutdownNow(); }
        }
    }
    @Test public void plaintextEndpointsAreRejectedBeforeConnecting() {
        try {
            new DohResolver(socket -> true, trustedContext.getSocketFactory(), new String[]{"http://127.0.0.1/dns-query"}, 1000);
            fail("Accepted plaintext endpoint");
        } catch (IllegalArgumentException expected) { }
    }
    @Test public void failedVpnSocketProtectionNeverSendsQuery() throws Exception {
        start(serverContext, exchange -> fail("Unprotected socket received a query"));
        DohResolver resolver = new DohResolver(socket -> false, trustedContext.getSocketFactory(), new String[]{endpoint()}, 1000);
        clients.add(resolver); fails(resolver); assertEquals(0, received.get());
    }
    // Minimal HTTP/1.1 test fixture only; production HTTP and TLS use platform libraries.
    @FunctionalInterface private interface HttpHandler { void handle(HttpExchange exchange) throws IOException; }
    private static final class Headers extends TreeMap<String, String> {
        Headers() { super(String.CASE_INSENSITIVE_ORDER); }
        void set(String key, String value) { put(key, value); }
        String getFirst(String key) { return get(key); }
    }
    private static final class HttpExchange {
        final SSLSocket socket;
        final String method;
        final Headers request = new Headers(), response = new Headers();
        final byte[] body;
        HttpExchange(SSLSocket socket) throws IOException {
            this.socket = socket;
            InputStream in = socket.getInputStream();
            method = line(in).split(" ")[0];
            String header;
            while (!(header = line(in)).isEmpty()) {
                int colon = header.indexOf(':');
                if (colon < 0) throw new IOException("Invalid test request");
                request.set(header.substring(0, colon), header.substring(colon + 1).trim());
            }
            int length = Integer.parseInt(request.getOrDefault("Content-Length", "0"));
            if (length > 65535) throw new IOException("Oversize test request");
            body = new byte[length]; new DataInputStream(in).readFully(body);
        }
        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int next;
            while ((next = in.read()) != -1) {
                if (next == 10) return bytes.toString("US-ASCII").replace("\r", "");
                if (bytes.size() > 16384) throw new IOException("Test header too large");
                bytes.write(next);
            }
            throw new EOFException();
        }
        String getRequestMethod() { return method; }
        Headers getRequestHeaders() { return request; }
        Headers getResponseHeaders() { return response; }
        InputStream getRequestBody() { return new ByteArrayInputStream(body); }
        void sendResponseHeaders(int status, long length) throws IOException {
            response.set("Connection", "close");
            if (length == 0) response.set("Transfer-Encoding", "chunked");
            else response.set("Content-Length", Long.toString(Math.max(0, length)));
            StringBuilder out = new StringBuilder("HTTP/1.1 " + status + " Test\r\n");
            response.forEach((k, v) -> out.append(k).append(": ").append(v).append("\r\n"));
            socket.getOutputStream().write(out.append("\r\n").toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }
    private static final class TestServer implements Closeable {
        final SSLServerSocket listener;
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        TestServer(SSLContext context, HttpHandler handler, ExecutorService workers) throws IOException {
            listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            workers.submit(() -> {
                while (!listener.isClosed()) {
                    try {
                        SSLSocket socket = (SSLSocket) listener.accept(); sockets.add(socket);
                        workers.submit(() -> {
                            try (socket) { socket.setSoTimeout(5000); handler.handle(new HttpExchange(socket)); }
                            catch (IOException ignored) { /* A rejecting TLS client may close before HTTP. */ }
                            finally { sockets.remove(socket); }
                        });
                    } catch (IOException ignored) { return; }
                }
            });
        }
        InetSocketAddress getAddress() { return (InetSocketAddress) listener.getLocalSocketAddress(); }
        @Override public void close() {
            try { listener.close(); } catch (IOException ignored) { }
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        }
    }

}
