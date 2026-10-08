package com.draftguard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简文件 Provider：只为了把导出的 zip 交给别的 App（分享/保存）。
 * 不依赖 androidx，所以自己写一个。只暴露 cache 目录下的文件，越界一律拒绝。
 */
public class LogFileProvider extends ContentProvider {

    static final String AUTHORITY = "com.draftguard.files";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) {
        if (getContext() == null || uri.getLastPathSegment() == null) {
            return null;
        }
        String name = uri.getLastPathSegment();
        if (name.contains("/") || name.contains("..")) {
            return null;
        }
        return new File(getContext().getCacheDir(), name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n != null && n.endsWith(".zip")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
