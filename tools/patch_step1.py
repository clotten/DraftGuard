#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step1.py —— 用新的"日志页"结构替换记录页的构建与刷新逻辑。

设计（与用户确认过）：
  · 打开就看到记录列表（首屏）
  · 状态/实时预览/统计/应用列表 全收进「诊断」弹窗，页面只留一行摘要
  · 精选四个操作（时间范围 / 看逐条 / 更多），其余收进「更多」
  · 记录条目带应用小图标、圆角卡片、更多留白
  · 搜索直接过滤列表，不再另开结果区
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import patch   # noqa: E402

ROOT = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

# ──────────────────────────────────────────────── 新的记录页构建
BUILD = r'''private void buildRecordsPage() {
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
    private void showMore() {
        final String[] items = {
                "导出到下载目录", "分享 zip", "诊断", "清除全部记录", "设置",
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
                            showDiag();
                            break;
                        case 3:
                            confirmClearAll();
                            break;
                        default:
                            showSettings();
                            break;
                    }
                })
                .show();
    }

'''

# ──────────────────────────────────────────────── 列表适配器
ADAPTER = r'''/**
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
            row.setBackground(rounded(COL_CARD, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), dp(3), dp(12), dp(3));
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

'''

# ──────────────────────────────────────────────── 刷新：摘要 + 列表
REFRESH = r'''/** 顶部一行摘要。原来的四张卡片内容都并进了「诊断」弹窗，这里只留最关键的两项 */
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
            String today = DAY.format(new Date());
            final java.util.List<Object> out = new java.util.ArrayList<>();

            if (showRawRows) {
                // 逐条视图：直接给原始版本（新的在前）
                java.util.List<LogStore.Row> rows = store.readDay(today, 0);
                for (int i = rows.size() - 1; i >= 0 && out.size() < 400; i--) {
                    LogStore.Row r = rows.get(i);
                    if ("send".equals(r.ev) || r.text.isEmpty()) {
                        continue;
                    }
                    if (!query.isEmpty() && !r.text.contains(query)) {
                        continue;
                    }
                    if (!appFilter.isEmpty() && !appFilter.contains(r.app)) {
                        continue;
                    }
                    out.add(r);
                }
            } else {
                java.util.List<LogStore.Row> rows = store.readDay(today, 0);
                long from = rangeMinutes <= 0 ? 0
                        : System.currentTimeMillis() - rangeMinutes * 60_000L;
                java.util.List<LogStore.Row> kept = new java.util.ArrayList<>();
                for (LogStore.Row r : rows) {
                    if ("send".equals(r.ev)) {
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
            refreshList();
            return;
        }
        refreshList();
        toast("已筛选包含「" + query + "」的记录");
    }

    /** 诊断弹窗：原来页面上的状态/实时预览/统计/应用列表都放这里 */
    private void showDiag() {
        final android.widget.ScrollView sc = new android.widget.ScrollView(this);
        final TextView tv = new TextView(this);
        tv.setTextColor(COL_FG);
        tv.setTextSize(13);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setLineSpacing(dp(3), 1f);
        tv.setText("读取中…");
        sc.addView(tv);

        new Thread(() -> {
            final LogStore store = new LogStore(getFilesDir(), 0);
            final String today = DAY.format(new Date());
            final Map<String, String> labels = store.labels(today);
            final java.util.List<LogStore.Row> rows = store.readDay(today, 0);
            final long bytes = store.totalBytes();

            int compCount = 0;
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, Integer> chars = new LinkedHashMap<>();
            int total = 0;
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

            final StringBuilder sb = new StringBuilder();
            sb.append("── 采集状态 ──\n");
            sb.append(isServiceEnabled() ? "● 已开启，正在记录\n" : "○ 未开启\n");
            sb.append("本次会话落盘 ").append(TypelogService.written).append(" 条")
              .append("（服务重启会归零，历史记录不会丢）\n");
            sb.append("收到事件 ").append(TypelogService.evAll)
              .append("（文本变化 ").append(TypelogService.evText).append("）\n");
            sb.append("取到文本 ").append(TypelogService.evCaptured)
              .append("　兜底找回 ").append(TypelogService.evTraverseHit)
              .append("　跳过密码框 ").append(TypelogService.skippedPassword).append("\n");
            sb.append("检测到点击「发送/搜索/发布」").append(TypelogService.sendBoundaries)
              .append(" 次（消息分段依据）\n");
            sb.append("跳过未聚焦的框 ").append(TypelogService.skippedNoFocus)
              .append("　删除 ").append(TypelogService.skippedDelete)
              .append("　噪音 ").append(TypelogService.skippedNoise).append("\n");

            sb.append("\n── 实时预览（最近一次输入）──\n");
            if (TextUtils.isEmpty(TypelogService.lastTs)) {
                sb.append("还没有捕获到输入。\n");
            } else {
                String app = TextUtils.isEmpty(TypelogService.lastAppLabel)
                        ? TypelogService.lastApp : TypelogService.lastAppLabel;
                sb.append("时间：").append(TypelogService.lastTs).append("\n");
                sb.append("应用：").append(TextUtils.isEmpty(app) ? "（未知）" : app).append("\n");
                sb.append("输入框：").append(TypelogService.lastField).append("\n");
                sb.append("当前字数：").append(TypelogService.lastText.length()).append("\n");
                sb.append("—— 最近一次内容 ——\n").append(tail(TypelogService.lastText, 300)).append("\n");
            }

            sb.append("\n── 统计（今天）──\n");
            sb.append("落盘版本数：").append(total).append("\n");
            sb.append("其中未上屏状态：").append(compCount).append(" 条\n");
            sb.append("占用空间：").append(bytes < 1024 ? bytes + " 字节" : (bytes / 1024) + " KB")
              .append("\n");
            if (!rows.isEmpty()) {
                LogStore.Row last = rows.get(rows.size() - 1);
                sb.append("最后一条：").append(last.ts.replace("T", " ").substring(0, 19))
                  .append("　").append(last.app).append("　").append(last.chars).append(" 字\n")
                  .append(tail(last.text, 120)).append("\n");
            }
            sb.append("保存位置：应用私有目录（其他 App 读不到）\n");

            sb.append("\n── 今天记录过的 App ──\n");
            if (counts.isEmpty()) {
                sb.append("还没有记录。\n");
            } else {
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    sb.append("· ").append(name(labels, e.getKey()))
                      .append("（").append(e.getKey()).append("）  ")
                      .append(e.getValue()).append(" 条，最长 ")
                      .append(chars.get(e.getKey())).append(" 字\n");
                }
            }

            ui.post(() -> {
                tv.setText(sb.toString().trim());
                new android.app.AlertDialog.Builder(this)
                        .setTitle("诊断")
                        .setView(sc)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }, "typelog-diag").start();
    }

'''

OPS = [
    ("replace", "private void buildUi() {", BUILD),
    ("replace", "private void refreshAll() {", REFRESH),
    ("delete", "private void refreshStatus() {", None),
    ("delete", "private void refreshLive() {", None),
    ("delete", "private void showDiag() {", None),
    ("delete", "private void doSearch() {", None),
    ("delete", "private void showToday() {", None),
    ("delete", "private void scrollToResults() {", None),
    ("delete", "private boolean isFullyVisible(View v) {", None),
    ("delete", "private void onScrollRefresh() {", None),
]

if __name__ == "__main__":
    print("替换记录页的构建与刷新逻辑：")
    patch(ROOT, OPS)
    print("\n现在把适配器追加到类中……")
    src = ROOT.read_text(encoding="utf-8")
    if "class RecordAdapter" in src:
        print("  已存在 RecordAdapter，跳过")
    else:
        idx = src.rfind("}")
        src = src[:idx] + ADAPTER + "}\n"
        ROOT.write_text(src, encoding="utf-8")
        print("  ✓ 已追加 RecordAdapter 及辅助方法")
