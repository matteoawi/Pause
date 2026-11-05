package it.matteo.pausa;

import android.content.Context;
import android.content.Intent;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import it.matteo.pausa.core.UpdateSchedule;

/** Application-owned work survives rotation without retaining a screen. */
final class ProtectionUpdates {
    static final AtomicBoolean busy = new AtomicBoolean();
    static volatile int result;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    static void start(Context context) {
        start(context, true);
    }
    static void maybeStart(Context context) {
        Settings settings = new Settings(context);
        if (UpdateSchedule.due(System.currentTimeMillis(), settings.number("protection_v2_updated"), settings.number("protection_v2_attempt")))
            start(context, false);
    }
    private static void start(Context context, boolean manual) {
        if (!busy.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        new Settings(app).putNumber("protection_v2_attempt", System.currentTimeMillis());
        if (manual) result = 0;
        worker.execute(() -> {
            try {
                new BlocklistStore(app).update();
                if (PausaVpnService.status.equals("on") || PausaVpnService.status.equals("starting")) {
                    try { app.startService(new Intent(app, PausaVpnService.class).setAction(PausaVpnService.RELOAD)); }
                    catch (IllegalStateException ignored) { /* next activation reads the new file */ }
                }
                if (manual) result = R.string.update_success;
            } catch (Exception | OutOfMemoryError e) { if (manual) result = R.string.update_failed; }
            finally { busy.set(false); }
        });
    }
    private ProtectionUpdates() { }
}
