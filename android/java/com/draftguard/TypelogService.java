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
    public static volatile long evRelaxedHit;   // 节点报告"不可编辑"但被放宽判定接受（微信就靠这个）
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
    /**
     * 每个输入框首次出现的文本。
     *
     * 用途：输入框的占位提示文字有两种给法 —— getHintText() 单独给、或直接当 getText() 返回。
     * 后者无法与"用户真的输入了这句话"区分，但**它一定是该输入框的首条文本**
     * （实测 B 站评论框：占位「这里是评论区，不是无人区;-)」被当成输入记了下来）。
     * 所以把首条文本记为疑似占位，之后若原样重现就跳过。
     */
    private final Map<String, String> firstSeenText = new HashMap<>();
    /** 轮询去重：控件路径 -> 上次读到的文本，避免每 900ms 重复记同一条 */
    private final Map<String, String> pollLastText = new HashMap<>();
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
        // 采集一开始就顺手拉起保活，防止进程被系统回收后采集静默中断
        if (Prefs.keepAlive(this)) {
            KeepAliveService.start(this);
        }
        // 回填历史记录里的应用名：早期版本受包可见性限制只存下了包名，
        // 补上 <queries> 声明后要把已记录的重新解析一遍，界面上才会显示「抖音」而不是包名
        handler.post(new Runnable() {
            @Override
            public void run() {
                backfillLabels();
            }
        });
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
                if (DIAG.size() > 300) {
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
        // 「点了发送」是最可靠的消息边界信号 —— 比靠文本形态倒推靠谱得多。
        // 之前只订阅了文本变化，导致"发送"这个动作完全不可见，
        // 只能用共同开头长度去猜"是改字还是新消息"，反复出错。
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (isSendButton(event)) {
                long now = System.currentTimeMillis();
                if (now - lastSendAt > 500) {     // 防抖：一次点击可能触发多个事件
                    lastSendAt = now;
                    markSendBoundary(event.getPackageName() == null ? ""
                            : event.getPackageName().toString());
                }
            }
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
                // 最后一条兜底：节点完全拿不到时（微信就是这样：getSource() 返回 null、
                // getRootInActiveWindow() 也拿不到），直接用**事件自带的文本**记录。
                // 微信的每个文本变化事件都携带输入框当前完整文本 —— 事件里就有答案。
                // 这是"微信打字记不到"的最终解法。
                String evText = eventText(event);
                if (evText != null && !evText.isEmpty()) {
                    evRelaxedHit++;
                    handleText(pkg, null, evText);
                    return;
                }
                // 确实什么都拿不到：把当时窗口里有什么记下来，供诊断
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
                && !Prefs.isIgnored(this, pkg) && looksLikeInput(fromEvent)) {
            return fromEvent;
        }
        if (LogStore.isSkippedPackage(pkg) || Prefs.isIgnored(this, pkg)) {
            return null;
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (looksLikeInput(focused)) {
                    return focused;
                }
                AccessibilityNodeInfo deep = findEditable(root, 0);
                if (looksLikeInput(deep)) {
                    return deep;
                }
            }
        } catch (Throwable ignored) {
        }
        // 活动窗口拿不到时（微信等应用的常见情况：root 为 null、事件节点又不可编辑），
        // 只要事件节点有文本且不是密码框，就认它是输入框 —— 有文本本身就是强信号。
        if (fromEvent != null && !fromEvent.isPassword() && textOf(fromEvent) != null) {
            evRelaxedHit++;
            return fromEvent;
        }
        return null;
    }

    /**
     * 是否像"用户正在打字的输入框"。
     *
     * 关键坑：**微信的输入框节点 isEditable() 返回 false**（MIUI + 微信对无障碍做了处理），
     * 只认 isEditable() 会把微信全部漏掉 —— 这正是"微信打字记不到"的真正原因。
     * 所以这里放宽为三选一：可编辑 / 类名像输入框（EditText、Edit） / 有焦点且有文本。
     * 密码框始终排除。
     */
    private static boolean looksLikeInput(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            if (node.isPassword()) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (node.isEditable()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cls = node.getClassName();
            if (cls != null) {
                String n = cls.toString();
                if (n.contains("EditText") || n.endsWith("Edit")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return node.isFocused() && textOf(node) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 严格版"是不是输入框"：只认可编辑或有 EditText 类名的控件。
     *
     * 事件路径可以放宽（事件带文本本身就是强信号），但**轮询不能放宽** ——
     * 轮询是主动去读界面，放宽就会读到非输入框的文字。
     * 实测 B 站搜索页：轮询读到了 [错眉·42分钟前更新]（作者名+时间戳），
     * 那不是用户输入，却被当成输入记了下来。
     */
    private static boolean isStrictInput(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            if (node.isPassword()) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (node.isEditable()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cls = node.getClassName();
            if (cls != null) {
                String n = cls.toString();
                if (n.contains("EditText") || n.endsWith("Edit")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
    /** 节点当前文本；读不到返回 null（注意：空字符串代表"真的为空"，与 null 不同） */
    private static String textOf(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        try {
            CharSequence t = node.getText();
            if (t != null) {
                return t.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            // 有些输入框的文本挂在子节点上
            CharSequence d = deepText(node, 0);
            if (d != null) {
                return d.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }


    /** 遍历找真正的输入框（轮询专用，判定更严） */
    private static AccessibilityNodeInfo findStrictInput(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) {
            return null;
        }
        try {
            if (isStrictInput(node) && node.isVisibleToUser()) {
                return node;
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo hit = findStrictInput(node.getChild(i), depth + 1);
                if (hit != null) {
                    return hit;
                }
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
    /** 事件自带的文本（微信等应用只给事件、不给节点，全靠它） */
    private static String eventText(AccessibilityEvent event) {
        if (event == null) {
            return null;
        }
        try {
            java.util.List<CharSequence> list = event.getText();
            if (list != null && !list.isEmpty()) {
                CharSequence t = list.get(0);
                return t == null ? null : t.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

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
        // 输入框的"首条文本"极可能是占位提示（部分应用不用 getHintText，
        // 而是把提示语直接当 getText() 返回）。记下来，后续原样重现就跳过。
        {
            String fk = focusedNode != null ? fieldKey(focusedNode, pkg) : (pkg + "#@event");
            String seen;
            synchronized (LOCK) {
                seen = firstSeenText.get(fk);
                if (seen == null) {
                    firstSeenText.put(fk, text);
                    if (firstSeenText.size() > 200) {
                        firstSeenText.clear();
                        firstSeenText.put(fk, text);
                    }
                }
            }
            if (seen != null && seen.equals(text)) {
                skippedNoise++;
                return;
            }
        }

        // 只记"正在输入的框"。这道闸门放在最前面，是为了同时挡住三条来源：
        //   · 事件路径：同屏其它可编辑控件、界面重绘时补发的旧内容
        //   · 轮询路径：每 900ms 重读同一个框（不挡就会把同一段文字重复记很多遍）
        //   · 遍历兜底：findEditable 找到的第一个可编辑控件，未必是你正在输入的那个
        // 节点拿不到时（事件文本路径）无法判定焦点，此时放行：
        // 能收到带文本的文本变化事件，本身就说明用户正在那个框里打字。
        if (Prefs.focusOnly(this) && focusedNode != null && !isActiveInput(focusedNode)) {
            skippedNoFocus++;
            return;
        }
        // 占位提示文字不是用户输入（部分系统把 hint 当作文本回传）。
        // 注意：数据库里存的是 hint 原文 "搜索记录过的文字"，结尾的省略号是界面显示时才加的，
        // 所以这里必须按不含省略号的形式比对 —— 之前按含省略号比对，一直没拦住。
        if (PlainText.isPlaceholder(text)) {
            skippedNoise++;
            return;
        }
        final boolean comp = focusedNode != null && isComposing(focusedNode, text);
        final String field = focusedNode != null ? fieldKey(focusedNode, pkg) : (pkg + "#@event");

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
                return;   // 全是空白，不值得占一条记录
            }
            // 新建的输入框里只有一个字符：多半是打了一个字又立刻删掉的碎片，不占记录
            if (Prefs.minChars(this) > 1 && p.chars == 0 && text.trim().length() < 2) {
                skippedNoise++;
                return;
            }
            // 输入框的首条记录若是"默认占位文字"，不记录
            if (p.chars == 0 && PlainText.looksLikeEmptyFieldHint(text)) {
                skippedNoise++;
                return;
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
    /** 最近一次"发送"点击时间（防抖：一次点击可能触发多个事件） */
    private long lastSendAt;
    /** 检测到的"发送"点击次数（消息边界信号，仅用于诊断显示） */
    public static volatile long sendBoundaries;
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
            // 轮询必须用严格判定：放宽会读到非输入框的文字
            if (isStrictInput(f)) {
                target = f;
            }
        } catch (Throwable ignored) {
        }
        if (target == null) {
            target = findStrictInput(root, 0);
            if (target == null) {
                return;   // 轮询找不到真正的输入框就跳过，不猜
            }
        }
        if (target == null) {
            return;
        }
        String text = readText(target, null);
        if (text == null) {
            return;
        }
        // 轮询的最后一道去重：这个框的内容和上次轮询时一模一样，就不必再往下走。
        // （按控件路径判等，不依赖对象身份 —— 详见 fieldKey 的注释）
        String key = fieldKey(target, pkg);
        synchronized (LOCK) {
            String seen = pollLastText.get(key);
            if (text.equals(seen)) {
                return;
            }
            pollLastText.put(key, text);
            if (pollLastText.size() > 64) {
                pollLastText.clear();
                pollLastText.put(key, text);
            }
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

    /**
     * 常见占位提示语，识别为噪音（不记录）。
     *
     * 两层防线：
     *   1) 系统给的 hint（见 readText）—— 通用，能覆盖任意应用的占位文字，
     *      DeepSeek 的「发消息」就是靠它拦住的
     *   2) 本方法的关键词兜底 —— 应付 getHintText() 不可用的机型
     *
     * 踩过的坑：库里存的是 hint 原文（"搜索记录过的文字"），结尾的省略号是界面显示时才加的。
     * 曾经按含省略号的字符串比对，结果一条都没拦住。
     */
    /**
     * 输入框默认占位文字的兜底判定。
     *
     * 小米笔记的「开始书写或 创建思维笔记」有两个坑：
     *   1) 它出现在**第一个文本事件**里、且是那个输入框有史以来第一条记录；
     *   2) 它有多种变体（不同机型/语言/版本措辞不同），靠关键词列表补不完。
     * 所以再加一条形态特征：以"开始/创建/点击/请…"这类祈使词开头，
     * 带不带空格都算，且长度不长。
     *
     * 只在"这个输入框的第一条记录"上生效，正常书写几乎不会误伤。
     */
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

    /**
     * 判断被点击的是不是"提交类"按钮 —— 发送 / 搜索 / 发表 / 确定 等。
     *
     * 通用化过程：最初只认「发送」（聊天类应用），但搜索框没有发送按钮，
     * 它是「搜索」键或「搜索」图标按钮（实测 B 站就是一个 TextView[搜索]）。
     * 所以这里归纳为"提交动作"：凡是把输入框内容交出去的动作，都是消息/条目的边界。
     */
    private static boolean isSendButton(AccessibilityEvent event) {
        String label = "";

        AccessibilityNodeInfo node = null;
        try {
            node = event.getSource();
        } catch (Throwable ignored) {
        }
        if (node != null) {
            // 排除输入框自身的点击（点输入框不是提交动作）
            try {
                if (node.isEditable()) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
            try {
                CharSequence t = node.getText();
                if (t != null) {
                    label = t.toString();
                }
            } catch (Throwable ignored) {
            }
            if (label.isEmpty()) {
                try {
                    CharSequence d = node.getContentDescription();
                    if (d != null) {
                        label = d.toString();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (label.isEmpty()) {
                try {
                    CharSequence id = node.getViewIdResourceName();
                    if (id != null) {
                        label = id.toString();
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 节点拿不到就退回**事件自带的文本**。微信与 B 站的 getSource() 都可能为 null，
        // 但事件里一直带着按钮文字（实测：[发送] / [搜索]）。
        if (label.isEmpty()) {
            label = eventText(event);
            if (label == null) {
                label = "";
            }
        }

        label = label.trim();
        if (label.isEmpty() || label.length() > 20) {
            return false;
        }
        String low = label.toLowerCase();

        // 提交类动作词表（中文应用的实际叫法）
        String[] actions = {
                "发送", "发出", "发送给好友", "发送弹幕", "发送评论",
                "搜索", "搜一下", "搜",
                "发表", "发布", "评论", "回复", "提交", "投稿",
                "确定", "完成", "确认", "好了",
                "send", "search", "submit", "post", "comment", "reply", "done", "go",
        };
        for (String a : actions) {
            if (label.equals(a)) {
                return true;
            }
        }
        // 前缀匹配："发送给好友"「搜索一下」这类
        if (label.startsWith("发送") || label.startsWith("搜索") || label.startsWith("发表")) {
            return true;
        }
        return low.contains("send") || low.equals("search") || low.contains("submit");
    }
    /** 记录一次"发送"边界；下一条文本事件将另起一段 */
    private void markSendBoundary(String pkg) {
        SendBoundary.mark(pkg);      // 写入纯类，供 Burst 判段（避免 Burst 依赖 Android 服务）
        sendBoundaries++;
        Log.i(TAG, "检测到发送：pkg=" + pkg + " 累计=" + sendBoundaries);
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
        CharSequence hint = null;
        if (node != null) {
            try {
                hint = node.getHintText();
            } catch (Throwable ignored) {
            }
            try {
                t = node.getText();
            } catch (Throwable ignored) {
            }
            // 输入框为空时，部分系统的无障碍会把"占位提示文字"当作文本返回
            // （小米笔记就是：getText() 直接给出「开始书写或 创建思维笔记」）。
            // 所以要在**拿到文本之后**跟 hint 比对，而不是只在 getText() 为空时才看 hint
            // —— 之前那样写，遇到"getText() 返回 hint"的机型就完全失效。
            if (!TextUtils.isEmpty(t) && !TextUtils.isEmpty(hint)
                    && t.toString().trim().equals(hint.toString().trim())) {
                return "";
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

    /**
     * 输入框的稳定标识。
     *
     * 坑：兜底分支曾经用 System.identityHashCode(node) —— 但每次事件/轮询拿到的
     * 都是**新的 AccessibilityNodeInfo 对象**，identityHashCode 每次都变，于是
     * 同一个输入框被当成无数个"不同的框"，按框去重完全失效，同一段文字被重复记录。
     * 现在改成基于控件树位置的确定性路径，同一个框每次都算出同一个 key。
     */
    private static String fieldKey(AccessibilityNodeInfo node, String pkg) {
        try {
            CharSequence id = node.getViewIdResourceName();
            if (id != null && id.length() > 0) {
                return pkg + "#" + id;
            }
        } catch (Throwable ignored) {
        }
        return pkg + "#@path:" + nodePath(node);
    }

    /** 从根到该节点的子节点下标路径，例如 0.2.1 —— 与对象身份无关，稳定可复现 */
    private static String nodePath(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        try {
            AccessibilityNodeInfo cur = node;
            int guard = 0;
            while (cur != null && guard++ < 32) {
                AccessibilityNodeInfo parent = cur.getParent();
                if (parent == null) {
                    break;
                }
                int idx = -1;
                int n = parent.getChildCount();
                for (int i = 0; i < n; i++) {
                    AccessibilityNodeInfo c = parent.getChild(i);
                    if (c == null) {
                        continue;
                    }
                    if (c.equals(cur)) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    break;
                }
                sb.insert(0, "." + idx);
                cur = parent;
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "root" : sb.substring(1);
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
        PackageManager pm = getPackageManager();
        // 优先用 queryIntentActivities：安卓 11+ 的包可见性限制下，
        // 它比 getApplicationInfo 更容易拿到别的应用的标签
        // （已配合 Manifest 里的 <queries> 声明）
        try {
            android.content.Intent main =
                    new android.content.Intent(android.content.Intent.ACTION_MAIN);
            main.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            main.setPackage(pkg);
            java.util.List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            if (ris != null && !ris.isEmpty()) {
                CharSequence l = ris.get(0).loadLabel(pm);
                if (l != null && l.length() > 0) {
                    name = l.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        if (name.equals(pkg)) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence l = pm.getApplicationLabel(ai);
                if (l != null && l.length() > 0) {
                    name = l.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        synchronized (labelCache) {
            if (labelCache.size() > 200) {
                labelCache.clear();
            }
            labelCache.put(pkg, name);
        }
        return name;
    }


    /**
     * 回填历史记录里的应用名。
     *
     * 为什么要它：早期版本因为包可见性限制拿不到别的应用的名字，索引里存的都是包名。
     * 补上 <queries> 声明后需要把已记录的那些重新解析一遍，否则界面里永远显示
     * com.ss.android.ugc.aweme 这种，而不是「抖音」。
     */
    private void backfillLabels() {
        if (store == null) {
            return;
        }
        try {
            java.text.SimpleDateFormat dayF =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            for (int back = 0; back < 3; back++) {
                String day = dayF.format(cal.getTime());
                java.util.Set<String> pkgs = new java.util.LinkedHashSet<>();
                for (LogStore.Row row : store.readDay(day, 0)) {
                    if (row.app != null && !row.app.isEmpty()) {
                        pkgs.add(row.app);
                    }
                }
                for (String p : pkgs) {
                    String lb = label(p);          // 解析成功会进 labelCache
                    if (lb != null && !lb.equals(p)) {
                        store.putAppLabel(day, p, lb);
                    }
                }
                cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
            }
            Log.i(TAG, "应用名回填完成");
        } catch (Throwable t) {
            Log.w(TAG, "应用名回填失败", t);
        }
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


    /**
     * 这条记录之后是否发生过"发送"点击。
     *
     * 这是最可靠的消息边界信号：用户点了发送 ⇒ 上一条消息结束。
     * 相比之下"靠文本形态猜是改字还是新消息"总会出错
     * （微信发消息不产生空状态，文本直接从旧消息跳到新消息）。
     */
}
