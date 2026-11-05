package it.matteo.pausa;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.os.*;
import android.system.*;
import it.matteo.pausa.core.*;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class PausaVpnService extends VpnService {
    public static final String START = "it.matteo.pausa.START", STOP = "it.matteo.pausa.STOP";
    public static final String RELOAD = "it.matteo.pausa.RELOAD";
    public static final String REFRESH_UI = "it.matteo.pausa.REFRESH_UI";
    public static volatile String status = "off", error = "";
    public static volatile int ruleCount;
    public static final AtomicLong queries = new AtomicLong(), blocked = new AtomicLong(), failures = new AtomicLong();
    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session session;
    private Settings settings;
    public static synchronized List<String> recent() { return new ArrayList<>(recent); }
    private static synchronized void record(String domain) {
        recent.remove(domain); recent.addFirst(domain);
        while (recent.size() > 30) recent.removeLast();
    }
    @Override public void onCreate() {
        super.onCreate(); settings = new Settings(this);
        queries.set(settings.number("queries")); blocked.set(StatisticsStore.snapshot(this).total);
        createChannel();
    }
    private String uiString(int resource) { return AppPreferences.wrap(this).getString(resource); }
    private void createChannel() {
        NotificationChannel channel = new NotificationChannel("protection", uiString(R.string.protection_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(uiString(R.string.protection_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopProtection(); stopSelf(); return START_NOT_STICKY; }
        if (REFRESH_UI.equals(intent.getAction())) {
            if (session != null) { createChannel(); getSystemService(NotificationManager.class).notify(7, notification()); }
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (RELOAD.equals(intent.getAction())) {
            Session active = session;
            if (active != null) active.reload.request();
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (session != null) return START_NOT_STICKY;
        error = ""; status = "starting";
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(7, notification());
            Session next = new Session(); session = next;
            next.thread = new Thread(() -> runTunnel(next), "pausa-tunnel"); next.thread.start();
            main.post(ticker);
        } catch (RuntimeException e) { fail(uiString(R.string.error_vpn_permission)); }
        return START_NOT_STICKY;
    }
    private void runTunnel(Session current) {
        try {
            DomainRules initialRules = new BlocklistStore(this).load();
            synchronized (current) { if (current.rules == null) current.rules = initialRules; }
            ruleCount = current.rules.size();
            if (session != current || current.closed) return;
            Builder builder = new Builder().setSession(uiString(R.string.vpn_session))
                .setMtu(32767).setBlocking(false)
                .addAddress("198.18.0.1", 32).addDnsServer("198.18.0.2").addRoute("198.18.0.2", 32)
                .addAddress("fd00:7061:7573::1", 128).addDnsServer("fd00:7061:7573::2").addRoute("fd00:7061:7573::2", 128)
                .allowFamily(OsConstants.AF_INET).allowFamily(OsConstants.AF_INET6)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            builder.addDisallowedApplication(getPackageName());
            for (String app : settings.set("excluded")) {
                try { builder.addDisallowedApplication(app); } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            synchronized (current) {
                if (current.closed) return;
                current.tun = builder.establish();
                if (current.tun == null) throw new IOException(uiString(R.string.error_vpn_revoked));
                current.input = new FileInputStream(current.tun.getFileDescriptor());
                current.output = new FileOutputStream(current.tun.getFileDescriptor());
            }
            if (session != current || current.closed) return;
            status = "on";
            StructPollfd poll = new StructPollfd(); poll.fd = current.tun.getFileDescriptor(); poll.events = (short) OsConstants.POLLIN;
            byte[] buffer = new byte[32767];
            while (!current.closed) {
                Os.poll(new StructPollfd[]{poll}, 1000);
                if (current.closed) break;
                if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0)
                    throw new IOException(uiString(R.string.error_vpn_interrupted));
                if ((poll.revents & OsConstants.POLLIN) != 0) {
                    int count = current.input.read(buffer);
                    if (count < 0) throw new IOException(uiString(R.string.error_vpn_interrupted));
                    if (count > 0) {
                        try { route(current, new IpPacket(buffer, count)); } catch (IOException ignored) { /* malformed packet */ }
                    }
                }
                long now = System.nanoTime() / 1_000_000;
                current.tcp.entrySet().removeIf(entry -> !entry.getValue().tick(now));
            }
        } catch (Exception | OutOfMemoryError e) {
            if (!current.closed && session == current) {
                String message = e instanceof IOException ? e.getMessage() : uiString(R.string.error_start);
                main.post(() -> { if (session == current) fail(message); });
            }
        } finally { current.close(); }
    }
    private void route(Session current, IpPacket packet) throws IOException {
        if (packet.destinationPort != 53) return;
        byte[] expected = InetAddress.getByName(packet.ipv6 ? "fd00:7061:7573::2" : "198.18.0.2").getAddress();
        if (!Arrays.equals(expected, packet.destination)) return;
        if (packet.protocol == 17) {
            resolve(current, packet.payload, response -> {
                if (response != null) {
                    // Avoid IP fragmentation; huge answers make the client retry over TCP.
                    if (response.length > 32_000) {
                        try { response = new DnsMessage(packet.payload).error(0); response[2] |= 2; }
                        catch (IOException ignored) { return; }
                    }
                    current.write(packet.udpReply(response));
                }
            }, packet);
        } else {
            String key = packet.flowKey();
            TcpDnsSession flow = current.tcp.get(key);
            if (flow == null && (packet.flags() & 0x17) == 2 && current.tcp.size() < 64) {
                flow = new TcpDnsSession(packet, System.nanoTime() / 1_000_000, current::write,
                    (query, response) -> resolve(current, query, response, packet));
                current.tcp.put(key, flow);
            } else if (flow != null) flow.accept(packet, System.nanoTime() / 1_000_000);
            else if ((packet.flags() & 4) == 0) current.write(packet.tcpReply(new byte[0], packet.acknowledgement(), 0, 4));
        }
    }
    private void resolve(Session current, byte[] data, Consumer<byte[]> output, IpPacket origin) {
        DnsMessage query;
        try { query = new DnsMessage(data); } catch (IOException ignored) { output.accept(null); return; }
        if (current.closed) return;
        queries.incrementAndGet();
        if (current.rules.blocks(query.domain)) {
            String[] app = owner(origin);
            StatisticsStore.record(this, app[0], app[1]);
            blocked.incrementAndGet(); record(query.domain); output.accept(query.error(3)); return;
        }
        try {
            current.work.execute(() -> {
                if (current.closed) return;
                try { output.accept(current.upstream.resolve(query)); }
                catch (IOException e) { if (!current.closed) { failures.incrementAndGet(); output.accept(query.error(2)); } }
            });
        } catch (RejectedExecutionException saturated) { failures.incrementAndGet(); output.accept(query.error(2)); }
    }
    private String[] owner(IpPacket packet) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                int uid = getSystemService(ConnectivityManager.class).getConnectionOwnerUid(packet.protocol,
                    new InetSocketAddress(InetAddress.getByAddress(packet.source), packet.sourcePort),
                    new InetSocketAddress(InetAddress.getByAddress(packet.destination), packet.destinationPort));
                if (uid >= 10000 && uid != android.os.Process.myUid()) {
                    String[] packages = getPackageManager().getPackagesForUid(uid);
                    if (packages != null && packages.length == 1) {
                        String name = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packages[0], 0)).toString();
                        return new String[]{packages[0], name};
                    }
                }
            } catch (Exception ignored) { /* Never guess from foreground activity or host names. */ }
        }
        return new String[]{BlockStatistics.UNKNOWN, ""};
    }
    private final Runnable ticker = new Runnable() {
        int ticks;
        @Override public void run() {
            Session active = session;
            if (active == null || active.closed) return;
            active.reload.tick();
            if (++ticks % 30 == 1) ProtectionUpdates.maybeStart(PausaVpnService.this);
            if (ticks % 5 == 0) {
                StatisticsStore.save(PausaVpnService.this, queries.get());
                getSystemService(NotificationManager.class).notify(7, notification());
            }
            main.postDelayed(this, 2000);
        }
    };
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PausaVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "protection").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(uiString(status.equals("starting") ? R.string.notification_starting : R.string.notification_active))
            .setContentText(uiString(R.string.notification_body))
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null, uiString(R.string.pause), stop).build()).build();
    }
    private void fail(String message) { error = message; stopProtection(); status = "error"; stopSelf(); }
    private void stopProtection() {
        Session old = session; session = null;
        if (old != null) old.close();
        main.removeCallbacks(ticker);
        if (settings != null) StatisticsStore.save(this, queries.get());
        if (!status.equals("error")) status = "off";
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onRevoke() { stopProtection(); stopSelf(); super.onRevoke(); }
    @Override public void onDestroy() { stopProtection(); super.onDestroy(); }
    private final class Session implements Closeable {
        volatile boolean closed;
        volatile DomainRules rules;
        ParcelFileDescriptor tun;
        FileInputStream input;
        FileOutputStream output;
        Thread thread;
        final DnsUpstream upstream = new DnsUpstream(PausaVpnService.this);
        final Map<String, TcpDnsSession> tcp = new ConcurrentHashMap<>();
        final ThreadPoolExecutor work = new ThreadPoolExecutor(4, 4, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        final RuleReloadQueue<DomainRules> reload = new RuleReloadQueue<>(
            () -> new BlocklistStore(PausaVpnService.this).load(),
            fresh -> { synchronized (this) { if (!closed && session == this) { rules = fresh; ruleCount = fresh.size(); } } },
            problem -> { if (!closed && session == this && !(problem instanceof OutOfMemoryError)) error = uiString(R.string.error_reload); });
        synchronized void write(byte[] packet) {
            if (closed || output == null) return;
            try { output.write(packet); } catch (IOException e) {
                if (!closed) main.post(() -> { if (session == this) fail(uiString(R.string.error_vpn_interrupted)); });
            }
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true; reload.close(); upstream.close(); work.shutdownNow(); tcp.clear();
            // Close the owning descriptor once. Streams share it and must not double-close a reused fd.
            if (tun != null) try { tun.close(); } catch (IOException ignored) { }
        }
    }
}
