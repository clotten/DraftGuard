package com.draftguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 设置项：保留天数、忽略的 App 列表 */
final class Prefs {

    private static final String FILE = "draftguard";
    private static final String K_RETENTION = "retention_days";
    private static final String K_IGNORED = "ignored_pkgs";
    private static final String K_ENABLED_AT = "enabled_at";
    private static final String K_KEEP_EMPTY = "keep_empty";
    private static final String K_POLLING = "polling";
    private static final String K_DEBUG = "debug_logging";
    private static final String K_IGNORE_DEL = "ignore_deletions";
    private static final String K_SKIP_IME = "skip_ime";
    private static final String K_MIN_CHARS = "min_chars";
    private static final String K_FOCUS_ONLY = "focus_only";

    static final int DEFAULT_RETENTION = 30;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int retentionDays(Context c) {
        return sp(c).getInt(K_RETENTION, DEFAULT_RETENTION);
    }

    static void setRetentionDays(Context c, int days) {
        sp(c).edit().putInt(K_RETENTION, Math.max(1, days)).apply();
    }

    static Set<String> ignored(Context c) {
        Set<String> s = sp(c).getStringSet(K_IGNORED, null);
        return s == null ? Collections.<String>emptySet() : s;
    }

    static boolean isIgnored(Context c, String pkg) {
        return pkg != null && ignored(c).contains(pkg);
    }

    static void setIgnored(Context c, String pkg, boolean on) {
        Set<String> s = new HashSet<>(ignored(c));
        if (on) {
            s.add(pkg);
        } else {
            s.remove(pkg);
        }
        sp(c).edit().putStringSet(K_IGNORED, s).apply();
    }

    static void setServiceEnabledAt(Context c, long ms) {
        sp(c).edit().putLong(K_ENABLED_AT, ms).apply();
    }

    static long serviceEnabledAt(Context c) {
        return sp(c).getLong(K_ENABLED_AT, 0L);
    }

    /** 是否保留"文本被清空"的记录（默认保留，因为清空往往就是崩溃/误操作的现场） */
    static boolean keepEmpty(Context c) {
        return sp(c).getBoolean(K_KEEP_EMPTY, true);
    }

    static void setKeepEmpty(Context c, boolean on) {
        sp(c).edit().putBoolean(K_KEEP_EMPTY, on).apply();
    }

    /** 轮询兜底：有的应用不发文本变化事件，只能定时主动看输入框内容 */
    static boolean polling(Context c) {
        return sp(c).getBoolean(K_POLLING, true);
    }

    static void setPolling(Context c, boolean on) {
        sp(c).edit().putBoolean(K_POLLING, on).apply();
    }

    /** 诊断日志：把收到的原始无障碍事件也记下来，排查"为什么没记到" */
    static boolean debug(Context c) {
        return sp(c).getBoolean(K_DEBUG, false);
    }

    static void setDebug(Context c, boolean on) {
        sp(c).edit().putBoolean(K_DEBUG, on).apply();
    }

    /** 删除操作不记录（默认开）：只保留"新增了文字"的那些版本 */
    static boolean ignoreDeletions(Context c) {
        return sp(c).getBoolean(K_IGNORE_DEL, true);
    }

    static void setIgnoreDeletions(Context c, boolean on) {
        sp(c).edit().putBoolean(K_IGNORE_DEL, on).apply();
    }

    /** 排除输入法键盘自己的事件（默认开） */
    static boolean skipIme(Context c) {
        return sp(c).getBoolean(K_SKIP_IME, true);
    }

    static void setSkipIme(Context c, boolean on) {
        sp(c).edit().putBoolean(K_SKIP_IME, on).apply();
    }

    /** 新建输入框里至少要有几个字符才记录（1 = 不设门槛） */
    static int minChars(Context c) {
        return sp(c).getInt(K_MIN_CHARS, 2);
    }

    static void setMinChars(Context c, int n) {
        sp(c).edit().putInt(K_MIN_CHARS, Math.max(1, n)).apply();
    }

    /**
     * 只记录"当前有输入焦点"的输入框。
     * 默认开启：能滤掉同一屏里其它可编辑控件、界面重绘时补发的旧内容，
     * 以及只显示占位提示的空框。
     */
    static boolean focusOnly(Context c) {
        return sp(c).getBoolean(K_FOCUS_ONLY, true);
    }

    static void setFocusOnly(Context c, boolean on) {
        sp(c).edit().putBoolean(K_FOCUS_ONLY, on).apply();
    }
}
