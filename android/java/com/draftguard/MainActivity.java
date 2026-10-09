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
    private static final int COL_FG = 0xFFE8ECF3;      // 正文
    private static final int COL_DIM = 0xFF8B95A7;     // 次要信息
    private static final int COL_ACCENT = 0xFF5B9DFF;  // 可点/强调
    private static final int COL_CARD = 0xFF232833;    // 卡片底色（比页面明显亮一些，才看得出是卡片）

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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
    private final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
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
    private final java.util.Map<String, android.graphics.drawable.Drawable> iconCache =
            new java.util.HashMap<>();

    /** 第二页当前是否处于"某个应用的详情"状态（非 null 时显示详情） */
    private String detailApp = null;

    /**
     * 记录视图的时间范围（分钟）。0 = 不限。
     *
     * 为什么需要它：实测半天就有 959 个版本 / 918 段，
     * 全部铺出来是一整面文字墙，根本没法一段段看。默认只看最近 2 小时。
     */
    private int rangeMinutes = 0;   // 0 = 全部时间（默认：避免"看起来没记录"）

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
            if (now - lastStatsRefresh > STATS_REFRESH_MS) {
                lastStatsRefresh = now;
                refreshSummary();
                refreshList();
                if (currentPage == 2) {
                    refreshTools();
                }
            }
        }
    };

    /** 统计卡片刷新节流间隔 */
    private static final long STATS_REFRESH_MS = 2000;
    private long lastStatsRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupNav();
        // 打开应用时确保保活服务在跑（用户可能在设置里开过又关了）
        if (Prefs.keepAlive(this)) {
            KeepAliveService.start(this);
        } else {
            KeepAliveService.stop(this);
        }
        buildRecordsPage();
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

    private void buildRecordsPage() {
        pageRoot.removeAllViews();

        // 1) 顶部摘要：一行小字。原来的状态/实时预览/统计/应用列表四张卡片
        //    都收进了「更多 → 诊断」，日常只留这一行。
        LinearLayout summaryRow = new LinearLayout(this);
        summaryRow.setOrientation(LinearLayout.HORIZONTAL);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        summaryRow.setPadding(dp(14), dp(10), dp(10), dp(2));
        summaryText = new TextView(this);
        summaryText.setTextColor(COL_DIM);
        summaryText.setTextSize(12);
        summaryText.setText("正在检测…");
        summaryText.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        summaryRow.addView(summaryText);
        TextView btnRefresh = link("刷新");
        btnRefresh.setOnClickListener(v -> {
            refreshSummary();
            refreshList();
        });
        summaryRow.addView(btnRefresh);
        pageRoot.addView(summaryRow);

        // 2) 搜索行：搜索直接过滤下面的列表，不再另开一个结果区
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(14), dp(4), dp(14), 0);
        searchBox = new EditText(this);
        searchBox.setHint("搜索记录过的文字…");
        searchBox.setHintTextColor(COL_DIM);
        searchBox.setTextColor(COL_FG);
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setPadding(0, dp(8), 0, dp(8));
        searchBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBox.setOnEditorActionListener((v, actionId, ev) -> {
            doSearch();
            return true;
        });
        searchRow.addView(searchBox);
        Button btnSearch = button("搜");
        btnSearch.setOnClickListener(v -> doSearch());
        searchRow.addView(btnSearch);
        pageRoot.addView(searchRow);

        // 3) 操作行：最常用的三个，其余在「更多」
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(dp(14), dp(8), dp(14), dp(6));
        btnRange = button(rangeLabel());
        btnRange.setOnClickListener(v -> chooseRange());
        actionRow.addView(btnRange);
        btnRaw = button(showRawRows ? "看合并" : "看逐条");
        btnRaw.setOnClickListener(v -> {
            showRawRows = !showRawRows;
            btnRaw.setText(showRawRows ? "看合并" : "看逐条");
            refreshList();
        });
        actionRow.addView(btnRaw);
        Button btnMore = button("更多");
        btnMore.setOnClickListener(v -> showMore());
        actionRow.addView(btnMore);
        pageRoot.addView(actionRow);

        // 4) 列表占满剩余高度。用 ListView 而不是往 TextView 里拼字符串 ——
        //    几百条时后者是一整面文字墙，而且没有视图回收会卡。
        listRecords = new android.widget.ListView(this);
        listRecords.setDivider(null);
        listRecords.setDividerHeight(0);
        listRecords.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        listRecords.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new RecordAdapter();
        listRecords.setAdapter(adapter);
        pageRoot.addView(listRecords);
    }

    /** 轻量文字按钮（用于"刷新"这类次要动作，不做成方块按钮） */
    private TextView link(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(COL_ACCENT);
        t.setTextSize(13);
        t.setPadding(dp(10), dp(4), dp(4), dp(4));
        t.setClickable(true);
        return t;
    }

    /** 圆角卡片背景 */
    private android.graphics.drawable.Drawable rounded(int fill, int radiusDp) {
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
    private void chooseRetention(final Runnable after) {
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
    private void chooseIgnored() {
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

    private void refreshAppsPage() {
        appsRoot.removeAllViews();
        detailApp = null;
        appsRoot.addView(heading("应用"));
        TextView hint = new TextView(this);
        hint.setTextColor(Color.parseColor("#8B95A7"));
        hint.setTextSize(12);
        hint.setPadding(dp(2), 0, 0, dp(10));
        hint.setText("点任意一个应用，看它里面的打字记录");
        appsRoot.addView(hint);

        final TextView loading = new TextView(this);
        loading.setTextColor(Color.parseColor("#8B95A7"));
        loading.setTextSize(13);
        loading.setPadding(dp(2), dp(8), 0, 0);
        loading.setText("统计中…");
        appsRoot.addView(loading);

        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            // 全部历史（不限制天数）：用户要求"所有记录过的应用"
            final java.util.Map<String, LogStore.AppStat> stats = store.appsSummary(0);
            // 按最近使用时间倒序
            java.util.List<LogStore.AppStat> list =
                    new java.util.ArrayList<>(stats.values());
            java.util.Collections.sort(list, (x, y) -> y.lastTs.compareTo(x.lastTs));

            ui.post(() -> {
                appsRoot.removeView(loading);
                if (list.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setTextColor(Color.parseColor("#8B95A7"));
                    empty.setTextSize(13);
                    empty.setText("还没有任何记录。去任意 App 打几个字，这里就会出现它。");
                    appsRoot.addView(empty);
                    return;
                }
                for (LogStore.AppStat st : list) {
                    appsRoot.addView(appRow(st));
                }
                loadIcons(list);
            });
        }, "typelog-apps").start();
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setTextColor(Color.parseColor("#E8ECF3"));
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(2), dp(2), 0, dp(6));
        t.setText(text);
        return t;
    }

    /** 一行：图标 + 名字 + 条数（右侧） */
    private View appRow(final LogStore.AppStat st) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.parseColor("#1A1D26"));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);
        row.setClickable(true);
        row.setOnClickListener(v -> showAppDetail(st));

        ImageView icon = new ImageView(this);
        int sz = dp(42);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = dp(12);
        icon.setLayoutParams(ilp);
        android.graphics.drawable.Drawable d = iconCache.get(st.app);
        icon.setImageDrawable(d != null ? d : letterIcon(st.display(), st.app));
        icon.setTag(st.app);
        row.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setTextColor(Color.parseColor("#E8ECF3"));
        name.setTextSize(15);
        name.setText(st.display());
        col.addView(name);

        TextView meta = new TextView(this);
        meta.setTextColor(Color.parseColor("#8B95A7"));
        meta.setTextSize(11);
        meta.setPadding(0, dp(3), 0, 0);
        StringBuilder m = new StringBuilder();
        m.append(st.app);
        if (st.days > 1) {
            m.append("　").append(st.days).append(" 天");
        }
        if (st.lastTs.length() >= 16) {
            m.append("　最近 ").append(st.lastTs, 11, 16);
        }
        meta.setText(m.toString());
        col.addView(meta);
        row.addView(col);

        TextView count = new TextView(this);
        count.setTextColor(Color.parseColor("#5B9DFF"));
        count.setTextSize(13);
        count.setText(st.rows + " 条");
        row.addView(count);

        return row;
    }

    /**
     * 拿不到真实图标时的兜底：用应用名首字画一个带底色的圆角方块。
     * 比统一显示一个通用图标更容易分辨。
     */
    private android.graphics.drawable.Drawable letterIcon(String label, String pkg) {
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

    /** 后台批量取应用图标，取到后回填到列表上的 ImageView */
    private void loadIcons(java.util.List<LogStore.AppStat> stats) {
        new Thread(() -> {
            android.content.pm.PackageManager pm = getPackageManager();
            for (LogStore.AppStat st : stats) {
                if (iconCache.containsKey(st.app)) {
                    continue;
                }
                android.graphics.drawable.Drawable d = null;
                try {
                    // 清单里已声明 MAIN/LAUNCHER 的 <queries>，所以这里能看到
                    // 有启动图标的第三方应用（安卓 11+ 的包可见性限制）
                    d = pm.getApplicationIcon(st.app);
                } catch (Throwable ignored) {
                    // 取不到就走 letterIcon 兜底
                }
                synchronized (iconCache) {
                    iconCache.put(st.app, d);
                }
            }
            ui.post(() -> {
                // 回填：只更新图标还是兜底字的行
                for (int i = 0; i < appsRoot.getChildCount(); i++) {
                    View v = appsRoot.getChildAt(i);
                    if (!(v instanceof LinearLayout)) {
                        continue;
                    }
                    View first = ((LinearLayout) v).getChildAt(0);
                    if (!(first instanceof ImageView)) {
                        continue;
                    }
                    Object tag = first.getTag();
                    if (tag == null) {
                        continue;
                    }
                    android.graphics.drawable.Drawable d = iconCache.get(tag.toString());
                    if (d != null) {
                        ((ImageView) first).setImageDrawable(d);
                    }
                }
            });
        }, "typelog-icons").start();
    }

    // ══════════════════════════════════════════════ 应用详情（第二页内）

    /** 显示某个应用的全部记录，按天分组，带返回按钮 */
    private void showAppDetail(final LogStore.AppStat st) {
        detailApp = st.app;
        appsRoot.removeAllViews();

        // 顶部：返回 + 标题
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(10));

        Button back = button("← 返回");
        back.setOnClickListener(v -> refreshAppsPage());
        bar.addView(back);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        titleCol.setLayoutParams(tlp);

        TextView name = new TextView(this);
        name.setTextColor(Color.parseColor("#E8ECF3"));
        name.setTextSize(17);
        name.setTypeface(null, Typeface.BOLD);
        name.setText(st.display());
        titleCol.addView(name);

        TextView sub = new TextView(this);
        sub.setTextColor(Color.parseColor("#8B95A7"));
        sub.setTextSize(11);
        sub.setPadding(0, dp(2), 0, 0);
        sub.setText(st.rows + " 条　" + st.app);
        titleCol.addView(sub);
        bar.addView(titleCol);
        appsRoot.addView(bar);

        final TextView body = new TextView(this);
        body.setTextColor(Color.parseColor("#E8ECF3"));
        body.setTextSize(13);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.setBackgroundColor(Color.parseColor("#1A1D26"));
        body.setTextIsSelectable(true);
        body.setText("读取中…");
        appsRoot.addView(body);

        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            java.util.List<String> days = store.days();
            final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            int totalRows = 0;
            int totalSeg = 0;
            boolean firstDay = true;
            // 天从新到旧（days() 已倒序）
            for (String day : days) {
                java.util.List<LogStore.Row> rows = store.readDay(day, 0);
                java.util.List<LogStore.Row> mine = new java.util.ArrayList<>();
                for (LogStore.Row r : rows) {
                    if (st.app.equals(r.app)) {
                        mine.add(r);
                    }
                }
                if (mine.isEmpty()) {
                    continue;
                }
                totalRows += mine.size();
                java.util.List<Burst> bursts = Burst.groupNewestFirst(mine);
                totalSeg += bursts.size();
                if (!firstDay) {
                    sb.append("\n");
                }
                firstDay = false;
                span(sb, "──── " + day + "　" + bursts.size() + " 段 ────\n",
                        Color.parseColor("#8B95A7"), 0.9f, true);
                for (Burst b : bursts) {
                    sb.append("\n");
                    StringBuilder h = new StringBuilder();
                    h.append(b.firstTs, 11, 16);
                    if (!b.firstTs.substring(11, 16).equals(b.lastTs.substring(11, 16))) {
                        h.append("–").append(b.lastTs, 11, 16);
                    }
                    h.append("　").append(b.text.length()).append(" 字");
                    if (b.versions > 1) {
                        h.append("　合并 ").append(b.versions).append(" 版");
                    }
                    span(sb, h.toString() + "\n", Color.parseColor("#5B9DFF"), 0.85f, false);
                    span(sb, b.text + "\n", Color.parseColor("#E8ECF3"), 1.0f, false);
                }
            }
            final int fRows = totalRows;
            final int fSeg = totalSeg;
            ui.post(() -> {
                if (sb.length() == 0) {
                    body.setText("这个应用还没有可显示的内容。");
                } else {
                    android.text.SpannableStringBuilder head = new android.text.SpannableStringBuilder();
                    span(head, "共 " + fSeg + " 段　原始 " + fRows + " 个版本（全部时间）\n",
                            Color.parseColor("#8B95A7"), 0.9f, false);
                    span(head, "同一次连续输入只显示最后成型的整段。\n\n",
                            Color.parseColor("#8B95A7"), 0.9f, false);
                    head.append(sb);
                    body.setText(head);
                }
                sub.setText(fRows + " 条　" + st.app);
            });
        }, "typelog-appdetail").start();
    }

    private static void span(android.text.SpannableStringBuilder sb, String text,
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
            private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ------------------------------------------------------------------ 刷新

    /** 顶部一行摘要。原来的四张卡片内容都并进了「诊断」弹窗，这里只留最关键的两项 */
    private void refreshSummary() {
        boolean on = isServiceEnabled();
        final StringBuilder sb = new StringBuilder();
        sb.append(on ? "● 已开启 · 记录中" : "○ 未开启（点「更多 → 设置」或去系统设置开启）");
        new Thread(() -> {
            final LogStore store = new LogStore(getFilesDir(), 0);
            final String today = DAY.format(new Date());
            final java.util.List<LogStore.Row> rows = store.readDay(today, 0);
            int n = 0;
            for (LogStore.Row r : rows) {
                if (!r.text.isEmpty()) {
                    n++;
                }
            }
            final int count = n;
            final long bytes = store.totalBytes();
            final String lastApp = rows.isEmpty() ? "" : rows.get(rows.size() - 1).app;
            ui.post(() -> {
                sb.append(" · 今天 ").append(count).append(" 条");
                if (bytes > 0) {
                    sb.append(" · ").append(bytes < 1024 ? bytes + " B" : (bytes / 1024) + " KB");
                }
                if (!lastApp.isEmpty()) {
                    sb.append(" · 最近 ").append(labelOf(lastApp));
                }
                if (summaryText != null) {
                    summaryText.setText(sb.toString());
                }
            });
        }, "typelog-summary").start();
    }

    /**
     * 重新读取并填充列表。
     *
     * 保留滚动位置：事件到达时会自动刷新，若每次都跳回顶部，
     * 用户正在看的内容就会被顶走（这正是"界面自己跳回统计区"的老问题）。
     */
    private void refreshList() {
        if (adapter == null) {
            return;
        }
        final int first = listRecords.getFirstVisiblePosition();
        View v0 = listRecords.getChildAt(0);
        final int top = v0 == null ? 0 : v0.getTop();
        final boolean keep = listRecords.getCount() > 0;

        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);

            // 读**所有日期**，而不是只读今天 ——
            // 按钮写着「全部时间」，只给今天的数据是名不副实的。
            // days() 已按新→旧排序；跨天需要按时间重排后再分段。
            java.util.List<LogStore.Row> rows = new java.util.ArrayList<>();
            for (String day : store.days()) {
                rows.addAll(store.readDay(day, 0));
                if (rows.size() > 60000) {
                    break;      // 安全阀：数据异常大时不至于卡死
                }
            }
            java.util.Collections.sort(rows, (a, b) -> a.ts.compareTo(b.ts));

            long from = rangeMinutes <= 0 ? 0
                    : System.currentTimeMillis() - rangeMinutes * 60_000L;
            java.util.List<LogStore.Row> kept = new java.util.ArrayList<>();
            for (LogStore.Row r : rows) {
                if ("send".equals(r.ev) || r.text.isEmpty()) {
                    continue;
                }
                if (from > 0 && Burst.msOf(r.ts) < from) {
                    continue;
                }
                if (!appFilter.isEmpty() && !appFilter.contains(r.app)) {
                    continue;
                }
                if (!query.isEmpty() && !r.text.contains(query)) {
                    continue;
                }
                kept.add(r);
            }

            final java.util.List<Object> out = new java.util.ArrayList<>();
            if (showRawRows) {
                for (int i = kept.size() - 1; i >= 0 && out.size() < 400; i--) {
                    out.add(kept.get(i));
                }
            } else {
                java.util.List<Burst> bursts = Burst.groupNewestFirst(kept);
                for (int i = 0; i < bursts.size() && out.size() < 400; i++) {
                    out.add(bursts.get(i));
                }
            }

            ui.post(() -> {
                items.clear();
                items.addAll(out);
                adapter.notifyDataSetChanged();
                if (keep) {
                    listRecords.setSelectionFromTop(first, top);
                }
                if (items.isEmpty()) {
                    summaryText.setText(query.isEmpty()
                            ? "这个范围内还没有记录。去任意 App 打几个字就会出现在这里。"
                            : "没有找到包含「" + query + "」的记录。");
                }
            });
        }, "typelog-list").start();
    }



    private void doSearch() {
        if (searchBox == null) {
            return;
        }
        query = searchBox.getText().toString().trim();
        if (query.isEmpty()) {
            toast("已取消筛选");
        }
        refreshList();
        if (!query.isEmpty()) {
            toast("已筛选包含「" + query + "」的记录");
        }
    }

        /** 诊断弹窗：原来页面上的状态/实时预览/统计/应用列表都放这里 */
    /** 诊断：重构时把原来页面上的内容都收到这里，注意别丢段落 */
                private static String tail(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    private boolean isServiceEnabled() {
        if (TypelogService.running) {
            return true;
        }
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) {
                return false;
            }
            ComponentName me = new ComponentName(this, TypelogService.class);
            String a = me.flattenToString();
            String b = me.flattenToShortString();
            return flat.contains(a) || flat.contains(b);
        } catch (Throwable t) {
            return false;
        }
    }

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
    private void chooseRange() {
        final int[] opts = {0, 60, 360, 1440};
        String[] labels = {"全部时间（默认）", "最近 1 小时", "最近 6 小时", "最近 24 小时"};
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
        String n = labels == null ? null : labels.get(app);
        return (n == null || n.isEmpty()) ? app : n;
    }

    /** 第三页：四张状态卡片 + 设置 + 诊断。
     *
     * 这些内容原来挤在记录页顶部（要滚四屏才看到记录），后来收进弹窗，
     * 但弹窗看不全、也不方便与设置对照，于是独立成一页。
     */
    private void buildToolsPage() {
        toolsRoot.removeAllViews();

        statusView = cardView("采集状态", "正在检测…");
        toolsRoot.addView(statusView);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        Button btnOpen = button("去开启 / 检查服务");
        btnOpen.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        row.addView(btnOpen);
        Button btnRefresh = button("刷新");
        btnRefresh.setOnClickListener(v -> refreshTools());
        row.addView(btnRefresh);
        toolsRoot.addView(row);

        liveView = cardView("实时预览（最近一次输入）", "…");
        toolsRoot.addView(liveView);
        statsView = cardView("统计（今天）", "…");
        toolsRoot.addView(statsView);
        appsView = cardView("今天记录过的 App", "…");
        toolsRoot.addView(appsView);

        // ── 设置：默认收起，点标题展开；布尔项用拨动开关，开/关一眼可见 ──
        settingsHeader = sectionHeader("设置", settingsExpanded, v -> {
            settingsExpanded = !settingsExpanded;
            updateHeader(settingsHeader, "设置", settingsExpanded);
            buildSettingsBox();
        });
        toolsRoot.addView(settingsHeader);
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        toolsRoot.addView(settingsBox);
        buildSettingsBox();

        // ── 诊断：默认收起（内容很长），点标题展开 ──
        diagHeader = sectionHeader("诊断", diagExpanded, v -> {
            diagExpanded = !diagExpanded;
            updateHeader(diagHeader, "诊断", diagExpanded);
            buildDiagBox();
        });
        toolsRoot.addView(diagHeader);
        diagBox = new LinearLayout(this);
        diagBox.setOrientation(LinearLayout.VERTICAL);
        toolsRoot.addView(diagBox);
        buildDiagBox();
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(COL_FG);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    /** 一行设置项：显示当前值，点击弹出对应选择 */
    /** 更新折叠标题的箭头 */
    private void updateHeader(TextView t, String title, boolean expanded) {
        if (t != null) {
            t.setText((expanded ? "▾ " : "▸ ") + title);
        }
    }

    /** 可点击的分节标题（带展开/收起箭头） */
    private TextView sectionHeader(String title, boolean expanded, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText((expanded ? "▾ " : "▸ ") + title);
        t.setTextColor(COL_FG);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(4), dp(18), dp(4), dp(8));
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    /** 一行提示（收起状态下用） */
    private TextView collapsedHint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(COL_DIM);
        t.setTextSize(12);
        t.setPadding(dp(4), 0, dp(4), dp(4));
        return t;
    }

    /**
     * 只重建设置区，不重建整页 ——
     * 否则一展开就跳回页顶，反而更难用。
     */
    private void buildSettingsBox() {
        if (settingsBox == null) {
            return;
        }
        settingsBox.removeAllViews();
        if (!settingsExpanded) {
            settingsBox.addView(collapsedHint("已收起（共 11 项，点上面的「设置」展开）"));
            return;
        }
        // 布尔项：拨动开关
        settingsBox.addView(switchRow("后台保活（通知栏常驻）", Prefs.keepAlive(this), 0));
        settingsBox.addView(switchRow("只记录有焦点的输入框", Prefs.focusOnly(this), 1));
        settingsBox.addView(switchRow("忽略删除操作", Prefs.ignoreDeletions(this), 2));
        settingsBox.addView(switchRow("排除输入法键盘事件", Prefs.skipIme(this), 3));
        settingsBox.addView(switchRow("记录文本被清空", Prefs.keepEmpty(this), 4));
        settingsBox.addView(switchRow("轮询兜底", Prefs.polling(this), 5));
        settingsBox.addView(switchRow("诊断日志", Prefs.debug(this), 6));
        // 需要选值的项：保持"点开选择"
        settingsBox.addView(chooserRow("最少记录字数", Prefs.minChars(this) + " 字", 4));
        settingsBox.addView(chooserRow("保留天数", Prefs.retentionDays(this) + " 天", 5));
        settingsBox.addView(chooserRow("不记录的 App", "", 9));
        settingsBox.addView(chooserRow("保存位置", "", 10));
    }

    /** 只重建诊断区 */
    private void buildDiagBox() {
        if (diagBox == null) {
            return;
        }
        diagBox.removeAllViews();
        if (!diagExpanded) {
            diagView = null;
            diagBox.addView(collapsedHint("已收起（内容较长，含最近 25 条原始事件，点上面展开）"));
            return;
        }
        diagBox.addView(collapsedHint("排查问题用。内容较长，往下滚即可。"));
        diagView = cardView("事件与存储", "读取中…");
        diagBox.addView(diagView);
        // 展开后**立刻**填充。
        // 踩过的坑：填充原先只写在 refreshTools() 里，而它仅在切页或事件到来时执行，
        // 于是展开诊断后一直停在"读取中…"，要等下次有事件才显示。
        new Thread(() -> {
            final String d = diagText().toString();
            ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag").start();
    }

    /** 一行开关：左标签，右 Switch */
    private View switchRow(String label, boolean value, final int kind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(COL_CARD, 12));
        row.setPadding(dp(14), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COL_FG);
        t.setTextSize(13);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(value);
        sw.setShowText(false);
        sw.setOnCheckedChangeListener((btn, checked) -> applySwitch(kind, checked));
        row.addView(sw);
        return row;
    }

    /** 一行"点开选择"：左标签，右当前值 + 箭头 */
    private View chooserRow(String label, String value, final int which) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(COL_CARD, 12));
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> {
            // 最少字数与保留天数是**弹窗里选**，选完才异步生效 ——
            // 若在这里立刻重建，拿到的是旧值（表现就是"改完不刷新，要再点一次才变"）。
            // 所以把重建交给选择完成后的回调。
            if (which == 4) {
                chooseMinChars(() -> buildSettingsBox());
            } else if (which == 5) {
                chooseRetention(() -> buildSettingsBox());
            } else {
                applySetting(which);
                buildSettingsBox();
            }
        });

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COL_FG);
        t.setTextSize(13);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        if (!value.isEmpty()) {
            TextView val = new TextView(this);
            val.setText(value);
            val.setTextColor(COL_ACCENT);
            val.setTextSize(13);
            val.setPadding(0, 0, dp(6), 0);
            row.addView(val);
        }
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(COL_DIM);
        arrow.setTextSize(16);
        row.addView(arrow);
        return row;
    }

    /** 开关被拨动（kind 与 buildSettingsBox 的顺序对应） */
    private void applySwitch(int kind, boolean on) {
        switch (kind) {
            case 0:
                Prefs.setKeepAlive(this, on);
                if (on) {
                    KeepAliveService.start(this);
                } else {
                    KeepAliveService.stop(this);
                }
                toast(on ? "保活已开启：通知栏会出现常驻通知"
                         : "保活已关闭：内存紧张时系统可能中断记录");
                break;
            case 1:
                Prefs.setFocusOnly(this, on);
                toast(on ? "只记正在输入的框（推荐）" : "所有可编辑框都会被记录");
                break;
            case 2:
                Prefs.setIgnoreDeletions(this, on);
                toast(on ? "删字不再新建记录" : "删除也会被记录");
                break;
            case 3:
                Prefs.setSkipIme(this, on);
                toast(on ? "已排除输入法键盘事件" : "已包含输入法键盘事件");
                break;
            case 4:
                Prefs.setKeepEmpty(this, on);
                toast(on ? "文本被清空也会记录一条" : "文本清空不记录");
                break;
            case 5:
                Prefs.setPolling(this, on);
                toast("轮询兜底已" + (on ? "开启" : "关闭") + "（下次生效）");
                break;
            default:
                Prefs.setDebug(this, on);
                if (on) {
                    TypelogService.DIAG.clear();
                }
                toast("诊断日志已" + (on ? "开启" : "关闭"));
                break;
        }
    }


    /** 卡片：加粗标题 + 正文 */
    private TextView cardView(String title, String body) {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTextColor(COL_FG);
        t.setBackground(rounded(COL_CARD, 12));
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setLineSpacing(dp(4), 1f);
        t.setTextIsSelectable(true);
        setCardText(t, title, body);
        return t;
    }

    private void setCardText(TextView v, String title, String body) {
        android.text.SpannableString ss = new android.text.SpannableString(title + "\n" + body);
        ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, title.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(ss);
    }

    /** 刷新第三页：实时预览与状态是内存数据（快），统计与诊断要读盘（后台） */
    private void refreshTools() {
        if (statusView == null) {
            return;
        }
        String app = TextUtils.isEmpty(TypelogService.lastAppLabel)
                ? TypelogService.lastApp : TypelogService.lastAppLabel;
        if (TextUtils.isEmpty(TypelogService.lastTs)) {
            setCardText(liveView, "实时预览（最近一次输入）",
                    "还没有捕获到输入。开启服务后，去任意 App 打几个字试试。");
        } else {
            setCardText(liveView, "实时预览（最近一次输入）",
                    "时间：" + TypelogService.lastTs + "\n"
                    + "应用：" + (TextUtils.isEmpty(app) ? "（未知）" : app) + "\n"
                    + "输入框：" + TypelogService.lastField + "\n"
                    + "当前字数：" + TypelogService.lastText.length() + "\n"
                    + "—— 最近一次内容 ——\n" + tail(TypelogService.lastText, 200));
        }
        setCardText(statusView, "采集状态",
                (isServiceEnabled() ? "● 已开启，正在记录" : "○ 未开启")
                + "\n本次会话落盘 " + TypelogService.written + " 条（服务重启会归零，历史不会丢）"
                + "\n收到事件 " + TypelogService.evAll
                + "（文本变化 " + TypelogService.evText + "）"
                + "\n取到文本 " + TypelogService.evCaptured
                + "　兜底找回 " + TypelogService.evTraverseHit
                + "　跳过密码框 " + TypelogService.skippedPassword);

        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            String today = DAY.format(new Date());
            Map<String, String> labels = store.labels(today);
            java.util.List<LogStore.Row> rows = store.readDay(today, 0);
            long bytes = store.totalBytes();

            int total = 0;
            int compCount = 0;
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, Integer> chars = new LinkedHashMap<>();
            for (LogStore.Row r : rows) {
                if (r.text.isEmpty()) {
                    continue;
                }
                total++;
                if (r.comp) {
                    compCount++;
                }
                Integer c = counts.get(r.app);
                counts.put(r.app, c == null ? 1 : c + 1);
                Integer mx = chars.get(r.app);
                chars.put(r.app, mx == null ? r.chars : Math.max(mx, r.chars));
            }

            StringBuilder st = new StringBuilder();
            st.append("今天落盘版本数：").append(total).append("\n");
            st.append("其中输入法未上屏：").append(compCount).append(" 条\n");
            st.append("占用空间：")
              .append(bytes < 1024 ? bytes + " 字节" : (bytes / 1024) + " KB").append("\n");
            if (!rows.isEmpty()) {
                LogStore.Row last = rows.get(rows.size() - 1);
                st.append("最后一条（从磁盘读回）：\n")
                  .append(last.ts.replace("T", " ").substring(0, 19)).append("\n")
                  .append(tail(last.text, 120)).append("\n");
            }
            st.append("保存位置：应用私有目录（其他 App 读不到）");

            StringBuilder ap = new StringBuilder();
            if (counts.isEmpty()) {
                ap.append("今天还没有记录。");
            } else {
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    ap.append("· ").append(name(labels, e.getKey()))
                      .append("（").append(e.getKey()).append("）  ")
                      .append(e.getValue()).append(" 条，最长 ")
                      .append(chars.get(e.getKey())).append(" 字\n");
                }
            }

            final String stFinal = st.toString();
            final String apFinal = ap.toString().trim();
            ui.post(() -> {
                setCardText(statsView, "统计（今天）", stFinal);
                setCardText(appsView, "今天记录过的 App", apFinal);
            });
        }, "typelog-tools").start();

        // 诊断区的填充放在 buildDiagBox() 里：它决定"是否展开、是否已创建视图"，
        // 放在这里会出现"没展开也去读盘"以及"展开了却没人填"两种毛病。
    }

    /**
     * 诊断正文。集中在这里生成，页面与弹窗共用 ——
     * 分两处写就一定会走样（本项目已经因此丢过一次内容）。
     */
    private StringBuilder diagText() {
        LogStore store = new LogStore(getFilesDir(), 0);
        String today = DAY.format(new Date());
        Map<String, String> labels = store.labels(today);

        StringBuilder sb = new StringBuilder();
        sb.append("收到的无障碍事件总数：").append(TypelogService.evAll).append("\n");
        sb.append("其中文本变化事件：").append(TypelogService.evText).append("\n");
        sb.append("取到文本并进入记录：").append(TypelogService.evCaptured).append("\n");
        sb.append("事件里节点为空(靠兜底找回)：").append(TypelogService.evSourceNull)
          .append(" / 兜底成功 ").append(TypelogService.evTraverseHit).append("\n");
        sb.append("宽松判据兜底命中：").append(TypelogService.evRelaxedHit).append("\n");
        sb.append("节点不是输入框：").append(TypelogService.evNotEditable).append("\n");
        sb.append("跳过系统UI/不可记录包：").append(TypelogService.skippedSelf).append("\n");
        sb.append("跳过输入法键盘自身事件：").append(TypelogService.skippedIme).append("\n");
        sb.append("跳过删除操作(按设置)：").append(TypelogService.skippedDelete).append("\n");
        sb.append("跳过未聚焦的框：").append(TypelogService.skippedNoFocus).append("\n");
        sb.append("跳过占位提示/单字碎片：").append(TypelogService.skippedNoise).append("\n");
        sb.append("跳过(设置里排除的)：").append(TypelogService.skippedIgnored).append("\n");
        sb.append("跳过(其它原因)：").append(TypelogService.skippedOther).append("\n");
        sb.append("跳过密码框：").append(TypelogService.skippedPassword).append("\n");
        sb.append("检测到点击「发送/搜索/发布」：").append(TypelogService.sendBoundaries)
          .append(" 次（消息分段依据）\n");
        sb.append("写入失败：").append(TypelogService.errors).append("\n");
        sb.append("本次会话落盘：").append(TypelogService.written).append(" 条\n");
        sb.append("最近一次错误的来源包：")
          .append(TextUtils.isEmpty(TypelogService.lastSourcePkg) ? "（无）"
                  : TypelogService.lastSourcePkg).append("\n");
        sb.append("最近一次错误：")
          .append(TextUtils.isEmpty(TypelogService.lastError) ? "（无）"
                  : TypelogService.lastError).append("\n");
        sb.append("存储层：写入成功 ").append(LogStore.diagWrittenRows)
          .append(" 行，累计 ").append(LogStore.diagByteCount).append(" 字节")
          .append("，跳过 ").append(LogStore.diagSkippedRows).append(" 行\n");
        sb.append("存储层最近一次写入：")
          .append(LogStore.diagLastWriteAt == 0 ? "（本次进程还没写过）"
                  : new Date(LogStore.diagLastWriteAt).toString()).append("\n");
        sb.append("存储层最近写入的行（前 120 字）：")
          .append(TextUtils.isEmpty(LogStore.diagLastLine) ? "（无）"
                  : tail(LogStore.diagLastLine, 120)).append("\n");
        sb.append("存储层最近一次错误：")
          .append(TextUtils.isEmpty(LogStore.diagLastError) ? "（无）"
                  : LogStore.diagLastError).append("\n");
        sb.append("最近一次清空前的备份：")
          .append(TextUtils.isEmpty(LogStore.diagLastBackupPath) ? "（本次进程还没清空过）"
                  : LogStore.diagLastBackupPath).append("\n");

        sb.append("\n哪些应用发过事件（次数）：\n");
        java.util.List<Map.Entry<String, Integer>> es =
                new ArrayList<>(TypelogService.ALL_EVENT_PKGS.entrySet());
        java.util.Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
        if (es.isEmpty()) {
            sb.append("（一次都没收到）\n");
        }
        for (int i = 0; i < es.size() && i < 12; i++) {
            Map.Entry<String, Integer> e = es.get(i);
            Integer t = TypelogService.TEXT_EVENT_PKGS.get(e.getKey());
            sb.append("· ").append(name(labels, e.getKey()))
              .append("  [").append(e.getKey()).append("]  总 ").append(e.getValue())
              .append(" 次，其中文本变化 ").append(t == null ? 0 : t).append(" 次\n");
        }

        Integer wx = TypelogService.ALL_EVENT_PKGS.get("com.tencent.mm");
        sb.append("\n微信(com.tencent.mm)：");
        if (wx == null) {
            sb.append("一次事件都没收到 ← 系统层面没放行");
        } else {
            Integer wxt = TypelogService.TEXT_EVENT_PKGS.get("com.tencent.mm");
            sb.append("收到 ").append(wx).append(" 次事件，其中文本变化 ")
              .append(wxt == null ? 0 : wxt).append(" 次");
        }

        sb.append("\n\n最近一次扫描情况：\n")
          .append(TextUtils.isEmpty(TypelogService.lastScanInfo) ? "（还没有扫描失败过）"
                  : TypelogService.lastScanInfo).append("\n");

        sb.append("\n磁盘上的原始文件（绕开所有缓存，最硬的证据）：\n");
        for (String line : store.fileInventory(today, 40)) {
            sb.append("· ").append(line).append("\n");
        }

        sb.append("\n最近 25 条原始事件（需在设置里开诊断日志）：\n");
        java.util.List<String> diag = TypelogService.DIAG;
        synchronized (diag) {
            if (diag.isEmpty()) {
                sb.append("（诊断日志未开启）\n");
            } else {
                int from = Math.max(0, diag.size() - 25);
                for (int i = from; i < diag.size(); i++) {
                    sb.append("· ").append(diag.get(i)).append("\n");
                }
            }
        }

        if (TypelogService.skippedNoFocus > 0 && TypelogService.evCaptured == 0) {
            sb.append("\n⚠ 已跳过 ").append(TypelogService.skippedNoFocus)
              .append(" 个未聚焦的框，且一条都没记到。\n")
              .append("   焦点过滤可能对本机过严，把「只记录有焦点的输入框」关掉试试。\n");
        }
        return sb;
    }

    /** 设置项文案：页面与设置弹窗共用一份，避免两处不一致 */
    private String[] settingItems() {
        return new String[]{
                "后台保活：" + (Prefs.keepAlive(this) ? "开启（通知栏常驻）" : "关闭"),
                "只记录有焦点的输入框：" + (Prefs.focusOnly(this) ? "开启" : "关闭"),
                "忽略删除操作：" + (Prefs.ignoreDeletions(this)
                        ? "开启（删字不新建记录）" : "关闭（删除也记录）"),
                "排除输入法键盘事件：" + (Prefs.skipIme(this) ? "开启" : "关闭"),
                "最少记录字数（当前 " + Prefs.minChars(this) + " 字）",
                "保留天数（当前 " + Prefs.retentionDays(this) + " 天）",
                "轮询兜底：" + (Prefs.polling(this) ? "开启" : "关闭"),
                "诊断日志：" + (Prefs.debug(this) ? "开启" : "关闭"),
                "记录文本被清空：" + (Prefs.keepEmpty(this) ? "记录" : "不记录"),
                "不记录的 App…",
                "显示保存位置",
        };
    }

    /** 应用某一项设置（页面与设置弹窗共用） */
    private void applySetting(int which) {
        switch (which) {
            case 0:
                boolean kaOn = !Prefs.keepAlive(this);
                Prefs.setKeepAlive(this, kaOn);
                if (kaOn) {
                    KeepAliveService.start(this);
                } else {
                    KeepAliveService.stop(this);
                }
                toast(kaOn ? "已开启保活：通知栏会出现一条常驻通知，进程不易被系统回收"
                           : "已关闭保活：内存紧张时系统可能中断记录");
                break;
            case 1:
                Prefs.setFocusOnly(this, !Prefs.focusOnly(this));
                toast(Prefs.focusOnly(this) ? "只记正在输入的框（推荐）"
                                            : "所有可编辑框都会被记录");
                break;
            case 2:
                Prefs.setIgnoreDeletions(this, !Prefs.ignoreDeletions(this));
                toast(Prefs.ignoreDeletions(this) ? "删字不再新建记录" : "删除也会被记录");
                break;
            case 3:
                Prefs.setSkipIme(this, !Prefs.skipIme(this));
                toast("已" + (Prefs.skipIme(this) ? "排除" : "包含") + "输入法键盘事件");
                break;
            case 4:
                chooseMinChars();
                break;
            case 5:
                chooseRetention();
                break;
            case 6:
                Prefs.setPolling(this, !Prefs.polling(this));
                toast("轮询兜底已" + (Prefs.polling(this) ? "开启" : "关闭") + "（下次生效）");
                break;
            case 7:
                Prefs.setDebug(this, !Prefs.debug(this));
                if (Prefs.debug(this)) {
                    TypelogService.DIAG.clear();
                }
                toast("诊断日志已" + (Prefs.debug(this) ? "开启" : "关闭"));
                break;
            case 8:
                Prefs.setKeepEmpty(this, !Prefs.keepEmpty(this));
                toast("已" + (Prefs.keepEmpty(this) ? "记录" : "忽略") + "清空事件");
                break;
            case 9:
                chooseIgnored();
                break;
            default:
                toast("应用私有目录：/data/data/" + getPackageName() + "/files/logs/");
                break;
        }
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
    private void doExportToDownloads() {
        toast("正在导出到「下载/DraftGuard」…");
        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            final Uri uri = Exporter.toPublicDownloads(this, store.root());
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

    private void doExport() {
        toast("正在打包…");
        new Thread(() -> {
            File zip = null;
            String err = null;
            try {
                LogStore store = new LogStore(getFilesDir(), 0);
                zip = Exporter.toCache(this, store.root());
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

    private void chooseMinChars() {
        chooseMinChars(null);
    }

    /** @param after 选完之后的回调（用于刷新设置列表里的当前值） */
    private void chooseMinChars(final Runnable after) {
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
    private void confirmClearAll() {
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
                            Exporter.backupToPublicDownloads(this, s2.root(), "preclear");
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
    private final class RecordAdapter extends android.widget.BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
            } else {
                row = buildRow();
            }
            bindRow(row, items.get(position));
            return row;
        }

        /** 行结构：图标 | （应用名 + 元信息） / 正文 */
        private LinearLayout buildRow() {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(rounded(COL_CARD, 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), dp(4), dp(12), dp(4));
            row.setLayoutParams(lp);
            row.setPadding(dp(12), dp(10), dp(12), dp(12));

            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(MainActivity.this);
            int sz = dp(30);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
            ilp.rightMargin = dp(10);
            icon.setLayoutParams(ilp);
            icon.setTag("icon");
            head.addView(icon);

            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(MainActivity.this);
            name.setTextColor(COL_FG);
            name.setTextSize(14);
            name.setTag("name");
            col.addView(name);

            TextView meta = new TextView(MainActivity.this);
            meta.setTextColor(COL_DIM);
            meta.setTextSize(11);
            meta.setPadding(0, dp(2), 0, 0);
            meta.setTag("meta");
            col.addView(meta);

            head.addView(col);
            row.addView(head);

            TextView body = new TextView(MainActivity.this);
            body.setTextColor(COL_FG);
            body.setTextSize(14);
            body.setPadding(0, dp(8), 0, 0);
            body.setLineSpacing(dp(3), 1f);
            body.setTag("body");
            row.addView(body);
            return row;
        }

        private void bindRow(LinearLayout row, Object item) {
            ImageView icon = row.findViewWithTag("icon");
            TextView name = row.findViewWithTag("name");
            TextView meta = row.findViewWithTag("meta");
            TextView body = row.findViewWithTag("body");

            String app;
            String head;
            String text;
            if (item instanceof Burst) {
                Burst b = (Burst) item;
                app = b.app;
                StringBuilder h = new StringBuilder();
                // 跨天查看时把日期带上，否则昨天的记录只显示时间会让人误会
                String today = DAY.format(new Date());
                if (!b.firstTs.startsWith(today)) {
                    h.append(b.firstTs, 5, 10).append(" ");
                }
                h.append(b.firstTs, 11, 16);
                if (!b.firstTs.substring(11, 16).equals(b.lastTs.substring(11, 16))) {
                    h.append("–").append(b.lastTs, 11, 16);
                }
                h.append("　").append(b.text.length()).append(" 字");
                if (b.versions > 1) {
                    h.append("　合并 ").append(b.versions).append(" 版");
                }
                if (b.comp) {
                    h.append("　未上屏");
                }
                head = h.toString();
                text = b.text;
            } else {
                LogStore.Row r = (LogStore.Row) item;
                app = r.app;
                head = r.ts.substring(11, 19) + "　" + r.chars + " 字"
                        + (r.comp ? "　未上屏" : "");
                text = r.text;
            }

            name.setText(labelOf(app));
            meta.setText(head);
            body.setText(text);

            android.graphics.drawable.Drawable d = iconCache.get(app);
            if (d != null) {
                icon.setImageDrawable(d);
            } else {
                icon.setImageDrawable(letterIcon(labelOf(app), app));
                requestIcon(app);
            }
        }
    }

    private final java.util.Set<String> iconRequested = new java.util.HashSet<>();

    /** 列表里第一次见到某个应用时，后台取一次它的图标 */
    private void requestIcon(final String pkg) {
        if (iconRequested.contains(pkg)) {
            return;
        }
        iconRequested.add(pkg);
        new Thread(() -> {
            android.graphics.drawable.Drawable d = null;
            try {
                d = getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable ignored) {
            }
            synchronized (iconCache) {
                iconCache.put(pkg, d);
            }
            ui.post(() -> {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }, "typelog-icon").start();
    }

    private final java.util.Map<String, String> labelCache = new java.util.HashMap<>();

    private String labelOf(String pkg) {
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
