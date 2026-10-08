package com.draftguard;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出记录为 zip。
 *
 * 两条出路：
 *   1) 写进**公共下载目录** Download/DraftGuard/ —— 文件管理器能看到，也能被 adb 拉取。
 *      这是 Android 10+ 推荐的做法：走 MediaStore，不需要存储权限。
 *   2) 写进应用 cache 再用分享面板发出去（原来的方式，适合直接发微信/QQ）。
 *
 * 加第 1 条的原因：原来只走分享面板，而面板里没有"保存到文件"这类入口，
 * 结果记录了却拿不到本地文件 —— 实测发现的缺陷。
 */
final class Exporter {

    private static final String TAG = "DraftGuardExport";
    static final String PUBLIC_SUBDIR = "DraftGuard";

    private Exporter() {
    }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
    }

    /** 把 logs 目录打包成 zip 写到 out；返回写入的条目数 */
    private static int writeZip(File logsRoot, OutputStream os) throws Exception {
        int entries = 0;
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os))) {
            File[] days = logsRoot.listFiles();
            if (days != null) {
                java.util.Arrays.sort(days);
                for (File day : days) {
                    if (!day.isDirectory()) {
                        continue;
                    }
                    File[] files = day.listFiles();
                    if (files == null) {
                        continue;
                    }
                    java.util.Arrays.sort(files);
                    for (File f : files) {
                        if (!f.isFile()) {
                            continue;
                        }
                        zos.putNextEntry(new ZipEntry(day.getName() + "/" + f.getName()));
                        try (FileInputStream in = new FileInputStream(f)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                zos.write(buf, 0, n);
                            }
                        }
                        zos.closeEntry();
                        entries++;
                    }
                }
            }
        }
        return entries;
    }

    /**
     * 导出到公共下载目录。返回写入的 Uri，失败返回 null。
     * Android 10+ 用 MediaStore，不需要任何存储权限。
     */
    static Uri toPublicDownloads(Context ctx, File logsRoot) {
        String name = "DraftGuard-" + stamp() + ".zip";
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR);
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                Log.w(TAG, "MediaStore.insert 返回 null");
                return null;
            }
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) {
                Log.w(TAG, "openOutputStream 返回 null");
                return null;
            }
            int entries = writeZip(logsRoot, os);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            }
            Log.i(TAG, "导出到公共下载目录成功：" + name + "，条目 " + entries);
            return uri;
        } catch (Throwable t) {
            Log.e(TAG, "导出到公共下载目录失败", t);
            return null;
        }
    }

    /**
     * 清空前把现有记录备份到**公共下载目录**（Download/DraftGuard/backup/）。
     *
     * 为什么不能放应用私有目录：那里用户取不出来（adb 也拉不到），
     * 备份取不出来就等于没有备份。放公共目录才真正可恢复。
     */
    static Uri backupToPublicDownloads(Context ctx, File logsRoot, String tag) {
        String name = "backup-" + stamp() + (tag == null || tag.isEmpty() ? "" : "-" + tag) + ".zip";
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR + "/backup");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                return null;
            }
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) {
                return null;
            }
            int entries = writeZip(logsRoot, os);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            }
            Log.i(TAG, "备份到公共目录成功：" + name + "，条目 " + entries);
            return uri;
        } catch (Throwable t) {
            Log.e(TAG, "备份失败", t);
            return null;
        }
    }
    /** 导出到应用 cache（供分享面板使用），返回文件；失败返回 null */
    static File toCache(Context ctx, File logsRoot) {
        String name = "typelog-export-" + stamp() + ".zip";
        try {
            File out = new File(ctx.getCacheDir(), name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                int entries = writeZip(logsRoot, fos);
                Log.i(TAG, "导出到 cache 成功：" + name + "，条目 " + entries);
            }
            return out.length() > 0 ? out : null;
        } catch (Throwable t) {
            Log.e(TAG, "导出到 cache 失败", t);
            return null;
        }
    }
}
