package it.matteo.pausa;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import it.matteo.pausa.core.DomainRules;

/** Disposable emulator integration: no UIAutomation or synthetic device input required. */
public final class ProtectionRunner extends Instrumentation {
    private boolean networkOnly;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); networkOnly = arguments != null && "true".equals(arguments.getString("hold-vpn")); start(); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle result = new Bundle(); StringBuilder log = new StringBuilder(); int passed = 0;
        Context context = getTargetContext();
        try {
            require(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"), "Emulators only; refuses physical phones");
            if (networkOnly) {
                activate(context);
                require(PausaVpnService.status.equals("on"), "VPN did not activate");
                holdForProbe(context);
                result.putString("stream", "\nPASS VPN held for separate-UID network probe\nPASSED 1/1\n"); finish(Activity.RESULT_OK, result); return;
            }
            File files = context.getFilesDir(), cache = new File(files, "hosts-v2");
            require(!cache.exists(), "Run only after pm clear on disposable emulator");
            byte[] base = read(context, "hosts.txt"), expanded = read(context, "expanded-domains.txt");
            Files.write(new File(files, "hosts").toPath(), base); // Legacy v0.3 cache must not suppress expanded coverage.
            Settings settings = new Settings(context); settings.putNumber("queries", 100); settings.putNumber("blocked", 42);
            settings.rule("allow", "safe.example.net", true); settings.rule("deny", "custom.example.net", true);
            DomainRules bundled = new BlocklistStore(context).load();
            require(bundled.size() > 190_000 && bundled.blocks("sst.013.nl"), "Expanded bundle not loaded over legacy cache");
            require(!bundled.blocks("www.youtube.com") && !bundled.blocks("www.instagram.com"), "Whole service blocked");
            passed++; log.append("PASS fresh/upgrade coverage, YouTube and Instagram remain allowed\n");
            BlocklistStore fixed = new BlocklistStore(context, url -> url.equals(BlocklistStore.URL) ? base : expanded);
            int count = fixed.update(); require(count > 190_000 && cache.exists(), "Snapshot not committed");
            byte[] previous = Files.readAllBytes(cache.toPath()); long updated = settings.number("protection_v2_updated");
            require(settings.number("queries") == 100 && settings.number("blocked") == 42, "Counters changed during update");
            require(new BlocklistStore(context).load().blocks("custom.example.net"), "Custom deny lost");
            require(!new BlocklistStore(context).load().blocks("safe.example.net"), "Custom allow lost");
            passed++; log.append("PASS combined atomic snapshot, counters and custom rules retained\n");
            try {
                new BlocklistStore(context, url -> { if (url.equals(BlocklistStore.URL)) return base; throw new IOException("offline second source"); }).update();
                throw new AssertionError("Failed second source accepted");
            } catch (IOException expected) { }
            require(Arrays.equals(previous, Files.readAllBytes(cache.toPath())), "Partial update replaced valid snapshot");
            require(updated == settings.number("protection_v2_updated"), "Failed download advanced timestamp");
            passed++; log.append("PASS second-source failure keeps prior snapshot and timestamp\n");
            byte[] unsafe = (new String(expanded, StandardCharsets.UTF_8) + "\ninstagram.com\n").getBytes(StandardCharsets.UTF_8);
            try {
                new BlocklistStore(context, url -> url.equals(BlocklistStore.URL) ? base : unsafe).update();
                throw new AssertionError("Whole-service rule accepted");
            } catch (IOException expected) { }
            require(Arrays.equals(previous, Files.readAllBytes(cache.toPath())), "Unsafe source replaced snapshot");
            passed++; log.append("PASS unsafe remote list rejected without changing snapshot\n");
            Files.write(cache.toPath(), "<html>bad cache</html>".getBytes(StandardCharsets.UTF_8));
            require(new BlocklistStore(context).load().blocks("sst.013.nl"), "Invalid cache did not fall back to bundle");
            Files.write(cache.toPath(), previous);
            passed++; log.append("PASS corrupt cache recovers bundled protection\n");
            require(VpnService.prepare(context) == null, "Emulator test VPN grant missing");
            settings.putNumber("protection_v2_updated", 0); settings.putNumber("protection_v2_attempt", 0);
            activate(context);
            long deadline = SystemClock.elapsedRealtime() + 100_000;
            while ((!PausaVpnService.status.equals("on") || settings.number("protection_v2_updated") == 0 || ProtectionUpdates.busy.get())
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
            require(PausaVpnService.status.equals("on") && PausaVpnService.ruleCount > 190_000, "VPN did not activate with expanded rules");
            require(settings.number("protection_v2_updated") > 0, "Automatic real HTTPS update failed");
            passed++; log.append("PASS activation starts expanded VPN and completes automatic HTTPS refresh\n");
            holdForProbe(context);
            result.putString("stream", "\n" + log + "PASSED " + passed + "/6\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\n" + log + "FAILED after " + passed + " checks: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void activate(Context context) {
        require(VpnService.prepare(context) == null, "Emulator test VPN grant missing");
        Activity activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        View[] activate = new View[1];
        runOnMainSync(() -> activate[0] = find(activity.getWindow().getDecorView(), activity.getString(R.string.activate)));
        if (activate[0] != null) runOnMainSync(() -> activate[0].performClick());
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        while (!PausaVpnService.status.equals("on") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
        require(PausaVpnService.status.equals("on"), "VPN activation timed out");
    }
    private void holdForProbe(Context context) throws IOException {
        Files.write(new File(context.getFilesDir(), "qa-protection-ready").toPath(), new byte[]{1});
        File completed = new File(context.getFilesDir(), "qa-probe-complete");
        long deadline = SystemClock.elapsedRealtime() + 180_000;
        while (!completed.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
        require(completed.exists(), "Separate-UID probe completion timeout");
    }
    private byte[] read(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n); return out.toByteArray();
        }
    }
    private View find(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = find(((ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
}
