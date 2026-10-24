package com.draftguard;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 界面：状态、开关、今天记了哪些 App、全文搜索、导出。
 * 故意不依赖 androidx，用代码搭布局 —— 这样单文件工程就能直接 aapt2+javac 构建出 APK。
 */
public class MainActivity extends Activity {

// 配色：只用三种前景色，靠字号而不是靠颜色做层次
    static final int COL_FG = 0xFFE8ECF3;      // 正文
    static final int COL_DIM = 0xFF8B95A7;     // 次要信息
    static final int COL_ACCENT = 0xFF5B9DFF;  // 可点/强调
    static final int COL_CARD = 0xFF232833;    // 卡片底色（比页面明显亮一些，才看得出是卡片）

    private LinearLayout pageRoot;          // 记录页的容器（= page1）
    private TextView summaryText;           // 顶部一行摘要
    private EditText searchBox;
    private android.widget.ListView listRecords;
    private RecordAdapter adapter;
    private Button btnRange, btnRaw;

    /** 记录列表的数据：合并视图下是 Burst，逐条视图下是 Row */
    private final java.util.List<Object> items = new java.util.ArrayList<>();

    /** 搜索关键词（空 = 不过滤）。搜索直接过滤列表，而不是另开一个结果区 */
    private String query = "";

    final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
    /** 按天分桶用的格式；适配器也要用（跨天显示日期），所以是包内可见 */
    final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", Locale.US);

    /** 看记录时是否显示原始逐条版本（默认合并成整段，见 Burst） */
    private boolean showRawRows = false;

    // ── 第三页（工具）：状态卡片 + 设置 + 诊断 ──────────────
    private android.widget.ScrollView page3;
    private LinearLayout toolsRoot;
    private TextView statusView, liveView, statsView, appsView, diagView;
    /** 设置区是否展开（默认收起：页面上平铺十来行很容易误触） */
    private boolean settingsExpanded = false;
    private LinearLayout settingsBox;
    private TextView settingsHeader;

    /** 诊断区是否展开（默认收起：内容很长，压住页面） */
    private boolean diagExpanded = false;
    private LinearLayout diagBox;
    private TextView diagHeader;
    private TextView navToolsText;
    private View navToolsBar;

    // ── 导航与第二页（应用列表）────────────────────────────────
    private android.widget.ScrollView page2;
    private LinearLayout appsRoot;
    private TextView navRecordsText, navAppsText;
    private View navRecordsBar, navAppsBar;
    private int currentPage = 0;

    /** 应用图标缓存：null 表示"试过但拿不到"，避免反复请求 */
    final java.util.Map<String, android.graphics.drawable.Drawable> iconCache =
            new java.util.HashMap<>();

    /** 第二页当前是否处于"某个应用的详情"状态（非 null 时显示详情） */
    private String detailApp = null;

    /**
     * 记录视图的时间范围（分钟）。0 = 不限。
     *
     * 为什么需要它：实测半天就有 959 个版本 / 918 段，
     * 全部铺出来是一整面文字墙，根本没法一段段看。默认只看最近 2 小时。
     */
    // 默认只看最近 24 小时。
    // 之前默认 0（全部时间），数据攒到几天后每次刷新都要把所有日期的文件
    // 全读一遍 + 全排序 + 全分段，于是"越大越慢"。
    // 实测 17 天 / 21109 个版本 / 9.4 MB 时就明显卡了。
    // 要看全部，用「全部时间」按钮主动切换（那时慢是有意的）。
    private int rangeMinutes = 1440;

    /**
     * 每次最多把多少行交给分段器。
     *
     * 为什么要有这个上限：界面最多显示 400 段，而"全部时间"可能有 2 万多行 ——
     * 把 2 万行全部分段的唯一效果是算完之后被 400 条的上限截掉。
     * 取最新 6000 行足够产出 400 段以上，且把"越大越慢"从线性变成常数。
     *
     * 注意：这是**范围裁剪**，不是过滤 —— 保留的是连续的最新一段窗口，
     * 窗口内的分段边界（发送标记/清空）一个都不动，不违反分层原则。
     */
    private static final int SEGMENT_ROW_CAP = 6000;

    /** 应用详情页最多读多少行（超出后提示"只显示最新 N 个"） */
    private static final int APP_DETAIL_ROW_CAP = 6000;

    /** 列表分页：每点一次"加载更早"多一页（每页 SEGMENT_ROW_CAP 行） */
    private int pagesLoaded = 1;

    /** 按天缓存的解析结果：day -> {签名, List<Row>}；签名由该日各 jsonl 的长度+修改时间算出 */
    private static final java.util.Map<String, Object[]> dayCache = new java.util.HashMap<>();

    /** 最近一次列表加载耗时（毫秒）—— 诊断用，能直接看出优化是否有效 */
    static volatile long lastListMs;
    static volatile int lastListDays;
    static volatile int lastListRows;
    static volatile int lastListSegmented;

    /** 应用筛选（空 = 全部） */
    private final java.util.Set<String> appFilter = new java.util.HashSet<>();

    /** 一次最多渲染多少段，避免超长文本块 */
    private static final int MAX_SEGMENTS = 200;

    private final BroadcastReceiver statsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 只刷新摘要与列表。列表内部会保留滚动位置，
            // 不再重写"滚动位置上方的卡片"（那会让界面看起来自己跳动）。
            long now = System.currentTimeMillis();
            if (now - lastStatsRefresh <= STATS_REFRESH_MS) {
                // 节流窗口内**不能直接丢弃**：被吃掉的往往正是"最后一次落盘"的通知，
                // 界面于是停在旧内容上，看起来就像"缺少最后几个字"（用户实测）。
                // 改成尾随触发：窗口一满就补刷一次，最后一次必被看到。
                ui.removeCallbacks(trailingRefresh);
                ui.postDelayed(trailingRefresh,
                        STATS_REFRESH_MS - (now - lastStatsRefresh) + 20);
                return;
            }
            {
                lastStatsRefresh = now;
                refreshSummary();
                refreshList();
                if (currentPage == 2) {
                    refreshTools();
                    // 诊断展开时也要跟着刷新。
                    // 之前只在"展开那一刻"填一次，之后事件来了内容不更新，
                    // 看起来就像"卡住了 / 不实时"。
                    if (diagExpanded && now - lastDiagRefresh > DIAG_REFRESH_MS) {
                        lastDiagRefresh = now;
                        refreshTools();
                    }
                }
            }
        }
    };

    /** 统计卡片刷新节流间隔 */
    private static final long STATS_REFRESH_MS = 2000;
    private long lastStatsRefresh;

    /** 节流窗口满时补刷一次，保证"最后一次落盘"一定被界面看到 */
    private final Runnable trailingRefresh = new Runnable() {
        @Override
        public void run() {
            lastStatsRefresh = System.currentTimeMillis();
            refreshSummary();
            refreshList();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        launchedAt = System.currentTimeMillis();
        setupNav();
        // 打开应用时确保保活服务在跑（用户可能在设置里开过又关了）
        if (Prefs.keepAlive(this)) {
            KeepAliveService.start(this);
        } else {
            KeepAliveService.stop(this);
        }
        refreshList();   // 首次调用会创建 RecordsPage 并装配视图
        buildToolsPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(TypelogService.EXTRA_EVENT);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statsReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statsReceiver, f);
        }
        lastStatsRefresh = System.currentTimeMillis();
        refreshSummary();
        refreshList();
        refreshTools();
        // 宽限期后先刷新状态显示；**再等一段**才考虑弹窗。
        // 实测：安装/force-stop 之后系统重新绑定无障碍服务可能要 8 秒以上，
        // 只查一次会在服务马上要连上时误报"记录已经停了"。
        ui.postDelayed(this::refreshServiceCard, CONNECT_GRACE_MS + 1500);
        ui.postDelayed(this::warnIfServiceStalled, 22000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statsReceiver);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 界面

    /** 轻量文字按钮（用于"刷新"这类次要动作，不做成方块按钮） */
    TextView link(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(COL_ACCENT);
        t.setTextSize(13);
        t.setPadding(dp(10), dp(4), dp(4), dp(4));
        t.setClickable(true);
        return t;
    }

    /** 圆角卡片背景 */
    android.graphics.drawable.Drawable rounded(int fill, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        float r = dp(radiusDp);
        g.setCornerRadius(r);
        return g;
    }

    /** 「更多」：导出、分享、诊断、清除、设置都收在这里 */
        /** 诊断面板：一眼看出"断在哪一环" */
            private void chooseRetention() {
        chooseRetention(null);
    }

    /** @param after 选完之后的回调（用于刷新设置列表里的当前值） */
    void chooseRetention(final Runnable after) {
        final int[] opts = {7, 30, 90, 365};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " 天";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("记录保留多少天")
                .setItems(labels, (d, which) -> {
                    Prefs.setRetentionDays(this, opts[which]);
                    toast("已设为 " + opts[which] + " 天");
                    if (after != null) {
                        after.run();
                    }
                })
                .show();
    }

    /** 从今天出现过的 App 里挑要忽略的 */
    void chooseIgnored() {
        new Thread(() -> {
            final LogStore store = new LogStore(getFilesDir(), 0);
            final String today = DAY.format(new Date());
            final Map<String, Integer> counts = new LinkedHashMap<>();
            final Map<String, String> labels = store.labels(today);
            for (LogStore.Row r : store.readDay(today, 0)) {
                Integer c = counts.get(r.app);
                counts.put(r.app, c == null ? 1 : c + 1);
            }
            final String[] pkgs = counts.keySet().toArray(new String[0]);
            final String[] names = new String[pkgs.length];
            final boolean[] checked = new boolean[pkgs.length];
            for (int i = 0; i < pkgs.length; i++) {
                String n = labels.get(pkgs[i]);
                names[i] = (TextUtils.isEmpty(n) ? pkgs[i] : n) + "  " + pkgs[i];
                checked[i] = Prefs.isIgnored(this, pkgs[i]);
            }
            ui.post(() -> {
                if (pkgs.length == 0) {
                    toast("今天还没有记录，无法选择");
                    return;
                }
                new android.app.AlertDialog.Builder(this)
                        .setTitle("勾选 = 不记录该 App")
                        .setMultiChoiceItems(names, checked, (d, which, isChecked) ->
                                Prefs.setIgnored(this, pkgs[which], isChecked))
                        .setPositiveButton("完成", null)
                        .show();
            });
        }, "typelog-ignored").start();
    }

    /** 让卡片的标题行加粗，正文保持常规 */
    // ══════════════════════════════════════════════ 页面切换

    private void setupNav() {
        pageRoot = findViewById(R.id.page1);
        page2 = findViewById(R.id.page2);
        page3 = findViewById(R.id.page3);
        toolsRoot = findViewById(R.id.toolsRoot);
        appsRoot = findViewById(R.id.appsRoot);
        navRecordsText = findViewById(R.id.navRecordsText);
        navAppsText = findViewById(R.id.navAppsText);
        navRecordsBar = findViewById(R.id.navRecordsBar);
        navAppsBar = findViewById(R.id.navAppsBar);
        findViewById(R.id.tabRecords).setOnClickListener(v -> switchPage(0));
        findViewById(R.id.tabApps).setOnClickListener(v -> switchPage(1));
        findViewById(R.id.tabTools).setOnClickListener(v -> switchPage(2));
        navToolsText = findViewById(R.id.navToolsText);
        navToolsBar = findViewById(R.id.navToolsBar);
        switchPage(0);
    }

    /**
     * 切换页面。
     *
     * 这里用"两个 ScrollView 互斥显示"，而不是 Fragment ——
     * 项目一直不用 androidx，Fragment 会引入依赖；页面只有两个，隐藏/显示足够。
     */
    private void switchPage(int idx) {
        currentPage = idx;
        pageRoot.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        page2.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        page3.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);

        int on = Color.parseColor("#FFFFFF");
        int off = Color.parseColor("#8B95A7");
        int barOn = Color.parseColor("#5B9DFF");
        int barOff = Color.parseColor("#1A1D26");
        navRecordsText.setTextColor(idx == 0 ? on : off);
        navAppsText.setTextColor(idx == 1 ? on : off);
        navRecordsText.setTypeface(null, idx == 0 ? Typeface.BOLD : Typeface.NORMAL);
        navAppsText.setTypeface(null, idx == 1 ? Typeface.BOLD : Typeface.NORMAL);
        navRecordsBar.setBackgroundColor(idx == 0 ? barOn : barOff);
        navAppsBar.setBackgroundColor(idx == 1 ? barOn : barOff);
        if (navToolsText != null) {
            navToolsText.setTextColor(idx == 2 ? on : off);
            navToolsText.setTypeface(null, idx == 2 ? Typeface.BOLD : Typeface.NORMAL);
            navToolsBar.setBackgroundColor(idx == 2 ? barOn : barOff);
        }

        if (idx == 1) {
            refreshAppsPage();
        } else if (idx == 2) {
            refreshTools();
        }
    }

    // ══════════════════════════════════════════════ 第二页：应用列表

    /** 应用页（实现在 AppsPage） */
    private AppsPage appsPage;

    /** 重建应用列表（页面切换 / 删除后调用） */
    private void refreshAppsPage() {
        if (appsPage == null) {
            appsPage = new AppsPage(this, appsRoot);
        }
        appsPage.refresh();
    }

    /** 记录页（实现在 RecordsPage） */
    private RecordsPage recordsPage;

    /** 列表刷新：页面切换、删除后、收到采集广播时调用 */
    private void refreshList() {
        if (recordsPage == null) {
            recordsPage = new RecordsPage(this, pageRoot);
            recordsPage.build();
        }
        recordsPage.refreshList();
    }

    /** 顶部摘要刷新 */
    private void refreshSummary() {
        if (recordsPage != null) {
            recordsPage.refreshSummary();
        }
    }

    /** 工具页（实现在 ToolsPage） */
    private ToolsPage toolsPage;

    /** 装配工具页：首次调用时创建 */
    private void buildToolsPage() {
        if (toolsPage == null) {
            toolsPage = new ToolsPage(this, toolsRoot);
            toolsPage.build();
        }
    }

    /** 刷新四张状态卡片 */
    private void refreshTools() {
        if (toolsPage != null) {
            toolsPage.refreshTools();
        }
    }

    /** 刷新"采集状态"卡片（服务状态变化时） */
    private void refreshServiceCard() {
        if (toolsPage != null) {
            toolsPage.refreshServiceCard();
        }
    }

    /** 服务掉了但界面还没反应时提醒用户（带节流） */
    private void warnIfServiceStalled() {
        if (toolsPage != null) {
            toolsPage.warnIfServiceStalled();
        }
    }

    /** 服务状态名（诊断面板用；实现在 ServiceState） */
    private String serviceStateName() {
        return ServiceState.name(serviceState());
    }

    TextView heading(String text) {
        TextView t = new TextView(this);
        t.setTextColor(Color.parseColor("#E8ECF3"));
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(2), dp(2), 0, dp(6));
        t.setText(text);
        return t;
    }

    /**
     * 拿不到真实图标时的兜底：用应用名首字画一个带底色的圆角方块。
     * 比统一显示一个通用图标更容易分辨。
     */
    android.graphics.drawable.Drawable letterIcon(String label, String pkg) {
        int sz = dp(42);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        // 底色由包名散列决定，同一应用每次颜色一致
        int[] palette = {0xFF3A6EA5, 0xFF4C8C6B, 0xFF9A5BA8, 0xFFB07340,
                         0xFF5B6FA8, 0xFF8C5B5B, 0xFF4E7C8C, 0xFF7A6BA8};
        p.setColor(palette[Math.abs(pkg.hashCode()) % palette.length]);
        float r = sz * 0.24f;
        c.drawRoundRect(new android.graphics.RectF(0, 0, sz, sz), r, r, p);

        p.setColor(0xFFFFFFFF);
        p.setTextSize(sz * 0.44f);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        String ch = (label == null || label.isEmpty()) ? "?" : label.substring(0, 1);
        float baseline = sz / 2f - (p.descent() + p.ascent()) / 2f;
        c.drawText(ch, sz / 2f, baseline, p);
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    // ══════════════════════════════════════════════ 应用详情（按天分页）

    // ══════════════════════════════════════════════ 应用详情（第二页内）

    static void span(android.text.SpannableStringBuilder sb, String text,
                             int color, float scale, boolean bold) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (scale != 1f) {
            sb.setSpan(new android.text.style.RelativeSizeSpan(scale), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) {
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
    Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ------------------------------------------------------------------ 刷新

    /**
     * 重新读取并填充列表。
     *
     * 保留滚动位置：事件到达时会自动刷新，若每次都跳回顶部，
     * 用户正在看的内容就会被顶走（这正是"界面自己跳回统计区"的老问题）。
     */
/** 某一天各 jsonl 的签名：长度与修改时间混一起。变了就说明文件被动过 */
    private static long daySignature(java.io.File dir) {
        long sig = 17;
        java.io.File[] fs = dir.listFiles();
        if (fs == null) {
            return sig;
        }
        for (java.io.File f : fs) {
            if (f.getName().endsWith(".jsonl")) {
                sig = sig * 131 + f.length() * 31 + f.lastModified();
            }
        }
        return sig;
    }

/** 长按一条记录 → 确认后单点删除（隐私数据） */

    void confirmDelete(final Object item) {
        final String app;
        final String field;
        final String fromTs;
        final String toTs;
        final String what;
        final String preview;
        if (item instanceof Burst) {
            Burst b = (Burst) item;
            app = b.app;
            field = b.field;
            fromTs = b.firstTs;
            toTs = b.lastTs;
            what = b.versions > 1 ? "这一整段（含 " + b.versions + " 个原始版本）" : "这一条";
            preview = b.text;
        } else if (item instanceof LogStore.Row) {
            LogStore.Row rr = (LogStore.Row) item;
            app = rr.app;
            field = rr.field;
            fromTs = rr.ts;
            toTs = rr.ts;
            what = rr.text.isEmpty() ? "这条（无正文，可能是分段边界）" : "这一条";
            preview = rr.text;
        } else {
            return;
        }
        String pv = preview == null ? "" : preview.replace('\n', ' ').trim();
        if (pv.length() > 80) {
            pv = pv.substring(0, 80) + "…";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("要删除" + what + "吗？\n\n" + pv
                        + "\n\n删除后无法恢复 —— 这是隐私删除，刻意不留备份。")
                .setPositiveButton("删除", (d, w) -> doDelete(app, field, fromTs, toTs))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 真正执行删除（后台线程），完成后作废缓存并刷新 */
    private void doDelete(final String app, final String field,
                          final String fromTs, final String toTs) {
        new Thread(() -> {
            int n;
            try {
                LogStore st = new LogStore(getFilesDir(), 0);
                n = st.deleteRange(app, field, fromTs, toTs);
                st.closeAll();
            } catch (Throwable e) {
                final String msg = String.valueOf(e.getMessage());
                ui.post(() -> toast("删除失败：" + msg));
                return;
            }
            // 服务端可能还开着旧文件句柄：不关掉的话，后续记录会写进已被替换的旧文件
            TypelogService.reopenFilesAfterDelete();
            dayCache.clear();          // 文件被重写了，缓存全部作废
            final int deleted = n;
            ui.post(() -> {
                toast(deleted > 0 ? "已删除 " + deleted + " 条" : "没有找到要删的记录");
                refreshList();
            });
        }, "typelog-delete").start();
    }

        /** 诊断弹窗：原来页面上的状态/实时预览/统计/应用列表都放这里 */
    /** 诊断：重构时把原来页面上的内容都收到这里，注意别丢段落 */
                private static String tail(String s, int n) {
        return Diagnostics.tail(s, n);
    }

    // ── 服务状态 ──
    // 状态常量在 ServiceState 里定义（含四条状态的语义说明）





    /** Activity 启动时刻，用于"宽限期"判断 */
    private long launchedAt;

    /** 服务连接宽限期：刚打开应用时服务往往还在连接，不能立刻报"被停用" */
    private static final long CONNECT_GRACE_MS = 10000;

    /** 最近一次判定出的服务状态（供诊断面板显示） */
    private int svcState = ServiceState.OFF;

    /**
     * 服务状态。
     *
     * 踩过的坑（两次）：
     *  1) 原来只判断"是否出现在 enabled_accessibility_services 里"，
     *     而 MIUI 停用服务后列表条目仍保留 → 界面显示"正在记录"，实际一条不记。
     *  2) 用户"在列表里打开了 DraftGuard"却仍报停用 —— 因为 MIUI 无障碍页
     *     **顶部还有一个总开关**（Settings.Secure.ACCESSIBILITY_ENABLED），
     *     总开关关着时，列表里的服务也不会被绑定。
     *     所以这两种情况必须分开说，指引才准确。
     */
    // ── 服务状态 ──

    /** Activity 启动时刻，用于"宽限期"判断 */

    /** 服务连接宽限期：刚打开应用时服务往往还在连接，不能立刻报"被停用" */

    /** 最近一次状态探测的原始依据（写进诊断，方便排查"为什么这么判断"） */
    private volatile String lastStateProbe = "";

    /**
     * 系统无障碍总开关是否打开。
     *
     * 用 AccessibilityManager（系统给应用准备的 API），
     * 不再依赖解析 Settings.Secure —— 实测本机读不到那个值，
     * 会把"已启用"误判成"未启用"。
     */
    /** 采集状态（委派给 ServiceState；判定依据存 lastStateProbe 供诊断显示） */
    int serviceState() {
        ServiceState.Result r = ServiceState.detect(this, TypelogService.running,
                launchedAt, CONNECT_GRACE_MS);
        lastStateProbe = r.reason;
        svcState = r.state;
        return r.state;
    }

    /** 上次弹"服务没在记录"的时间，用于节流 */
    private long lastStalledWarnAt;

        /** 采集状态文案（含"该怎么办"） */
        /** 去系统无障碍设置 */
        /** 上次弹"服务没在记录"的时间，用于节流 */

    /** 上次弹"服务已停用"的时间，用于节流 */

    /**
     * 服务被系统停用时给一个弹窗。
     *
     * 只在"以前确实开过"（Prefs.serviceEnabledAt > 0）时提醒，
     * 免得第一次装应用就弹一个用户看不懂的东西；并做 5 分钟节流。
     */
            /** 截取命中位置前后各 60 字，方便快速确认是不是要找的那段 */
        /**
     * 看今天全部记录。
     *
     * 默认用「合并视图」：把逐字版本流合并成一段段完整的话 —— 存储层保留每次变化
     * （防丢就靠它），但给人看的时候不该显示 你→你好→你好呀 这种增量过程。
     * 想看原始增量时用下面的「逐条」开关。
     */
        /** 把加粗标题与已渲染好的 Spannable 正文拼到一起（避免分两步设置导致正文被覆盖） */
        /**
     * 把"按钮 + 结果区"滚到可见。
     *
     * 结果可能很长，用户点完按钮后若停在别处会找不到内容；
     * 直接滚到结果卡片顶部，让"按钮在上、内容紧接其下"。
     */
        /**
     * 这个卡片此刻是否**完整可见**。
     *
     * 为什么要判断：每次事件都重写状态卡与实时预览的文字（实时预览最多 200 字），
     * 它们高度一变，**滚动位置下方的所有内容都会整体位移** ——
     * 用户正看记录，界面却像"自己跳回统计区"。
     * 看不见的卡片刷新没有任何意义，只会制造跳动。
     */
        /** 有卡片因为不可见而跳过了刷新，滚回来时要补上 */
    private boolean skippedRefresh;

    /** 滚动时补刷：之前跳过的卡片若已可见，立刻更新，避免显示过期内容 */
        private String rangeLabel() {
        if (rangeMinutes <= 0) {
            return "全部时间";
        }
        if (rangeMinutes < 60) {
            return "最近 " + rangeMinutes + " 分钟";
        }
        return "最近 " + (rangeMinutes / 60) + " 小时";
    }

    /** 时间范围选择：实测半天就有 918 段，全铺出来没法看，所以给了范围开关 */
    /** 记录页的「时间范围」菜单（对话框仍住在宿主里，避免把 Prefs/弹窗逻辑搬过去） */
    void chooseRangeFor(RecordsPage page) {
        final int[] opts = {1440, 360, 60};
        String[] labels = {"最近 24 小时", "最近 6 小时", "最近 1 小时"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("记录的时间范围")
                .setItems(labels, (d, which) -> {
                    page.setRangeMinutes(opts[which]);
                    toast("已切换到「" + labels[which] + "」");
                    page.refreshList();
                })
                .show();
    }

    /** 记录页的「更多」菜单 */
    void showMoreFor(RecordsPage page) {
        showMore();   // 更多菜单与页面状态无关，直接复用
    }

    private void chooseRange() {
        // 没有「全部时间」了：数据攒多之后它要么很慢、要么只是个"看起来全"的假象。
        // 要翻更早的记录，去「应用」页按天翻 —— 那里一天一页，快且明确。
        final int[] opts = {1440, 360, 60};
        String[] labels = {"最近 24 小时", "最近 6 小时", "最近 1 小时"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("记录的时间范围")
                .setItems(labels, (d, which) -> {
                    rangeMinutes = opts[which];
                    if (btnRange != null) {
                        btnRange.setText(rangeLabel());
                    }
                    toast("已切换到「" + labels[which] + "」");
                    refreshList();
                })
                .show();
    }

    /** 应用筛选：从磁盘实际记录过的应用里挑 */
    private void chooseAppFilter() {
        new Thread(() -> {
            final LogStore store = new LogStore(getFilesDir(), 0);
            final String today = DAY.format(new Date());
            final Map<String, String> labels = store.labels(today);
            final java.util.LinkedHashSet<String> apps = new java.util.LinkedHashSet<>();
            for (LogStore.Row r : store.readDay(today, 0)) {
                if (r.app != null && !r.app.isEmpty()) {
                    apps.add(r.app);
                }
            }
            final String[] pkgs = apps.toArray(new String[0]);
            final String[] names = new String[pkgs.length];
            final boolean[] checked = new boolean[pkgs.length];
            for (int i = 0; i < pkgs.length; i++) {
                names[i] = name(labels, pkgs[i]) + "（" + pkgs[i] + "）";
                checked[i] = appFilter.contains(pkgs[i]);
            }
            ui.post(() -> {
                if (pkgs.length == 0) {
                    toast("今天还没有记录");
                    return;
                }
                new android.app.AlertDialog.Builder(this)
                        .setTitle("勾选 = 只看这些应用（都不勾 = 全部）")
                        .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {
                            if (isChecked) {
                                appFilter.add(pkgs[which]);
                            } else {
                                appFilter.remove(pkgs[which]);
                            }
                        })
                        .setPositiveButton("应用", (d, w) -> refreshList())
                        .setNeutralButton("清空筛选", (d, w) -> {
                            appFilter.clear();
                            refreshList();
                        })
                        .show();
            });
        }, "typelog-appfilter").start();
    }

    private static String name(Map<String, String> labels, String app) {
        return Diagnostics.name(labels, app);
    }

    /** 一行设置项：显示当前值，点击弹出对应选择 */
/** 诊断刷新节流间隔 */
    private static final long DIAG_REFRESH_MS = 2000;
    private long lastDiagRefresh;

    /**
     * 诊断正文。**组装逻辑在 Diagnostics**（本类只负责把结果贴到界面上）。
     * 页面与弹窗共用同一份文本 —— 分两处写就一定会走样。
     */
    StringBuilder diagText() {
        serviceState();     // 顺带刷新 lastStateProbe
        return Diagnostics.build(this,
                Diagnostics.ListStats.of(lastListMs, lastListDays, lastListRows, lastListSegmented),
                lastStateProbe, serviceStateName());
    }

    /** 设置项文案（实现在 ToolsText，页面与设置弹窗共用一份） */
    private String[] settingItems() {
        return ToolsText.settingItems(this);
    }

    /** 「更多」：导出、分享、清空，以及跳到「工具」页 */
    private void showMore() {
        final String[] items = {
                "导出到下载目录", "分享 zip", "设置与诊断（去「工具」页）", "清除全部记录",
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("更多")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            doExportToDownloads();
                            break;
                        case 1:
                            doExport();
                            break;
                        case 2:
                            switchPage(2);
                            break;
                        default:
                            confirmClearAll();
                            break;
                    }
                })
                .show();
    }
    void doExportToDownloads() {
        toast("正在导出到「下载/DraftGuard」…");
        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            final Uri uri = Exporter.toPublicDownloads(this, store);
            ui.post(() -> {
                if (uri == null) {
                    toast("导出失败：无法写入下载目录，可以改用「分享 zip」");
                } else {
                    String path = "下载/" + Exporter.PUBLIC_SUBDIR + "/DraftGuard-" + Exporter.stamp() + ".zip";
                    toast("已导出到 " + path);
                }
            });
        }, "typelog-export-pub").start();
    }

    void doExport() {
        toast("正在打包…");
        new Thread(() -> {
            File zip = null;
            String err = null;
            try {
                LogStore store = new LogStore(getFilesDir(), 0);
                zip = Exporter.toCache(this, store);
            } catch (Throwable t) {
                err = t.toString();
            }
            final File fz = zip;
            final String fe = err;
            ui.post(() -> {
                if (fe != null || fz == null || fz.length() == 0) {
                    toast("导出失败：" + fe);
                    return;
                }
                try {
                    Uri uri = new Uri.Builder()
                            .scheme("content")
                            .authority(LogFileProvider.AUTHORITY)
                            .appendPath(fz.getName())
                            .build();
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/zip");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "导出记录"));
                } catch (Throwable t) {
                    toast("分享失败：" + t);
                }
            });
        }, "typelog-export").start();
    }

    void chooseMinChars() {
        chooseMinChars(null);
    }

    /** @param after 选完之后的回调（用于刷新设置列表里的当前值） */
    void chooseMinChars(final Runnable after) {
        final int[] opts = {1, 2, 3, 5};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " 字" + (opts[i] == 1 ? "（不设门槛，单字符也记）" : "");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("新建输入框至少几字才记录")
                .setItems(labels, (d, which) -> {
                    Prefs.setMinChars(this, opts[which]);
                    toast("已设为 " + opts[which] + " 字");
                    if (after != null) {
                        after.run();
                    }
                })
                .show();
    }

    /** 清除全部记录：先给用户看一眼有多少条，再确认两次 */
    void confirmClearAll() {
        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            final int total = store.countAll();
            final long bytes = store.totalBytes();
            ui.post(() -> new android.app.AlertDialog.Builder(this)
                    .setTitle("清除全部记录")
                    .setMessage("当前共有 " + total + " 条记录，占用约 "
                            + (bytes < 1024 ? bytes + " 字节" : (bytes / 1024) + " KB") + "。\n\n"
                            + "清空前会自动备份到「下载 / DraftGuard / backup」，"
                            + "随时可以用文件管理器取回。\n\n确定要清空吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定清除", (d, w) -> {
                        toast("正在备份并清除…");
                        new Thread(() -> {
                            LogStore s2 = new LogStore(getFilesDir(), 0);
                            // 先备份到公共下载目录，用户可随时取回
                            Exporter.backupToPublicDownloads(this, s2, "preclear");
                            s2.clearAll();
                            ui.post(() -> {
                                toast("已清除 " + total + " 条记录，之后新打的字会重新记录");
                                refreshSummary();
                                refreshList();
                            });
                        }, "typelog-clear").start();
                    })
                    .show());
        }, "typelog-count").start();
    }
/**
     * 记录列表的适配器。
     *
     * 每行：应用图标 + 应用名 + 时间/字数/合并版本 + 正文。
     * 用 ListView 的视图回收，几百条也不会卡。
     */
    private final java.util.Map<String, String> labelCache = new java.util.HashMap<>();

    String labelOf(String pkg) {
        String v = labelCache.get(pkg);
        if (v != null) {
            return v;
        }
        // 先查磁盘索引里的应用名，取不到就退回包名
        try {
            LogStore store = new LogStore(getFilesDir(), 0);
            Map<String, String> lb = store.labels(DAY.format(new Date()));
            String hit = lb.get(pkg);
            if (hit != null && !hit.isEmpty()) {
                labelCache.put(pkg, hit);
                return hit;
            }
        } catch (Throwable ignored) {
        }
        labelCache.put(pkg, pkg);
        return pkg;
    }

}
