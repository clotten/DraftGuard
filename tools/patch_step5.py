#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step5.py —— 新增第三页「工具」：
  四张状态卡片（采集状态 / 实时预览 / 统计 / 今天记录过的 App）
  + 设置项 + 诊断信息（直接铺在页面上，不再依赖弹窗）

做法：删掉旧的 showMore / showSettings / showDiag 三个方法，
再整块插入新实现 —— 避免零散替换导致"改了这里丢了那里"。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import delete_method, insert_after   # noqa: E402

MAIN = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
LAYOUT = Path(r"E:\desktop\酒馆\tools\ziJi\android\res\layout\activity_main.xml")

NEW_TAB = """
        <LinearLayout
            android:id="@+id/tabTools"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical">

            <TextView
                android:id="@+id/navToolsText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center"
                android:paddingTop="14dp"
                android:paddingBottom="12dp"
                android:text="工具"
                android:textColor="#8B95A7"
                android:textSize="15sp" />

            <View
                android:id="@+id/navToolsBar"
                android:layout_width="match_parent"
                android:layout_height="2dp"
                android:background="#1A1D26" />
        </LinearLayout>
"""

PAGE3 = """
        <!-- 页面 3：工具（状态卡片 + 设置 + 诊断） -->
        <ScrollView
            android:id="@+id/page3"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:fillViewport="true"
            android:visibility="gone">

            <LinearLayout
                android:id="@+id/toolsRoot"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="14dp" />
        </ScrollView>
"""

FIELDS = """    // ── 第三页（工具）：状态卡片 + 设置 + 诊断 ──────────────
    private android.widget.ScrollView page3;
    private LinearLayout toolsRoot;
    private TextView statusView, liveView, statsView, appsView, diagView;
    private TextView navToolsText;
    private View navToolsBar;
"""

BLOCK = r'''/** 第三页：四张状态卡片 + 设置 + 诊断。
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

        toolsRoot.addView(sectionTitle("设置"));
        String[] items = settingItems();
        for (int i = 0; i < items.length; i++) {
            toolsRoot.addView(settingRow(items[i], i));
        }

        toolsRoot.addView(sectionTitle("诊断"));
        TextView hint = new TextView(this);
        hint.setTextColor(COL_DIM);
        hint.setTextSize(11);
        hint.setPadding(dp(2), 0, 0, dp(8));
        hint.setText("排查问题用，内容较长，往下滚即可。");
        toolsRoot.addView(hint);
        diagView = cardView("事件与存储", "读取中…");
        toolsRoot.addView(diagView);
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
    private View settingRow(String text, final int which) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(COL_FG);
        t.setTextSize(13);
        t.setBackground(rounded(COL_CARD, 12));
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            applySetting(which);
            refreshTools();
        });
        return t;
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

        new Thread(() -> {
            final String d = diagText().toString();
            ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag").start();
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
'''


def patch_layout() -> None:
    src = LAYOUT.read_text(encoding="utf-8")
    if "tabTools" not in src:
        marker = """                android:background="#1A1D26" />
        </LinearLayout>
    </LinearLayout>
"""
        if marker not in src:
            raise SystemExit("导航栏锚点未找到")
        src = src.replace(marker, """                android:background="#1A1D26" />
        </LinearLayout>
""" + NEW_TAB + "    </LinearLayout>\n", 1)
        print("  ✓ 布局：加入「工具」标签")
    if "page3" not in src:
        anchor = "        </ScrollView>\n    </FrameLayout>"
        if anchor not in src:
            raise SystemExit("页面容器锚点未找到")
        src = src.replace(anchor, "        </ScrollView>\n" + PAGE3 + "    </FrameLayout>", 1)
        print("  ✓ 布局：加入 page3")
    LAYOUT.write_text(src, encoding="utf-8")


def main() -> None:
    patch_layout()
    src = MAIN.read_text(encoding="utf-8")

    if "private TextView statusView, liveView" not in src:
        anchor = "    // ── 导航与第二页（应用列表）────────────────────────────────"
        if anchor not in src:
            raise SystemExit("字段锚点未找到")
        src = src.replace(anchor, FIELDS + "\n" + anchor, 1)
        print("  ✓ 字段")

    # 三页切换
    reps = [
        ('        findViewById(R.id.tabApps).setOnClickListener(v -> switchPage(1));',
         '        findViewById(R.id.tabApps).setOnClickListener(v -> switchPage(1));\n'
         '        findViewById(R.id.tabTools).setOnClickListener(v -> switchPage(2));\n'
         '        navToolsText = findViewById(R.id.navToolsText);\n'
         '        navToolsBar = findViewById(R.id.navToolsBar);'),
        ('        page2 = findViewById(R.id.page2);',
         '        page2 = findViewById(R.id.page2);\n'
         '        page3 = findViewById(R.id.page3);\n'
         '        toolsRoot = findViewById(R.id.toolsRoot);'),
        ("""        pageRoot.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        page2.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);""",
         """        pageRoot.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        page2.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        page3.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);"""),
        ("""        navRecordsBar.setBackgroundColor(idx == 0 ? barOn : barOff);
        navAppsBar.setBackgroundColor(idx == 1 ? barOn : barOff);""",
         """        navRecordsBar.setBackgroundColor(idx == 0 ? barOn : barOff);
        navAppsBar.setBackgroundColor(idx == 1 ? barOn : barOff);
        if (navToolsText != null) {
            navToolsText.setTextColor(idx == 2 ? on : off);
            navToolsText.setTypeface(null, idx == 2 ? Typeface.BOLD : Typeface.NORMAL);
            navToolsBar.setBackgroundColor(idx == 2 ? barOn : barOff);
        }"""),
        ("""        if (idx == 1) {
            refreshAppsPage();
        }""",
         """        if (idx == 1) {
            refreshAppsPage();
        } else if (idx == 2) {
            refreshTools();
        }"""),
        ("        buildRecordsPage();", "        buildRecordsPage();\n        buildToolsPage();"),
    ]
    for old, new in reps:
        if old not in src:
            raise SystemExit(f"锚点未找到：{old[:70]}")
        src = src.replace(old, new, 1)
    print("  ✓ setupNav / switchPage / onCreate / 三页切换")

    # onResume 也刷新工具页
    old = "        refreshSummary();\n        refreshList();\n    }\n\n    @Override\n    protected void onPause()"
    new = "        refreshSummary();\n        refreshList();\n        refreshTools();\n    }\n\n    @Override\n    protected void onPause()"
    if old in src:
        src = src.replace(old, new, 1)
        print("  ✓ onResume 刷新工具页")

    # 广播到达时，工具页可见才刷新
    old = """                refreshSummary();
                refreshList();
            }"""
    new = """                refreshSummary();
                refreshList();
                if (currentPage == 2) {
                    refreshTools();
                }
            }"""
    if old in src:
        src = src.replace(old, new, 1)
        print("  ✓ 广播联动")

    # 删掉旧的三个方法，整块插入新实现
    for m in ("private void showMore() {", "private void showSettings() {", "private void showDiag() {"):
        src = delete_method(src, m)
        print(f"  ✓ 删除旧 {m.split('(')[0].split()[-1]}")
    src = insert_after(src, "private static String name(Map<String, String> labels, String app) {", BLOCK)
    print("  ✓ 插入工具页与设置/诊断实现（共用一份生成逻辑）")

    MAIN.write_text(src, encoding="utf-8")
    print(f"已写入 {MAIN}")


if __name__ == "__main__":
    main()
