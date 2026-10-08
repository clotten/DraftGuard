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

    private LinearLayout root;
    private TextView statusView;
    private TextView liveView;
    private TextView statsView;
    private TextView appsView;
    private TextView resultView;
    private EditText searchBox;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
    private final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", Locale.US);

    private final BroadcastReceiver statsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLive();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        // 打开应用时确保保活服务在跑（用户可能在设置里开过又关了）
        if (Prefs.keepAlive(this)) {
            KeepAliveService.start(this);
        } else {
            KeepAliveService.stop(this);
        }
        buildUi();
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
        refreshAll();
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

    private void buildUi() {
        statusView = card("采集状态", "正在检测…");
        root.addView(statusView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        root.addView(row);

        Button btnToggle = button("去开启 / 检查服务");
        btnToggle.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("在列表里找到「字迹留存」，打开开关");
            } catch (Throwable t) {
                toast("打不开无障碍设置，请手动到 设置 → 无障碍 里打开");
            }
        });
        row.addView(btnToggle);

        Button btnRefresh = button("刷新");
        btnRefresh.setOnClickListener(v -> refreshAll());
        row.addView(btnRefresh);

        liveView = card("实时预览（最近一次输入）", "还没有捕获到输入。开启服务后，去任意 App 打几个字试试。");
        root.addView(liveView);

        statsView = card("统计", "—");
        root.addView(statsView);

        appsView = card("今天记录过的 App", "—");
        root.addView(appsView);

        // 搜索
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(0, dp(10), 0, 0);
        root.addView(searchRow);

        searchBox = new EditText(this);
        searchBox.setHint("搜索记录过的文字…");
        searchBox.setTextColor(Color.parseColor("#E8ECF3"));
        searchBox.setHintTextColor(Color.parseColor("#8B95A7"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchBox.setLayoutParams(lp);
        searchRow.addView(searchBox);

        Button btnSearch = button("搜");
        btnSearch.setOnClickListener(v -> doSearch());
        searchRow.addView(btnSearch);

        resultView = card("搜索结果", "输入关键词后点「搜」，会从今天往前找。");
        root.addView(resultView);

        // 导出
        LinearLayout expRow = new LinearLayout(this);
        expRow.setOrientation(LinearLayout.HORIZONTAL);
        expRow.setPadding(0, dp(10), 0, dp(20));
        root.addView(expRow);

        Button btnExportPub = button("导出到下载目录");
        btnExportPub.setOnClickListener(v -> doExportToDownloads());
        expRow.addView(btnExportPub);

        Button btnExport = button("分享 zip");
        btnExport.setOnClickListener(v -> doExport());
        expRow.addView(btnExport);

        Button btnOpen = button("看今天全部记录");
        btnOpen.setOnClickListener(v -> showToday());
        expRow.addView(btnOpen);

        LinearLayout setRow = new LinearLayout(this);
        setRow.setOrientation(LinearLayout.HORIZONTAL);
        setRow.setPadding(0, 0, 0, dp(24));
        root.addView(setRow);

        Button btnDiag = button("诊断");
        btnDiag.setOnClickListener(v -> showDiag());
        setRow.addView(btnDiag);

        Button btnClear = button("清除全部记录");
        btnClear.setOnClickListener(v -> confirmClearAll());
        setRow.addView(btnClear);
        Button btnSettings = button("设置");
        btnSettings.setOnClickListener(v -> showSettings());
        setRow.addView(btnSettings);
    }

    /** 诊断面板：一眼看出"断在哪一环" */
    private void showDiag() {
        setCard(resultView, "诊断", "读取中…");
        new Thread(() -> {
            final LogStore store = new LogStore(getFilesDir(), 0);
            final String today = DAY.format(new Date());
            final Map<String, String> labels = store.labels(today);

            StringBuilder sb = new StringBuilder();
            sb.append("收到的无障碍事件总数：").append(TypelogService.evAll).append("\n");
            sb.append("其中文本变化事件：").append(TypelogService.evText).append("\n");
            sb.append("取到文本并进入记录：").append(TypelogService.evCaptured).append("\n");
            sb.append("事件里节点为空(靠兜底找回)：").append(TypelogService.evSourceNull)
              .append(" / 兜底成功 ").append(TypelogService.evTraverseHit).append("\n");
            sb.append("节点不是输入框：").append(TypelogService.evNotEditable).append("\n");
            sb.append("跳过系统UI/不可记录包：").append(TypelogService.skippedSelf).append("\n");
            sb.append("跳过输入法键盘自身事件：").append(TypelogService.skippedIme).append("\n");
            sb.append("跳过删除操作(按设置)：").append(TypelogService.skippedDelete).append("\n");
            sb.append("跳过未聚焦的框：").append(TypelogService.skippedNoFocus).append("\n");
            sb.append("跳过占位提示/单字碎片：").append(TypelogService.skippedNoise).append("\n");
            sb.append("跳过(设置里排除的)：").append(TypelogService.skippedIgnored).append("\n");
            sb.append("跳过密码框：").append(TypelogService.skippedPassword).append("\n");
            sb.append("写入失败：").append(TypelogService.errors).append("\n\n");

            sb.append("哪些应用发过事件（次数）：\n");
            boolean any = false;
            synchronized (TypelogService.ALL_EVENT_PKGS) {
                List<Map.Entry<String, Integer>> es =
                        new ArrayList<>(TypelogService.ALL_EVENT_PKGS.entrySet());
                Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
                for (int i = 0; i < es.size() && i < 12; i++) {
                    Map.Entry<String, Integer> e = es.get(i);
                    String n = labels.get(e.getKey());
                    Integer t = TypelogService.TEXT_EVENT_PKGS.get(e.getKey());
                    sb.append("· ").append(TextUtils.isEmpty(n) ? e.getKey() : n)
                      .append("  [").append(e.getKey()).append("]  总 ").append(e.getValue())
                      .append(" 次，其中文本变化 ").append(t == null ? 0 : t).append(" 次\n");
                    any = true;
                }
            }
            if (!any) {
                sb.append("（一次都没收到）\n");
            }

            // 微信专项判断：这是最常用来验证的场景
            Integer wx = TypelogService.ALL_EVENT_PKGS.get("com.tencent.mm");
            sb.append("\n微信(com.tencent.mm)：");
            if (wx == null) {
                sb.append("一次事件都没收到 ← 系统层面没放行，见下方解决步骤");
            } else {
                Integer wxt = TypelogService.TEXT_EVENT_PKGS.get("com.tencent.mm");
                sb.append("收到 ").append(wx).append(" 次事件，其中文本变化 ")
                  .append(wxt == null ? 0 : wxt).append(" 次");
                if (wxt == null) {
                    sb.append(" ← 微信不发文本变化事件，应走轮询兜底（确认设置里轮询是开的）");
                }
            }

            sb.append("\n\n最近一次扫描情况：\n")
              .append(TextUtils.isEmpty(TypelogService.lastScanInfo)
                      ? "（还没有扫描失败过）" : TypelogService.lastScanInfo).append("\n\n");

            sb.append("磁盘上的原始文件（绕开所有缓存，最硬的证据）：\n");
            for (String line : store.fileInventory(today, 60)) {
                sb.append("· ").append(line).append("\n");
            }
            sb.append("\n");
            sb.append("最近 25 条原始事件（需在设置里开诊断日志）：\n");
            java.util.List<String> diag = TypelogService.DIAG;
            synchronized (diag) {
                int from = Math.max(0, diag.size() - 25);
                for (int i = from; i < diag.size(); i++) {
                    sb.append("· ").append(diag.get(i)).append("\n");
                }
                if (diag.isEmpty()) {
                    sb.append("（诊断日志未开启）\n");
                }
            }

            // 主动报警：焦点过滤如果过严，表现就是"跳过数一直涨、却一条都没记到"
            if (TypelogService.skippedNoFocus >= 20 && TypelogService.evCaptured == 0) {
                sb.append("\n⚠ 注意：已跳过 ").append(TypelogService.skippedNoFocus)
                  .append(" 个未聚焦的框，但一条都没记到 ——");
                sb.append("\n   焦点过滤可能对本机过严。到「设置」里把");
                sb.append("\n   「只记录有焦点的输入框」关掉试试，能记到就说明是判断失灵。\n");
            }
            sb.append("\n怎么读：\n");
            sb.append("· 事件总数一直是 0 → 无障碍服务没真正启用\n");
            sb.append("· 只有本应用/输入法的事件，别的 App 一条都没有 → 系统拦截了本服务读取其他应用\n");
            sb.append("　（MIUI/HyperOS：应用信息里打开「自启动」「后台弹出界面」，"
                    + "或到无障碍页面找「已安装的服务/更多设置」放行）\n");
            sb.append("· 有文本变化但\"节点为空\"在涨 → 靠兜底找回，正常\n");
            sb.append("· 取到文本在涨但预览没变 → 界面刷新问题，不是采集问题\n");

            final String text = sb.toString().trim();
            ui.post(() -> setCard(resultView, "诊断", text));
        }, "typelog-diag").start();
    }

    private void showSettings() {
        String[] items = {
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
        new android.app.AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(items, (d, which) -> {
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
                            toast(Prefs.focusOnly(this)
                                    ? "只记正在输入的框（推荐）"
                                    : "所有可编辑框都会被记录");
                            break;
                        case 2:
                            Prefs.setIgnoreDeletions(this, !Prefs.ignoreDeletions(this));
                            toast(Prefs.ignoreDeletions(this)
                                    ? "删字不再新建记录（只保留新增文字的那些版本）"
                                    : "删除也会被记录");
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
                            toast("轮询兜底已" + (Prefs.polling(this) ? "开启" : "关闭")
                                    + "（下次生效）");
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
                        case 10:
                            toast("应用私有目录：/data/data/" + getPackageName() + "/files/logs/");
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void chooseRetention() {
        final int[] opts = {7, 30, 90, 365};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " 天";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("日志保留天数")
                .setItems(labels, (d, which) -> {
                    Prefs.setRetentionDays(this, opts[which]);
                    toast("已设为 " + opts[which] + " 天（重启服务后清理）");
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
    private void setCard(TextView v, String title, String body) {
        android.text.SpannableString ss = new android.text.SpannableString(title + "\n" + body);
        ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, title.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(ss);
    }

    private TextView card(String title, String body) {
        TextView t = new TextView(this);
        t.setTextColor(Color.parseColor("#E8ECF3"));
        t.setTextSize(13);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        t.setBackgroundColor(Color.parseColor("#1B1F28"));
        t.setTextIsSelectable(true);
        setCard(t, title, body);
        return t;
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

    private void refreshAll() {
        refreshStatus();
        refreshLive();
        final Context ctx = this;
        new Thread(() -> {
            final LogStore store = new LogStore(getFilesDir(), 0);
            String today = DAY.format(new Date());
            List<LogStore.Row> rows = store.readDay(today, 0);
            final Map<String, Integer> counts = new LinkedHashMap<>();
            final Map<String, Integer> chars = new LinkedHashMap<>();
            int total = 0;
            int compCount = 0;
            for (LogStore.Row r : rows) {
                if (r.text.isEmpty()) {
                    continue;
                }
                Integer c = counts.get(r.app);
                counts.put(r.app, c == null ? 1 : c + 1);
                Integer mx = chars.get(r.app);
                chars.put(r.app, mx == null ? r.chars : Math.max(mx, r.chars));
                total++;
                if (r.comp) {
                    compCount++;
                }
            }
            Map<String, String> labels = store.labels(today);
            long bytes = store.totalBytes();
            final StringBuilder apps = new StringBuilder();
            if (counts.isEmpty()) {
                apps.append("今天还没有记录。");
            } else {
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    String name = labels.get(e.getKey());
                    apps.append("· ").append(TextUtils.isEmpty(name) ? e.getKey() : name)
                        .append("（").append(e.getKey()).append("）  ")
                        .append(e.getValue()).append(" 条，最长 ")
                        .append(chars.get(e.getKey())).append(" 字\n");
                }
            }
            String lastSaved = "";
            if (!rows.isEmpty()) {
                LogStore.Row last = rows.get(rows.size() - 1);
                lastSaved = "最后一条（从磁盘读回）：\n"
                        + last.ts.replace("T", " ").substring(0, 19)
                        + "  " + last.minute + "  " + last.app + "  " + last.chars + " 字\n"
                        + tail(last.text, 120) + "\n";
            }
            final String stats = "今天落盘版本数：" + total + "（这是从磁盘读出来的）\n"
                    + "其中输入法未上屏状态：" + compCount + " 条（也存了）\n"
                    + "已跳过密码框次数：" + TypelogService.skippedPassword + "\n"
                    + "写入失败：" + TypelogService.errors
                    + (TypelogService.lastError.isEmpty() ? "" : "（" + TypelogService.lastError + "）")
                    + "\n占用空间：" + (bytes / 1024) + " KB\n\n"
                    + lastSaved
                    + "保存位置：应用私有目录（其他 App 读不到）";
            ui.post(() -> {
                setCard(statsView, "统计", stats);
                setCard(appsView, "今天记录过的 App", apps.toString().trim());
            });
        }, "typelog-ui").start();
    }

    private void refreshStatus() {
        boolean on = isServiceEnabled();
        String head = on ? "● 已开启，正在记录" : "○ 未开启";
        String tail = on
                ? "\n每条记录的保存时机：你每敲一下 → 350 毫秒内落盘。"
                  + "\n换 App、切后台、锁屏都不会中断。"
                : "\n点下面的按钮，到系统的无障碍列表里打开「字迹留存」。"
                  + "\n开启后本应用可以长期驻留，不会因为你划掉界面就停止。";
        setCard(statusView, "采集状态", head + tail);
    }

    private void refreshLive() {
        String app = TextUtils.isEmpty(TypelogService.lastAppLabel)
                ? TypelogService.lastApp : TypelogService.lastAppLabel;
        String body;
        if (TextUtils.isEmpty(TypelogService.lastTs)) {
            body = "还没有捕获到输入。开启服务后，去任意 App 打几个字试试。\n"
                    + "（若确定打过字仍无显示，点下面「诊断」看事件计数）";
        } else {
            body = "时间：" + TypelogService.lastTs + "\n"
                    + "应用：" + (TextUtils.isEmpty(app) ? "（未知）" : app) + "\n"
                    + "输入框：" + TypelogService.lastField + "\n"
                    + "当前字数：" + TypelogService.lastText.length() + "\n"
                    + "—— 最近一次内容 ——\n"
                    + tail(TypelogService.lastText, 200);
        }
        setCard(liveView, "实时预览（最近一次输入）", body);
        setCard(statusView, "采集状态",
                (isServiceEnabled() ? "● 已开启，正在记录" : "○ 未开启")
                + "\n累计落盘 " + TypelogService.written + " 条"
                + "　收到事件 " + TypelogService.evAll
                + "（文本变化 " + TypelogService.evText + "）"
                + "\n取到文本 " + TypelogService.evCaptured
                + "　兜底找回 " + TypelogService.evTraverseHit
                + "　跳过密码框 " + TypelogService.skippedPassword);
    }

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

    private void doSearch() {
        final String q = searchBox.getText().toString().trim();
        if (q.isEmpty()) {
            toast("先输入关键词");
            return;
        }
        setCard(resultView, "搜索结果", "搜索中…");
        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            List<LogStore.Row> hits = new ArrayList<>();
            for (String day : store.days()) {
                store.search(day, q, 60, hits);
                if (hits.size() >= 60) {
                    break;
                }
            }
            final StringBuilder sb = new StringBuilder();
            if (hits.isEmpty()) {
                sb.append("没找到包含「").append(q).append("」的记录。");
            } else {
                sb.append("找到 ").append(hits.size()).append(" 条（新的在前）\n\n");
                for (LogStore.Row r : hits) {
                    sb.append(r.ts.replace("T", " ")).append("  [").append(r.minute).append("]  ")
                      .append(r.app).append("\n")
                      .append(context(r.text, q)).append("\n\n");
                }
            }
            ui.post(() -> setCard(resultView, "搜索结果", sb.toString().trim()));
        }, "typelog-search").start();
    }

    /** 截取命中位置前后各 60 字，方便快速确认是不是要找的那段 */
    private static String context(String text, String q) {
        int i = text.indexOf(q);
        if (i < 0) {
            return tail(text, 200);
        }
        int from = Math.max(0, i - 60);
        int to = Math.min(text.length(), i + q.length() + 60);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }

    private void showToday() {
        final String today = DAY.format(new Date());
        setCard(resultView, "今天全部记录", "读取中…（按分钟分组，新的在前）");
        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            List<LogStore.Row> rows = store.readDay(today, 0);
            final StringBuilder sb = new StringBuilder();
            if (rows.isEmpty()) {
                sb.append("今天还没有记录。");
            } else {
                sb.append("共 ").append(rows.size()).append(" 条，按时间倒序：\n\n");
                int shown = 0;
                for (int i = rows.size() - 1; i >= 0 && shown < 200; i--, shown++) {
                    LogStore.Row r = rows.get(i);
                    String flag = r.comp ? "（未上屏）" : "";
                    sb.append(r.ts, 11, 19).append("  [").append(r.minute).append("] ")
                      .append(r.app).append("  ").append(r.chars).append(" 字").append(flag)
                      .append("\n").append(tail(r.text, 120)).append("\n\n");
                }
                if (rows.size() > shown) {
                    sb.append("… 还有 ").append(rows.size() - shown)
                      .append(" 条，完整内容请点「导出全部记录」\n");
                }
            }
            ui.post(() -> setCard(resultView, "今天全部记录", sb.toString().trim()));
        }, "typelog-today").start();
    }

    /** 导出到公共下载目录：文件管理器能看到，也能被 adb 拉取 */
    private void doExportToDownloads() {
        toast("正在导出到「下载/DraftGuard」…");
        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);
            final Uri uri = Exporter.toPublicDownloads(this, store.root());
            ui.post(() -> {
                if (uri == null) {
                    toast("导出失败：无法写入下载目录");
                    setCard(resultView, "导出结果",
                            "导出失败。可以改用「分享 zip」发给微信/QQ。");
                } else {
                    String path = "下载/" + Exporter.PUBLIC_SUBDIR + "/DraftGuard-" + Exporter.stamp() + ".zip";
                    toast("已导出到 " + path);
                    setCard(resultView, "导出结果",
                            "已导出到公共下载目录：\n" + path + "\n\n"
                            + "用文件管理器打开「下载 / DraftGuard」就能看到这个 zip。");
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
                    .setMessage("当前共有 " + total + " 条记录，占用约 " + (bytes / 1024) + " KB。\n\n"
                            + "清空后无法恢复，确定要删掉吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定清除", (d, w) -> {
                        toast("正在清除…");
                        new Thread(() -> {
                            LogStore s2 = new LogStore(getFilesDir(), 0);
                            s2.clearAll();
                            ui.post(() -> {
                                setCard(resultView, "清除结果",
                                        "已清除全部记录（" + total + " 条）。\n"
                                        + "之后新打的字会重新开始记录。");
                                refreshAll();
                                toast("已清除 " + total + " 条记录");
                            });
                        }, "typelog-clear").start();
                    })
                    .show());
        }, "typelog-count").start();
    }
}

