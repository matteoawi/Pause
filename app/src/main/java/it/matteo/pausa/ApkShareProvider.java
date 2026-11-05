package it.matteo.pausa;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Shares only this installed APK, never an arbitrary path or private app data. */
public final class ApkShareProvider extends ContentProvider {
    public static Uri uri(Context context) { return Uri.parse("content://" + context.getPackageName() + ".share/apk"); }
    private void validate(Uri uri) throws FileNotFoundException {
        if (!uri(getContext()).equals(uri)) throw new FileNotFoundException("Unknown resource");
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return uri(getContext()).equals(uri) ? "application/vnd.android.package-archive" : null; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        validate(uri); if (!mode.equals("r")) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(new File(getContext().getApplicationInfo().sourceDir), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!uri(getContext()).equals(uri)) throw new IllegalArgumentException("Unknown resource");
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns); Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(OpenableColumns.DISPLAY_NAME)) values[i] = "Pause-" + BuildConfig.VERSION_NAME + ".apk";
            else if (columns[i].equals(OpenableColumns.SIZE)) values[i] = new File(getContext().getApplicationInfo().sourceDir).length();
        }
        cursor.addRow(values); return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
