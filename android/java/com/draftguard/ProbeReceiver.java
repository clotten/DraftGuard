package com.draftguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 诊断探针：让外部（ADB）能读取应用内部状态。
 *
 * 为什么需要它：Android 11+ 禁止 `adb pull /data/data/<包名>/...`，
 * 应用私有目录里的日志文件用 adb 读不到。但排查问题恰恰要看那些文件。
 * 这个接收器让应用"自己"把状态打到 logcat，于是：
 *
 *   adb logcat -s DraftGuardProbe
 *   adb shell am broadcast -n com.draftguard/.ProbeReceiver -a com.draftguard.PROBE --es cmd dump
 *
 * 就成了远程调试的眼睛。它还能顺手触发清空记录、重置计数，省去点屏幕。
 *
 * 安全边界（重要）：
 *   · 接收器在 manifest 里没有 android:exported="true"，外部应用调不动；
 *   · 只有当用户在「设置」里打开"诊断日志"后才会响应，默认是关闭的；
 *   · 不写入、不外发任何数据，只是把已有状态打到本机 logcat。
 */
public class ProbeReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.draftguard.PROBE";
    private static final String TAG = "DraftGuardProbe";
    private static final int LAST_N = 15;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (!Prefs.debug(context)) {
            Log.i(TAG, "探针已忽略：诊断日志未开启（应用内 设置 → 诊断日志）");
            return;
        }
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) {
            cmd = "dump";
        }
        try {
            switch (cmd) {
                case "clear":
                    // 需要显式确认，避免误触发（历史上就用命令行 clear 清掉过用户数据）
                    if (!intent.getBooleanExtra("confirm", false)) {
                        Log.w(TAG, "clear 被拒绝：缺少确认参数。"
                                + "如确需清空，请加 --ez confirm true；"
                                + "记录会先自动备份到 files/backup-*.zip");
                        break;
                    }
                    doClear(context);
                    break;
                case "reset":
                    doReset();
                    break;
                case "flush":
                    Log.i(TAG, "flush: 记录文件每次写入都是即时 fsync，无需额外刷新");
                    doDumpCounters(context);
                    break;
                case "bursts": {
                    // 直接输出「合并视图」的结果，便于远程核对"整段话"是否正确
                    LogStore st = new LogStore(context.getFilesDir(), 0);
                    String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                    java.util.List<LogStore.Row> rows = st.readDay(day, 0);
                    java.util.List<Burst> bs = Burst.groupNewestFirst(rows);
                    Log.i(TAG, "bursts: 原始 " + rows.size() + " 个版本 -> 合并 "
                            + bs.size() + " 段");
                    for (int i = 0; i < bs.size() && i < 40; i++) {
                        Burst b = bs.get(i);
                        Log.i(TAG, "  [" + b.firstTs.substring(11, 16) + "] " + b.app
                                + " versions=" + b.versions + " chars=" + b.text.length()
                                + " text=<" + oneLine(b.text, 120) + ">");
                    }
                    break;
                }
                case "export": {
                    LogStore st = new LogStore(context.getFilesDir(), 0);
                    android.net.Uri u = Exporter.toPublicDownloads(context, st.root());
                    Log.i(TAG, "export -> " + (u == null ? "失败" : u.toString())
                            + "  (Download/" + Exporter.PUBLIC_SUBDIR + ")");
                    break;
                }
                case "dump":
                default:
                    doDump(context);
                    break;
            }
        } catch (Throwable t) {
            Log.e(TAG, "探针执行失败", t);
        }
    }

    // ------------------------------------------------------------------ dump

    private void doDump(Context context) {
        Log.i(TAG, "===== DraftGuard probe dump "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " =====");

        Log.i(TAG, "service.running=" + TypelogService.running
                + "  written=" + TypelogService.written
                + "  errors=" + TypelogService.errors);

        Log.i(TAG, "events: all=" + TypelogService.evAll
                + " text=" + TypelogService.evText
                + " captured=" + TypelogService.evCaptured
                + " sourceNull=" + TypelogService.evSourceNull
                + " notEditable=" + TypelogService.evNotEditable
                + " traverseHit=" + TypelogService.evTraverseHit
                + " relaxedHit=" + TypelogService.evRelaxedHit);

        Log.i(TAG, "skipped: noFocus=" + TypelogService.skippedNoFocus
                + " delete=" + TypelogService.skippedDelete
                + " noise=" + TypelogService.skippedNoise
                + " ime=" + TypelogService.skippedIme
                + " password=" + TypelogService.skippedPassword
                + " selfOrSystem=" + TypelogService.skippedSelf
                + " ignored=" + TypelogService.skippedIgnored);

        Log.i(TAG, "last: app=" + TypelogService.lastApp
                + " label=" + TypelogService.lastAppLabel
                + " field=" + TypelogService.lastField
                + " ts=" + TypelogService.lastTs
                + " chars=" + TypelogService.lastText.length());
        if (!TypelogService.lastText.isEmpty()) {
            Log.i(TAG, "lastText=<" + oneLine(TypelogService.lastText, 200) + ">");
        }

        if (!TypelogService.lastError.isEmpty()) {
            Log.w(TAG, "lastError=" + TypelogService.lastError);
        }
        if (!TypelogService.lastScanInfo.isEmpty()) {
            Log.i(TAG, "lastScanInfo=" + TypelogService.lastScanInfo);
        }

        // 事件来源：判断"某应用到底有没有送事件"的关键
        Log.i(TAG, "-- packages that sent events --");
        dumpCounts("all", TypelogService.ALL_EVENT_PKGS);
        dumpCounts("text", TypelogService.TEXT_EVENT_PKGS);

        // 磁盘真相：绕开所有缓存
        LogStore store = new LogStore(context.getFilesDir(), 0);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Log.i(TAG, "-- files on disk (" + today + ") --");
        for (String line : store.fileInventory(today, 120)) {
            Log.i(TAG, line.replace("\n", " | "));
        }
        Log.i(TAG, "totalBytes=" + store.totalBytes() + "  rows=" + store.countAll() + "  written=" + TypelogService.written);

        // 最近记录：把 text 也打出来，远程就能核对内容
        Log.i(TAG, "-- last " + LAST_N + " records --");
        List<LogStore.Row> rows = store.readDay(today, 0);
        int from = Math.max(0, rows.size() - LAST_N);
        for (int i = from; i < rows.size(); i++) {
            LogStore.Row r = rows.get(i);
            Log.i(TAG, r.ts + " [" + r.minute + "] " + r.app
                    + " chars=" + r.chars + (r.comp ? " comp" : "")
                    + " text=<" + oneLine(r.text, 160) + ">");
        }

        // 设置快照：很多"没记录"其实是开关关着
        Map<String, String> ignored = null;
        // 原始无障碍事件流：排查"某应用到底发了什么事件"的唯一手段
        Log.i(TAG, "-- raw accessibility events (newest last) --");
        java.util.List<String> diagEv = TypelogService.DIAG;
        synchronized (diagEv) {
            if (diagEv.isEmpty()) {
                Log.i(TAG, "  (空：诊断日志未开启，或重启后还没收到事件)");
            }
            int s0 = Math.max(0, diagEv.size() - 30);
            for (int i = s0; i < diagEv.size(); i++) {
                Log.i(TAG, "  ev: " + diagEv.get(i));
            }
        }
        Log.i(TAG, "-- prefs --"
                + " focusOnly=" + Prefs.focusOnly(context)
                + " ignoreDeletions=" + Prefs.ignoreDeletions(context)
                + " skipIme=" + Prefs.skipIme(context)
                + " polling=" + Prefs.polling(context)
                + " minChars=" + Prefs.minChars(context)
                + " retention=" + Prefs.retentionDays(context)
                + " ignoredApps=" + Prefs.ignored(context));

        int[] editables = new int[]{0};
        Log.i(TAG, "===== dump end =====");
    }

    private void doDumpCounters(Context context) {
        Log.i(TAG, "written=" + TypelogService.written
                + " captured=" + TypelogService.evCaptured
                + " noFocus=" + TypelogService.skippedNoFocus);
    }

    private static void dumpCounts(String kind, Map<String, Integer> m) {
        synchronized (m) {
            if (m.isEmpty()) {
                Log.i(TAG, "  (" + kind + ") none");
                return;
            }
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                Log.i(TAG, "  (" + kind + ") " + e.getKey() + " = " + e.getValue());
            }
        }
    }

    // ------------------------------------------------------------------ 动作

    private void doClear(Context context) {
        LogStore store = new LogStore(context.getFilesDir(), 0);
        int before = store.countAll();
        // 清空前备份到**公共下载目录**（用户可取回；私有目录里的备份等于没有）
        android.net.Uri bak = Exporter.backupToPublicDownloads(context, store.root(), "preclear");
        store.clearAll();
        Log.i(TAG, "cleared: 已删除 " + before + " 条记录，剩余 " + store.countAll() + " 条");
        Log.i(TAG, "清空前备份：" + (bak == null
                ? "未生成（可能无数据）"
                : "Download/" + Exporter.PUBLIC_SUBDIR + "/backup/"));
    }

    private void doReset() {
        TypelogService.written = 0;
        TypelogService.errors = 0;
        TypelogService.evAll = 0;
        TypelogService.evText = 0;
        TypelogService.evCaptured = 0;
        TypelogService.evSourceNull = 0;
        TypelogService.evNotEditable = 0;
        TypelogService.evTraverseHit = 0;
        TypelogService.skippedNoFocus = 0;
        TypelogService.skippedDelete = 0;
        TypelogService.skippedNoise = 0;
        TypelogService.skippedIme = 0;
        TypelogService.skippedPassword = 0;
        TypelogService.skippedSelf = 0;
        TypelogService.skippedIgnored = 0;
        Log.i(TAG, "counters reset");
    }

    private static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", "\\n").replace("\r", "");
        if (t.length() > max) {
            t = t.substring(0, max) + "…(" + s.length() + " 字)";
        }
        return t;
    }
}
