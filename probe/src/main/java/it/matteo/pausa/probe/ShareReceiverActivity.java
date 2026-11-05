package it.matteo.pausa.probe;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.TextView;
import java.io.*;
import java.security.MessageDigest;

/** Disposable emulator-only receiver; verifies a real cross-UID URI grant. */
public final class ShareReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this); view.setTextSize(18); view.setPadding(24, 40, 24, 24); setContentView(view);
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) throw new IOException("Missing stream");
            String name; long size;
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("Missing metadata");
                name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long actual = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = in.read(bytes)) != -1) { digest.update(bytes, 0, count); actual += count; }
            }
            if (actual != size || actual < 100000) throw new IOException("Truncated APK");
            boolean denied = false;
            try (OutputStream ignored = getContentResolver().openOutputStream(uri, "w")) { }
            catch (SecurityException | FileNotFoundException expected) { denied = true; }
            if (!denied) throw new IOException("Write unexpectedly allowed");
            denied = false;
            try (InputStream ignored = getContentResolver().openInputStream(uri.buildUpon().path("/private").build())) { }
            catch (SecurityException | FileNotFoundException expected) { denied = true; }
            if (!denied) throw new IOException("Other resource unexpectedly allowed");
            StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
            view.setText("SHARE PASS\n" + name + "\n" + actual + " bytes\nSHA256 " + hex + "\nWrite denied; other paths denied");
        } catch (Exception failure) { view.setText("SHARE FAILED\n" + failure); }
    }
}
