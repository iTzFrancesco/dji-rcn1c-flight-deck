package com.drone.rcn1cbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Espone il solo APK scaricato all'installatore Android tramite content URI. */
public class ApkFileProvider extends ContentProvider {
    private static final String AUTHORITY = "com.drone.rcn1cbridge.apkprovider";
    private static volatile File sharedFile;

    public static void setSharedFile(File file) {
        sharedFile = file;
    }

    public static Uri uriFor(File file) {
        setSharedFile(file);
        return Uri.parse("content://" + AUTHORITY + "/update.apk");
    }

    private File requestedFile(Uri uri) throws FileNotFoundException {
        File file = sharedFile;
        if (file == null || !file.isFile() || !"update.apk".equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException("APK aggiornamento non disponibile");
        }
        return file;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("sola lettura");
        return ParcelFileDescriptor.open(requestedFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = requestedFile(uri);
        } catch (FileNotFoundException error) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
