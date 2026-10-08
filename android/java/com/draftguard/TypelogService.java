package com.draftguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 采集主体。
 *
 * 事件流极密（每敲一个键至少一个 TEXT_CHANGED，输入法联想时更多），所以：
 *   - 只处理可编辑文本类控件，其余一律返回，避免无谓开销；
 *   - 用“控件 + 输入框”做键，350ms 去抖，但去抖期间的新文本会覆盖旧文本，最终一定写最新的一版；
 *   - 真正落盘在独立 HandlerThread 上，主线程只做极轻的判断，不卡输入。
 *
 * 隐私边界（重要）：
 *   - 密码框：系统给的节点会带 isPassword() 标记，本服务遇到就直接跳过，连内存都不进；
 *   - 本应用自己、系统 UI、输入法自身：跳过，避免把候选词、通知文字混进来。
 */
public class TypelogService extends AccessibilityService {

    private static final String TAG = "TypelogService";
    static final String EXTRA_EVENT = "com.draftguard.STATS";

    private static final long DEBOUNCE_MS = 350;
    /** 轮询间隔：只在事件路径失灵时才起作用，900ms 兼顾不漏字与省电 */
    private static final long POLL_MS = 900;
    /** 超过这个长度就不缓存完整文本做比对，避免超长粘贴把内存顶爆 */
    private static final int CACHE_LIMIT = 200_000;
    /** 输入框文本超过这个长度才允许被截断展示（存储仍然尽量存全量） */
    private static final int MAX_STORE_CHARS = 2_000_000;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat MIN =
            new SimpleDateFormat("HH:mm", Locale.US);

    private static volatile TypelogService instance;

    /** 对外暴露的统计，供界面显示 */
    public static volatile boolean running;
    public static volatile String lastApp = "";
    public static volatile String lastAppLabel = "";
    public static volatile String lastField = "";
    public static volatile String lastText = "";
    public static volatile String lastTs = "";
    public static volatile long written;
    public static volatile long skippedPassword;
    public static volatile long skippedOther;
    public static volatile long errors;

    // ---- 诊断计数：用来一眼看出"断在哪一环" ----
    public static volatile long evAll;          // 收到的所有无障碍事件
    public static volatile long evText;         // 其中：文本变化事件
    public static volatile long evSourceNull;   // 文本变化事件里 source 为空
    public static volatile long evNotEditable;  // source 不是可编辑控件
    public static volatile long evTraverseHit;  // 靠遍历活动窗口找回输入框的次数
    public static volatile long evCaptured;     // 真正取到文本并进入记录流程的次数
    public static volatile long skippedSelf;    // 跳过：本应用自己 / 系统 UI
    public static volatile long skippedIgnored; // 跳过：用户在设置里排除的 App
    public static volatile long skippedIme;     // 跳过：输入法键盘自己的事件
    public static volatile long skippedDelete;  // 跳过：删除操作（按设置）
    public static volatile String lastError = "";   // 最近一次写入错误，界面直接显示
    public static volatile long skippedNoFocus;     // 跳过：不是当前有焦点的输入框
    public static volatile long skippedNoise;       // 跳过：占位提示文字 / 单字符碎片
    public static volatile String lastSourcePkg = "";

    /** 哪些应用真的给本服务发过事件 —— 排查"收不到某应用事件"最有用 */
    public static final java.util.Map<String, Integer> TEXT_EVENT_PKGS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Integer>());
    public static final java.util.Map<String, Integer> ALL_EVENT_PKGS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Integer>());

    private static final String[] DIAG_KEYS = {
            "evAll", "evText", "evSourceNull", "evNotEditable", "evTraverseHit",
            "evCaptured", "written", "skippedPassword", "skippedOther", "errors"};

    /** 最近一次"活动窗口里的可疑控件"快照，找不到输入框时给用户看 */
    public static volatile String lastScanInfo = "";

    private HandlerThread worker;
    private Handler handler;
    private LogStore store;
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, String> labelCache = new LinkedHashMap<>();

    private static final class Pending {
        String text = "";
        String lastWriteText;    // 已落盘的那一版
        String lastWriteMinute;  // 已落盘那一版所属的分钟
        String minute = "";
        int chars;
        boolean comp;
        Runnable runnable;
    }

    public static TypelogService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        running = true;
        worker = new HandlerThread("draftguard-io");
        worker.start();
        handler = new Handler(worker.getLooper());
        store = new LogStore(getFilesDir(), Prefs.retentionDays(this));
        Log.i(TAG, "采集服务已连接，数据目录：" + store.root().getAbsolutePath());
        Prefs.setServiceEnabledAt(this, System.currentTimeMillis());
        startPolling();
        sendStats();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        evAll++;
        counted(ALL_EVENT_PKGS, event.getPackageName());
        if (Prefs.debug(this)) {
            String d = event.getEventType() + " " + event.getPackageName() + " "
                    + event.getClassName() + " " + event.getText();
            synchronized (DIAG) {
                if (DIAG.size() > 60) {
                    DIAG.remove(0);
                }
                DIAG.add(d);
            }
        }
        final int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null) {
                lastApp = pkg.toString();
                lastAppLabel = label(pkg.toString());
            }
            sendStatsThrottled();
            return;
        }
        if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            return;
        }
        evText++;
        counted(TEXT_EVENT_PKGS, event.getPackageName());

        try {
            AccessibilityNodeInfo node = null;
            try {
                node = event.getSource();
            } catch (Throwable ignored) {
            }
            final String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
            lastSourcePkg = pkg;

            if (LogStore.isSkippedPackage(pkg)) {
                skippedSelf++;
                sendStatsThrottled();
                return;
            }
            // 输入法键盘自己的事件（候选栏、按键回显）不是"你输入框里的字"，排除
            if (Prefs.skipIme(this) && ImeFilter.isIme(this, pkg)) {
                skippedIme++;
                sendStatsThrottled();
                return;
            }
            if (Prefs.isIgnored(this, pkg)) {
                skippedIgnored++;
                sendStatsThrottled();
                return;
            }
            if (node != null && node.isEditable() && node.isPassword()) {
                // 密码框：系统只给"有内容"这个事实，不给文本。不读、不存、不进内存。
                skippedPassword++;
                recordSkip("password", pkg);
                sendStatsThrottled();
                return;
            }
            if (node == null) {
                evSourceNull++;
            } else if (!node.isEditable()) {
                evNotEditable++;
            }

            // 正常情况下直接用事件附带的节点；拿不到或不是输入框时，遍历活动窗口再找一次。
            // 安卓 11+ 上 getSource() 返回 null 很常见，这条兜底是"记不到字"的主要修复。
            AccessibilityNodeInfo target = pickEditableEvent(node, pkg);
            if (target == null) {
                // 三路都拿不到：把当时窗口里有什么记下来，供诊断
                lastScanInfo = scanInfo(pkg);
                sendStatsThrottled();
                return;
            }
            if (target != node) {
                evTraverseHit++;
            }
            String text = readText(target, event);
            if (text == null) {
                text = "";
            }
            evCaptured++;
            handleText(pkg, target, text);
        } catch (Throwable t) {
            errors++;
            lastTs = TS.format(new Date());
            Log.w(TAG, "事件处理异常", t);
        }
    }

    /**
     * 事件里的节点可用就用它；否则退回活动窗口，找带输入焦点、且可编辑的控件。
     * 再退一步：整个窗口树里第一个可编辑控件（有些 App 焦点信息不规范）。
     */
    private AccessibilityNodeInfo pickEditableEvent(AccessibilityNodeInfo fromEvent, String pkg) {
        if (fromEvent != null && !LogStore.isSkippedPackage(pkg)
                && !Prefs.isIgnored(this, pkg) && fromEvent.isEditable()) {
            return fromEvent;
        }
        if (LogStore.isSkippedPackage(pkg) || Prefs.isIgnored(this, pkg)) {
            return null;
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return null;
            }
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable() && !focused.isPassword()) {
                return focused;
            }
            AccessibilityNodeInfo deep = findEditable(root, 0);
            if (deep != null && !deep.isPassword()) {
                return deep;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) {
            return null;
        }
        try {
            if (node.isEditable() && node.isVisibleToUser()) {
                return node;
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c == null) {
                    continue;
                }
                AccessibilityNodeInfo hit = findEditable(c, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 找不到输入框时，描述一下当时窗口里到底有什么 */
    private String scanInfo(String pkg) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("包名 ").append(pkg).append("；");
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                sb.append("活动窗口拿不到（可能是输入法窗口或系统限制）");
                return sb.toString();
            }
            sb.append("根节点 ").append(root.getClassName()).append("；");
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            sb.append("输入焦点=").append(f == null ? "无"
                    : f.getClassName() + (f.isEditable() ? "(可编辑)" : "(不可编辑)"));
            int[] editables = new int[]{0};
            int[] nodes = new int[]{0};
            countTree(root, 0, nodes, editables);
            sb.append("；窗口内控件 ").append(nodes[0])
              .append(" 个，其中可编辑 ").append(editables[0]).append(" 个");
        } catch (Throwable t) {
            sb.append("扫描异常：").append(t);
        }
        return sb.toString();
    }

    private void countTree(AccessibilityNodeInfo node, int depth, int[] nodes, int[] editables) {
        if (node == null || depth > 12) {
            return;
        }
        nodes[0]++;
        try {
            if (node.isEditable()) {
                editables[0]++;
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                countTree(node.getChild(i), depth + 1, nodes, editables);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 拿到一份文本后的记录流程（事件路径与轮询路径共用）。
     * 这里做去抖：连打时只保留最后一次，350ms 后落盘的是最新那一版，不会丢字。
     */
    private void handleText(String pkg, AccessibilityNodeInfo focusedNode, String text) {
        if (LogStore.isSkippedPackage(pkg) || Prefs.isIgnored(this, pkg)) {
            skippedOther++;
            return;
        }
        if (text == null) {
            return;
        }
        if (text.isEmpty() && !Prefs.keepEmpty(this)) {
            return;
        }
        // 只记"正在输入的框"。放在这里而不是更前面，是为了让它统计到的
        // 全是"确实有文本、但那个框没有焦点"的情况 —— 这正是需要排查的信号。
        // （参数名曾经和下面的局部变量重名，导致误用了事件节点，已改名避免再踩。）
        if (Prefs.focusOnly(this) && !isActiveInput(focusedNode)) {
            skippedNoFocus++;
            return;
        }
        final boolean comp = isComposing(focusedNode, text);
        final String field = fieldKey(focusedNode, pkg);

        // 一个字节都读不到时，宁愿这一版漏掉，也不能把已有内容覆盖成空
        if (text.isEmpty()) {
            synchronized (LOCK) {
                Pending exist = pending.get(field);
                if (exist == null || exist.chars == 0) {
                    return;
                }
            }
        }
        final String app = pkg;
        final String day = DAY.format(new Date());
        final String minute = MIN.format(new Date());
        final long now = System.currentTimeMillis();
        final String appLabel = label(app);

        Pending p;
        synchronized (LOCK) {
            p = pending.get(field);
            if (p == null) {
                p = new Pending();
                pending.put(field, p);
            }
            // 用户要求：删除操作不记录。倒着加的输入法（如某些九宫格）会先出现占位字符，
            // 所以同长度但内容不同时按"新增"处理，只有真的变短才算删除。
            int added = 0;
            if (p.chars > 0 && text.length() > 0) {
                int common = 0;
                int min = Math.min(text.length(), p.text.length());
                while (common < min && text.charAt(common) == p.text.charAt(common)) {
                    common++;
                }
                if (text.length() < p.text.length()) {
                    added = 0;
                } else if (text.length() == p.text.length()) {
                    added = text.equals(p.text) ? 0 : 1;
                } else if (text.length() - p.text.length() >= 2 && common == 0) {
                    added = text.length();
                } else {
                    added = text.length() - p.text.length();
                }
            } else if (text.length() > 0) {
                added = text.length();
            }
            if (Prefs.ignoreDeletions(this) && added <= 0) {
                skippedDelete++;
                // 仍然记住当前文本，但这一版不落盘
                p.text = text;
                p.chars = text.length();
                p.comp = comp;
                p.minute = minute;
                return;
            }
            if (added <= 0 && text.trim().isEmpty()) {
            // 占位提示文字不是用户输入（部分系统把 hint 当作文本回传）
            if (isPlaceholder(text)) {
                skippedNoise++;
                return;
            }
            // 新建的输入框里只有一个字符：多半是打了一个字又立刻删掉的碎片，不占记录
            if (Prefs.minChars(this) > 1 && p.chars == 0 && text.trim().length() < 2) {
                skippedNoise++;
                return;
            }
                return;   // 全是空白，不值得占一条记录
            }
            if (text.length() > CACHE_LIMIT) {
                // 超大文本（罕见）：不去抖，直接写，免得内存里挂着几十兆
                commit(app, appLabel, field, day, minute, text, comp, 0, now);
                return;
            }
            int delta = text.length() - p.chars;
            p.text = text;
            p.chars = text.length();
            p.comp = comp;
            p.minute = minute;

            if (p.runnable != null) {
                handler.removeCallbacks(p.runnable);
            }
            final int deltaF = delta;
            p.runnable = new Runnable() {
                @Override
                public void run() {
                    commit(app, appLabel, field, day, minute, text, comp, deltaF,
                            System.currentTimeMillis());
                }
            };
            handler.postDelayed(p.runnable, DEBOUNCE_MS);

            // 立刻刷新界面可见状态：让用户看到"确实在被记录"
            lastApp = app;
            lastAppLabel = appLabel;
            lastField = field;
            lastText = text;
            lastTs = TS.format(new Date());
        }
        sendStatsThrottled();
    }

    private long lastUiAt;
    /** 诊断环形缓冲：把原始事件记下来，导出日志时能看到"系统到底给了什么" */
    static final java.util.List<String> DIAG =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    private boolean pollStarted;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            try {
                pollActiveWindow();
            } catch (Throwable t) {
                errors++;
            }
            if (handler != null) {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    /**
     * 轮询兜底：有些应用（含微信的部分版本）不发或很少发文本变化事件，
     * 这时只能主动去看"当前活动窗口里的输入框现在是什么内容"。
     * 因为记录逻辑本身按内容去重，轮询不会产生重复记录，只是保证不漏。
     */
    private void pollActiveWindow() {
        if (!Prefs.polling(this)) {
            return;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        if (root == null) {
            return;
        }
        String pkg = "";
        try {
            CharSequence p = root.getPackageName();
            pkg = p == null ? "" : p.toString();
        } catch (Throwable ignored) {
        }
        if (LogStore.isSkippedPackage(pkg) || Prefs.isIgnored(this, pkg)) {
            return;
        }
        AccessibilityNodeInfo target = null;
        try {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable() && !f.isPassword()) {
                target = f;
            }
        } catch (Throwable ignored) {
        }
        if (target == null) {
            target = findEditable(root, 0);
            if (target != null && target.isPassword()) {
                return;   // 轮询里遇到密码框：直接跳过
            }
        }
        if (target == null) {
            return;
        }
        String text = readText(target, null);
        if (text == null) {
            return;
        }
        lastApp = pkg;
        lastAppLabel = label(pkg);
        handleText(pkg, target, text);
    }

    private void startPolling() {
        if (pollStarted || handler == null) {
            return;
        }
        pollStarted = true;
        handler.postDelayed(poller, POLL_MS);
    }

    private void sendStatsThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastUiAt > 300) {
            lastUiAt = now;
            sendStats();
        }
    }

    /** 常见占位提示语，识别为噪音（不记录） */
    private static boolean isPlaceholder(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        return t.startsWith("搜索记录过的文字") || t.startsWith("在这里打字")
                || t.equals("请输入") || t.equals("说点什么") || t.equals("搜索")
                || t.equals("输入内容") || t.startsWith("搜索…") || t.startsWith("输入…");
    }
    /**
     * 是否只记录"当前有输入焦点"的输入框。
     *
     * 为什么需要它：无障碍会为同一屏里**所有**可编辑控件发事件，也常在界面重绘时
     * 补发一次文本变化。结果是后台框、隐藏旧框、只显示占位提示的框都被当成
     * "你在打字"记下来 —— 日志里重复的 "搜索记录过的文字…" 就是这么来的。
     *
     * 判断顺序（Android 上 isFocused() 偶尔不准，所以留一条兜底）：
     *   1) node.isFocused() 为真
     *   2) 否则：它是否就是活动窗口的 FOCUS_INPUT 节点
     */
    private boolean isActiveInput(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            if (node.isFocused()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) {
                return false;
            }
            return focused.equals(node);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void counted(java.util.Map<String, Integer> m, CharSequence pkg) {
        if (pkg == null) {
            return;
        }
        String k = pkg.toString();
        synchronized (m) {
            Integer v = m.get(k);
            m.put(k, v == null ? 1 : v + 1);
            if (m.size() > 80) {
                m.clear();
            }
        }
    }

    private String lastSkip;

    private void recordSkip(String kind, String pkg) {
        String key = kind + "|" + pkg;
        if (!key.equals(lastSkip)) {
            lastSkip = key;
            lastTs = TS.format(new Date());
            sendStats();
        }
    }

    private void commit(String app, String appLabel, String field, String day, String minute,
                        String text, boolean comp, int delta, long nowMs) {
        synchronized (LOCK) {
            Pending p = pending.get(field);
            if (p == null) {
                return;
            }
            // 同一分钟内这一版已经写过了，就不再重复落盘
            if (text.equals(p.lastWriteText) && minute.equals(p.lastWriteMinute)) {
                return;
            }
            p.lastWriteText = text;
            p.lastWriteMinute = minute;
            p.minute = minute;
        }
        Record r = new Record();
        Date d = new Date(nowMs);
        r.ts = TS.format(d);
        r.ms = nowMs;
        r.day = day;
        r.minute = minute;
        r.app = app;
        r.appLabel = appLabel;
        r.field = field;
        r.text = text.length() > MAX_STORE_CHARS ? text.substring(0, MAX_STORE_CHARS) : text;
        r.delta = delta;
        r.comp = comp;
        try {
            store.append(r);
            store.putAppLabel(day, app, appLabel);
            written++;
            lastTs = r.ts;
            lastError = store.lastError == null ? "" : store.lastError;
        } catch (Throwable t) {
            errors++;
            Log.w(TAG, "写入失败", t);
        }
    }

    @Override
    public void onInterrupt() {
        // 用户临时关闭无障碍（例如系统弹窗要求），不做处理
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        instance = null;
        if (store != null) {
            store.closeAll();
        }
        if (worker != null) {
            worker.quitSafely();
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (store != null) {
            store.closeAll();
        }
        if (worker != null) {
            worker.quitSafely();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 辅助

    private static String readText(AccessibilityNodeInfo node, AccessibilityEvent event) {
        CharSequence t = null;
        if (node != null) {
            try {
                // 输入框为空时，部分系统的无障碍会把"占位提示文字"当作文本返回。
                // 那时 getText() 为空、而 hint 非空，据此排除，免得把提示语当成用户输入。
                if (TextUtils.isEmpty(node.getText())) {
                    CharSequence hint = null;
                    try {
                        hint = node.getHintText();
                    } catch (Throwable ignored) {
                    }
                    if (!TextUtils.isEmpty(hint)) {
                        return "";
                    }
                }
                t = node.getText();
            } catch (Throwable ignored) {
            }
            if (TextUtils.isEmpty(t)) {
                // 有些输入框的文本挂在子节点上
                t = deepText(node, 0);
            }
        }
        if (TextUtils.isEmpty(t) && event != null) {
            try {
                if (event.getText() != null && !event.getText().isEmpty()) {
                    t = event.getText().get(0);
                }
            } catch (Throwable ignored) {
            }
        }
        // 注意：读到空串要区分两种情况 ——
        //   "输入框真的被清空了"（返回 ""，是有效事件）
        //   "我们压根读不到文本"（返回 null，不能当成清空）
        return t == null ? null : t.toString();
    }

    /** 控件自己的 getText() 为空时，往下找一层子节点的文本（部分 App 这样组织输入框） */
    private static CharSequence deepText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) {
            return null;
        }
        try {
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c == null) {
                    continue;
                }
                CharSequence t = c.getText();
                if (!TextUtils.isEmpty(t)) {
                    return t;
                }
                CharSequence d = deepText(c, depth + 1);
                if (!TextUtils.isEmpty(d)) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 是否处于“还没上屏”的状态。
     * 部分输入法在上屏前会把选区/光标停在 0，用它做一个近似判断；
     * 拿不到就当作已上屏，正常记录。
     */
    private static boolean isComposing(AccessibilityNodeInfo node, String text) {
        if (text.isEmpty()) {
            return false;
        }
        try {
            int sel = node.getTextSelectionStart();
            return sel == 0 && node.getTextSelectionEnd() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String fieldKey(AccessibilityNodeInfo node, String pkg) {
        try {
            CharSequence id = node.getViewIdResourceName();
            if (id != null && id.length() > 0) {
                return pkg + "#" + id;
            }
        } catch (Throwable ignored) {
        }
        return pkg + "#@" + Integer.toHexString(System.identityHashCode(node));
    }

    String label(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "";
        }
        synchronized (labelCache) {
            String cached = labelCache.get(pkg);
            if (cached != null) {
                return cached;
            }
        }
        String name = pkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) {
                name = l.toString();
            }
        } catch (Throwable ignored) {
        }
        synchronized (labelCache) {
            if (labelCache.size() > 200) {
                labelCache.clear();
            }
            labelCache.put(pkg, name);
        }
        return name;
    }

    private void sendStats() {
        try {
            Intent i = new Intent(EXTRA_EVENT);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** 供界面统计：今天各 App 的记录条数 */
    static Map<String, Integer> todayCounts(Context ctx) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            LogStore s = new LogStore(ctx.getFilesDir(), 0);
            String today = DAY.format(new Date());
            for (LogStore.Row r : s.readDay(today, 0)) {
                Integer c = out.get(r.app);
                out.put(r.app, c == null ? 1 : c + 1);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
