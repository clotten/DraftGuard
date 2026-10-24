package com.draftguard;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Date;
import java.util.Map;

/**
 * 「工具」页：采集状态卡片 + 实时预览 + 今日统计 + 应用清单 + 设置 + 诊断。
 *
 * <h3>为什么单独成类</h3>
 * 这一页原来挤在 {@link MainActivity} 里，与记录页、应用页的代码混在一起，
 * 而且它内部还分四层：卡片 → 折叠面板 → 开关行/选择行 → 具体设置。
 * 搬出来后结构变成一条直线：
 * <pre>
 *   build()            装配整页（下方各 buildXxx 依次挂上去）
 *   refreshTools()     刷新四张卡片的文案
 *   refreshServiceCard() / warnIfServiceStalled()   服务状态与提醒
 *   buildSettingsBox() / buildDiagBox()            两个可折叠区
 *   switchRow() / chooserRow() / applySwitch() / applySetting()   具体的设置项
 * </pre>
 *
 * <h3>对话框为什么不在这里</h3>
 * 设置里的"保留天数""不记录的 App""清空全部记录"都要弹 AlertDialog，
 * 它们的实现留在宿主上（{@code chooseRetention} 等），本类通过 {@code host.} 调用。
 * 这样搬动的是"视图装配"，不牵动那堆交互流程。
 */
final class ToolsPage {

    private static final long DIAG_REFRESH_MS = 2000;
    private static final long CONNECT_GRACE_MS = 10000;

    private final MainActivity host;
    private final LinearLayout toolsRoot;

    private TextView statusView;
    private TextView liveView;
    private TextView statsView;
    private TextView appsView;
    private TextView diagView;

    private boolean settingsExpanded = false;
    private LinearLayout settingsBox;
    private TextView settingsHeader;

    private boolean diagExpanded = false;
    private LinearLayout diagBox;
    private TextView diagHeader;

    private int svcState = ServiceState.OFF;
    private String lastStateProbe = "";
    private boolean skippedRefresh;
    private long lastDiagRefresh;
    private long lastStalledWarnAt;

    ToolsPage(MainActivity host, LinearLayout toolsRoot) {
        this.host = host;
        this.toolsRoot = toolsRoot;
    }

    /** 宿主用它把状态探测结果接过去（诊断面板显示判定依据） */
    String lastStateProbe() {
        return lastStateProbe;
    }

    int svcState() {
        return svcState;
    }

    /** 第三页：四张状态卡片 + 设置 + 诊断。
     *
     * 这些内容原来挤在记录页顶部（要滚四屏才看到记录），后来收进弹窗，
     * 但弹窗看不全、也不方便与设置对照，于是独立成一页。
     */
    void build() {
        toolsRoot.removeAllViews();

        statusView = cardView("采集状态", "正在检测…");
        toolsRoot.addView(statusView);
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, host.dp(8), 0, host.dp(8));
        Button btnOpen = host.button("打开系统无障碍设置");
        btnOpen.setOnClickListener(v -> openAccessibilitySettings());
        row.addView(btnOpen);
        Button btnRefresh = host.button("刷新");
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
        settingsBox = new LinearLayout(host);
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
        diagBox = new LinearLayout(host);
        diagBox.setOrientation(LinearLayout.VERTICAL);
        toolsRoot.addView(diagBox);
        buildDiagBox();
    }


    TextView sectionTitle(String text) {
        TextView t = new TextView(host);
        t.setText(text);
        t.setTextColor(MainActivity.COL_FG);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(host.dp(4), host.dp(18), 0, host.dp(8));
        return t;
    }


    /** 只刷新诊断卡片内容（不重建整个区块，避免闪烁与滚动跳动） */
    void refreshDiagBox() {
        if (!diagExpanded) {
            return;
        }
        if (diagView == null) {
            buildDiagBox();
            return;
        }
        new Thread(() -> {
            final String d = host.diagText().toString();
            host.ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag-refresh").start();
    }


    /** 更新折叠标题的箭头 */
    void updateHeader(TextView t, String title, boolean expanded) {
        if (t != null) {
            t.setText((expanded ? "▾ " : "▸ ") + title);
        }
    }


    /** 可点击的分节标题（带展开/收起箭头） */
    TextView sectionHeader(String title, boolean expanded, View.OnClickListener click) {
        TextView t = new TextView(host);
        t.setText((expanded ? "▾ " : "▸ ") + title);
        t.setTextColor(MainActivity.COL_FG);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(host.dp(4), host.dp(18), host.dp(4), host.dp(8));
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }


    /** 一行提示（收起状态下用） */
    TextView collapsedHint(String text) {
        TextView t = new TextView(host);
        t.setText(text);
        t.setTextColor(MainActivity.COL_DIM);
        t.setTextSize(12);
        t.setPadding(host.dp(4), 0, host.dp(4), host.dp(4));
        return t;
    }


    /**
     * 只重建设置区，不重建整页 ——
     * 否则一展开就跳回页顶，反而更难用。
     */
    void buildSettingsBox() {
        if (settingsBox == null) {
            return;
        }
        settingsBox.removeAllViews();
        if (!settingsExpanded) {
            settingsBox.addView(collapsedHint("已收起（共 11 项，点上面的「设置」展开）"));
            return;
        }
        // 布尔项：拨动开关
        settingsBox.addView(switchRow("后台保活（通知栏常驻）", Prefs.keepAlive(host), 0));
        settingsBox.addView(switchRow("只记录有焦点的输入框", Prefs.focusOnly(host), 1));
        settingsBox.addView(switchRow("忽略删除操作", Prefs.ignoreDeletions(host), 2));
        settingsBox.addView(switchRow("排除输入法键盘事件", Prefs.skipIme(host), 3));
        settingsBox.addView(switchRow("记录文本被清空", Prefs.keepEmpty(host), 4));
        settingsBox.addView(switchRow("轮询兜底", Prefs.polling(host), 5));
        settingsBox.addView(switchRow("诊断日志", Prefs.debug(host), 6));
        // 需要选值的项：保持"点开选择"
        settingsBox.addView(chooserRow("最少记录字数", Prefs.minChars(host) + " 字", 4));
        settingsBox.addView(chooserRow("保留天数", Prefs.retentionDays(host) + " 天", 5));
        settingsBox.addView(chooserRow("不记录的 App", "", 9));
        settingsBox.addView(chooserRow("保存位置", "", 10));
    }


    /** 只重建诊断区 */
    void buildDiagBox() {
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
        refreshDiagBox();
    }


    /** 一行开关：左标签，右 Switch */
    View switchRow(String label, boolean value, final int kind) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(host.rounded(MainActivity.COL_CARD, 12));
        row.setPadding(host.dp(14), host.dp(6), host.dp(8), host.dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = host.dp(8);
        row.setLayoutParams(lp);

        TextView t = new TextView(host);
        t.setText(label);
        t.setTextColor(MainActivity.COL_FG);
        t.setTextSize(13);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        android.widget.Switch sw = new android.widget.Switch(host);
        sw.setChecked(value);
        sw.setShowText(false);
        sw.setOnCheckedChangeListener((btn, checked) -> applySwitch(kind, checked));
        row.addView(sw);
        return row;
    }


    /** 一行"点开选择"：左标签，右当前值 + 箭头 */
    View chooserRow(String label, String value, final int which) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(host.rounded(MainActivity.COL_CARD, 12));
        row.setPadding(host.dp(14), host.dp(14), host.dp(14), host.dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = host.dp(8);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> {
            // 最少字数与保留天数是**弹窗里选**，选完才异步生效 ——
            // 若在这里立刻重建，拿到的是旧值（表现就是"改完不刷新，要再点一次才变"）。
            // 所以把重建交给选择完成后的回调。
            if (which == 4) {
                host.chooseMinChars(() -> buildSettingsBox());
            } else if (which == 5) {
                host.chooseRetention(() -> buildSettingsBox());
            } else {
                applySetting(which);
                buildSettingsBox();
            }
        });

        TextView t = new TextView(host);
        t.setText(label);
        t.setTextColor(MainActivity.COL_FG);
        t.setTextSize(13);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        if (!value.isEmpty()) {
            TextView val = new TextView(host);
            val.setText(value);
            val.setTextColor(MainActivity.COL_ACCENT);
            val.setTextSize(13);
            val.setPadding(0, 0, host.dp(6), 0);
            row.addView(val);
        }
        TextView arrow = new TextView(host);
        arrow.setText("›");
        arrow.setTextColor(MainActivity.COL_DIM);
        arrow.setTextSize(16);
        row.addView(arrow);
        return row;
    }


    /** 开关被拨动（kind 与 buildSettingsBox 的顺序对应） */
    void applySwitch(int kind, boolean on) {
        switch (kind) {
            case 0:
                Prefs.setKeepAlive(host, on);
                if (on) {
                    KeepAliveService.start(host);
                } else {
                    KeepAliveService.stop(host);
                }
                host.toast(on ? "保活已开启：通知栏会出现常驻通知"
                         : "保活已关闭：内存紧张时系统可能中断记录");
                break;
            case 1:
                Prefs.setFocusOnly(host, on);
                host.toast(on ? "只记正在输入的框（推荐）" : "所有可编辑框都会被记录");
                break;
            case 2:
                Prefs.setIgnoreDeletions(host, on);
                host.toast(on ? "删字不再新建记录" : "删除也会被记录");
                break;
            case 3:
                Prefs.setSkipIme(host, on);
                host.toast(on ? "已排除输入法键盘事件" : "已包含输入法键盘事件");
                break;
            case 4:
                Prefs.setKeepEmpty(host, on);
                host.toast(on ? "文本被清空也会记录一条" : "文本清空不记录");
                break;
            case 5:
                Prefs.setPolling(host, on);
                host.toast("轮询兜底已" + (on ? "开启" : "关闭") + "（下次生效）");
                break;
            default:
                Prefs.setDebug(host, on);
                if (on) {
                    TypelogService.DIAG.clear();
                }
                host.toast("诊断日志已" + (on ? "开启" : "关闭"));
                break;
        }
    }



    /** 卡片：加粗标题 + 正文 */
    TextView cardView(String title, String body) {
        TextView t = new TextView(host);
        t.setTextSize(13);
        t.setTextColor(MainActivity.COL_FG);
        t.setBackground(host.rounded(MainActivity.COL_CARD, 12));
        t.setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12));
        t.setLineSpacing(host.dp(4), 1f);
        t.setTextIsSelectable(true);
        setCardText(t, title, body);
        return t;
    }


    void setCardText(TextView v, String title, String body) {
        android.text.SpannableString ss = new android.text.SpannableString(title + "\n" + body);
        ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, title.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(ss);
    }


    /** 刷新第三页：实时预览与状态是内存数据（快），统计与诊断要读盘（后台） */
    void refreshTools() {
        if (statusView == null) {
            return;
        }
        // 文案全部由 ToolsText 生成（纯函数，可单测）；这里只负责读库和贴到卡片上
        ToolsText.Live live = new ToolsText.Live();
        live.ts = TypelogService.lastTs;
        live.app = TypelogService.lastApp;
        live.appLabel = TypelogService.lastAppLabel;
        live.field = TypelogService.lastField;
        live.text = TypelogService.lastText;
        setCardText(liveView, "实时预览（最近一次输入）", ToolsText.livePreview(live));

        ToolsText.Status st = new ToolsText.Status();
        st.written = TypelogService.written;
        st.evAll = TypelogService.evAll;
        st.evText = TypelogService.evText;
        st.evCaptured = TypelogService.evCaptured;
        st.evTraverseHit = TypelogService.evTraverseHit;
        st.skippedPassword = TypelogService.skippedPassword;
        setCardText(statusView, "采集状态", ToolsText.status(serviceStateText(), st));

        new Thread(() -> {
            LogStore store = LogStore.get(host.getFilesDir());
            String today = host.DAY.format(new Date());
            Map<String, String> labels = store.labels(today);
            java.util.List<LogStore.Row> rows = store.readDay(today, 0);
            long bytes = store.totalBytes();

            final String stFinal = ToolsText.statsText(rows, bytes);
            final String apFinal = ToolsText.appsText(rows, labels);
            host.ui.post(() -> {
                setCardText(statsView, "统计（今天）", stFinal);
                setCardText(appsView, "今天记录过的 App", apFinal);
            });
        }, "typelog-tools").start();

        // 诊断区的填充放在 buildDiagBox() 里：它决定"是否展开、是否已创建视图"，
        // 放在这里会出现"没展开也去读盘"以及"展开了却没人填"两种毛病。
    }


    /** 应用某一项设置（页面与设置弹窗共用） */
    void applySetting(int which) {
        switch (which) {
            case 0:
                boolean kaOn = !Prefs.keepAlive(host);
                Prefs.setKeepAlive(host, kaOn);
                if (kaOn) {
                    KeepAliveService.start(host);
                } else {
                    KeepAliveService.stop(host);
                }
                host.toast(kaOn ? "已开启保活：通知栏会出现一条常驻通知，进程不易被系统回收"
                           : "已关闭保活：内存紧张时系统可能中断记录");
                break;
            case 1:
                Prefs.setFocusOnly(host, !Prefs.focusOnly(host));
                host.toast(Prefs.focusOnly(host) ? "只记正在输入的框（推荐）"
                                            : "所有可编辑框都会被记录");
                break;
            case 2:
                Prefs.setIgnoreDeletions(host, !Prefs.ignoreDeletions(host));
                host.toast(Prefs.ignoreDeletions(host) ? "删字不再新建记录" : "删除也会被记录");
                break;
            case 3:
                Prefs.setSkipIme(host, !Prefs.skipIme(host));
                host.toast("已" + (Prefs.skipIme(host) ? "排除" : "包含") + "输入法键盘事件");
                break;
            case 4:
                host.chooseMinChars(this::refreshTools);
                break;
            case 5:
                host.chooseRetention(this::buildSettingsBox);
                break;
            case 6:
                Prefs.setPolling(host, !Prefs.polling(host));
                host.toast("轮询兜底已" + (Prefs.polling(host) ? "开启" : "关闭") + "（下次生效）");
                break;
            case 7:
                Prefs.setDebug(host, !Prefs.debug(host));
                if (Prefs.debug(host)) {
                    TypelogService.DIAG.clear();
                }
                host.toast("诊断日志已" + (Prefs.debug(host) ? "开启" : "关闭"));
                break;
            case 8:
                Prefs.setKeepEmpty(host, !Prefs.keepEmpty(host));
                host.toast("已" + (Prefs.keepEmpty(host) ? "记录" : "忽略") + "清空事件");
                break;
            case 9:
                host.chooseIgnored();
                break;
            default:
                host.toast("应用私有目录：/data/data/" + host.getPackageName() + "/files/logs/");
                break;
        }
    }


    /**
     * 服务没在记录时给一个弹窗。
     *
     * 只在"以前确实开过"（Prefs.serviceEnabledAt > 0）时提醒，
     * 免得第一次装应用就弹一个用户看不懂的东西；并做 5 分钟节流。
     */
            /** 采集状态文案（含"该怎么办"） */
        /** 去系统无障碍设置 */
        /** 宽限期后检查一次：刷新状态显示，必要时弹窗提醒 */
    void refreshServiceCard() {
        if (statusView != null) {
            setCardText(statusView, "采集状态", serviceStateText());
        }
    }


    /**
     * 服务没在记录时给一个弹窗。
     *
     * 只在"以前确实开过"（Prefs.serviceEnabledAt > 0）时提醒，
     * 免得第一次装应用就弹一个用户看不懂的东西；并做 5 分钟节流。
     */
    void warnIfServiceStalled() {
        int st = host.serviceState();
        boolean abnormal = (st == ServiceState.STALLED
                || (st == ServiceState.OFF && Prefs.serviceEnabledAt(host) > 0));
        if (!abnormal) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStalledWarnAt < 5 * 60 * 1000L) {
            return;
        }
        lastStalledWarnAt = now;

        String why = (st == ServiceState.STALLED)
                ? "无障碍服务曾经打开过，但现在没有真正连上。\n\n"
                  + "在「已下载的应用」列表里它可能仍显示「开启」，"
                  + "这是 MIUI 上很常见的假象。"
                : "还没有打开无障碍服务。";

        new android.app.AlertDialog.Builder(host)
                .setTitle("记录已经停了")
                .setMessage("现在打的字不会被保存。\n\n" + why + "\n\n"
                        + "解决：到 系统设置 → 无障碍 → 已下载的应用 → DraftGuard，\n"
                        + "把它「关掉再打开一次」。\n\n"
                        + "顺手再做两件事更稳：\n"
                        + "· 应用管理 → DraftGuard → 省电策略 → 无限制\n"
                        + "· 打开「自启动」权限\n\n"
                        + "回到本应用后，「工具」页会显示「● 已开启，正在记录」。")
                .setPositiveButton("去系统设置", (d, w) -> openAccessibilitySettings())
                .setNegativeButton("知道了", null)
                .show();
    }



    /** 去系统无障碍设置 */
    void openAccessibilitySettings() {
        try {
            host.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            host.toast("打不开系统设置，请手动进入：设置 → 无障碍");
        }
    }


    String serviceStateText() {
        return ServiceState.text(host.serviceState());
    }


    String serviceStateName() {
        return ServiceState.name(host.serviceState());
    }


    boolean isServiceEnabled() {
        return host.serviceState() == ServiceState.OK;
    }

}
