package com.draftguard;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 导出记录为 zip（<b>内容由数据库现场生成</b>，不再打包文件目录）。
 *
 * JSONL 在这里的角色是**导出格式**，不是存储格式 —— 分析脚本
 * （merge_export.py / analyze.py / 提取整段.py）仍然读 {@code <day>/<app>.jsonl}，
 * 所以这里的 zip 结构与旧版完全一致。
 *
 * 两条出路：
 *   1) 公共下载目录 Download/DraftGuard/ —— 文件管理器能看到，adb 也能拉
 *   2) 应用 cache + 分享面板
 */
final class Exporter {

    private static final String TAG = "DraftGuardExport";
    static final String PUBLIC_SUBDIR = "DraftGuard";

    private Exporter() {
    }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
    }

    /** 导出到公共下载目录。返回写入的 Uri，失败返回 null。 */
    static Uri toPublicDownloads(Context ctx, LogStore store) {
        String name = "DraftGuard-" + stamp() + ".zip";
        return writeToPublic(ctx, store, name,
                Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR);
    }

    /**
     * 清空前把现有记录备份到公共下载目录（Download/DraftGuard/backup/）。
     *
     * 为什么不能放应用私有目录：那里用户取不出来（adb 也拉不到），
     * 备份取不出来就等于没有备份。
     */
    static Uri backupToPublicDownloads(Context ctx, LogStore store, String tag) {
        String name = "backup-" + stamp()
                + (tag == null || tag.isEmpty() ? "" : "-" + tag) + ".zip";
        return writeToPublic(ctx, store, name,
                Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR + "/backup");
    }

    private static Uri writeToPublic(Context ctx, LogStore store, String name, String relPath) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
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
            int entries;
            try {
                entries = store.writeZip(os);
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            }
            Log.i(TAG, "导出成功：" + name + "，条目 " + entries);
            return uri;
        } catch (Throwable t) {
            Log.e(TAG, "导出失败", t);
            return null;
        }
    }

    /** 导出到应用 cache（供分享面板使用），返回文件；失败返回 null */
    static File toCache(Context ctx, LogStore store) {
        String name = "draftguard-export-" + stamp() + ".zip";
        try {
            File out = new File(ctx.getCacheDir(), name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                int entries = store.writeZip(fos);
                Log.i(TAG, "导出到 cache 成功：" + name + "，条目 " + entries);
            }
            return out.length() > 0 ? out : null;
        } catch (Throwable t) {
            Log.e(TAG, "导出到 cache 失败", t);
            return null;
        }
    }
}
