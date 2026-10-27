package com.draftguard;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「记录」页：摘要 + 搜索 + 时间范围 + 列表（合并视图 / 逐条视图）。
 *
 * <h3>为什么单独成类</h3>
 * 它原来是 {@link MainActivity} 里最长的一块（页面装配 + 摘要 + 列表刷新 + 搜索），
 * 与「应用」页、「工具」页的代码挤在同一个类里。抽出来后：
 * <ul>
 *   <li>本页自己的视图与状态（列表项、搜索词、时间范围、应用过滤）都由本类持有</li>
 *   <li>宿主只提供 Context / 主线程 Handler / dp / 几个共用小工具</li>
 *   <li>时间范围、应用过滤、单条删除这些**菜单动作**仍留在宿主里，
 *       通过本类暴露的 setter 生效 —— 这样对话框代码不用跟着搬</li>
 * </ul>
 *
 * <h3>刷新为什么这么写</h3>
 * 每次刷新只读"需要的窗口"（{@code readNewest(from, 上限)}），
 * 再把最新 N 行交给分段器。历史数据再多也不会变慢 —— 详见 {@link #refreshList()}。
 */
final class RecordsPage {

    /** 每页最多多少行交给分段器 */
    private static final int SEGMENT_ROW_CAP = 6000;
    /**
     * 列表最多显示多少段。
     *
     * <p>原来是 200 —— 结果用户选了「最近 24 小时」却只看到约 11 小时的内容：
     * 段数被截断了，而界面**没有任何提示**，看起来就像时间范围没生效。
     * 现在提到 1000（ListView 有视图回收，这个量级没问题），
     * 并且截断时会在摘要里明说。
     */
    private static final int MAX_SEGMENTS = 1000;

    private final MainActivity host;
    private final LinearLayout root;

    final List<Object> items = new ArrayList<>();
    RecordAdapter adapter;

    private TextView summaryText;
    private EditText searchBox;
    private android.widget.ListView listRecords;
    private Button btnRange;
    private Button btnRaw;

    private String query = "";
    private boolean showRawRows = false;
    private int rangeMinutes = 1440;
    private final Set<String> appFilter = new HashSet<>();

    /** 最近一次列表加载的统计（诊断面板会显示） */
    long lastMs;
    int lastDays;
    int lastRows;
    int lastSegmented;

    RecordsPage(MainActivity host, LinearLayout root) {
        this.host = host;
        this.root = root;
    }

    // ── 给宿主菜单用的 setter / 查询 ──────────────────────────────

    void setRangeMinutes(int minutes) {
        this.rangeMinutes = minutes;
        if (btnRange != null) {
            btnRange.setText(rangeLabel());
        }
    }

    int rangeMinutes() {
        return rangeMinutes;
    }

    void setFilter(Set<String> filter) {
        appFilter.clear();
        appFilter.addAll(filter);
    }

    void setQuery(String q) {
        this.query = q == null ? "" : q;
    }

    String query() {
        return query;
    }

    boolean showRawRows() {
        return showRawRows;
    }

    String rangeLabel() {
        if (rangeMinutes < 60) {
            return "最近 " + rangeMinutes + " 分钟";
        }
        if (rangeMinutes < 1440) {
            return "最近 " + (rangeMinutes / 60) + " 小时";
        }
        return "最近 24 小时";
    }

    void build() {
        root.removeAllViews();

        // 1) 顶部摘要：一行小字。原来的状态/实时预览/统计/应用列表四张卡片
        //    都收进了「更多 → 诊断」，日常只留这一行。
        LinearLayout summaryRow = new LinearLayout(host);
        summaryRow.setOrientation(LinearLayout.HORIZONTAL);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        summaryRow.setPadding(host.dp(14), host.dp(10), host.dp(10), host.dp(2));
        summaryText = new TextView(host);
        summaryText.setTextColor(MainActivity.COL_DIM);
        summaryText.setTextSize(12);
        summaryText.setText("正在检测…");
        summaryText.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        summaryRow.addView(summaryText);
        TextView btnRefresh = host.link("刷新");
        btnRefresh.setOnClickListener(v -> {
            refreshSummary();
            refreshList();
        });
        summaryRow.addView(btnRefresh);
        root.addView(summaryRow);

        // 2) 搜索行：搜索直接过滤下面的列表，不再另开一个结果区
        LinearLayout searchRow = new LinearLayout(host);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(host.dp(14), host.dp(4), host.dp(14), 0);
        searchBox = new EditText(host);
        searchBox.setHint("搜索记录过的文字…");
        searchBox.setHintTextColor(MainActivity.COL_DIM);
        searchBox.setTextColor(MainActivity.COL_FG);
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setPadding(0, host.dp(8), 0, host.dp(8));
        searchBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBox.setOnEditorActionListener((v, actionId, ev) -> {
            doSearch();
            return true;
        });
        searchRow.addView(searchBox);
        Button btnSearch = host.button("搜");
        btnSearch.setOnClickListener(v -> doSearch());
        searchRow.addView(btnSearch);
        root.addView(searchRow);

        // 3) 操作行：最常用的三个，其余在「更多」
        LinearLayout actionRow = new LinearLayout(host);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(6));
        btnRange = host.button(rangeLabel());
        btnRange.setOnClickListener(v -> host.chooseRangeFor(this));
        actionRow.addView(btnRange);
        btnRaw = host.button(showRawRows ? "看合并" : "看逐条");
        btnRaw.setOnClickListener(v -> {
            showRawRows = !showRawRows;
            btnRaw.setText(showRawRows ? "看合并" : "看逐条");
            refreshList();
        });
        actionRow.addView(btnRaw);
        Button btnMore = host.button("更多");
        btnMore.setOnClickListener(v -> host.showMoreFor(this));
        actionRow.addView(btnMore);
        root.addView(actionRow);

        // 4) 列表占满剩余高度。用 ListView 而不是往 TextView 里拼字符串 ——
        //    几百条时后者是一整面文字墙，而且没有视图回收会卡。
        listRecords = new android.widget.ListView(host);
        listRecords.setDivider(null);
        listRecords.setDividerHeight(0);
        listRecords.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        listRecords.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new RecordAdapter(host, items);
        listRecords.setAdapter(adapter);
        root.addView(listRecords);
    }


    /** 顶部一行摘要。原来的四张卡片内容都并进了「诊断」弹窗，这里只留最关键的两项 */
    void refreshSummary() {
        boolean on = (host.serviceState() == ServiceState.OK);
        final StringBuilder sb = new StringBuilder();
        sb.append(on ? "● 已开启 · 记录中" : "○ 未开启（点「更多 → 设置」或去系统设置开启）");
        new Thread(() -> {
            final LogStore store = new LogStore(host.getFilesDir(), 0);
            final String today = host.DAY.format(new Date());
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
            host.ui.post(() -> {
                sb.append(" · 今天 ").append(count).append(" 条");
                if (bytes > 0) {
                    sb.append(" · ").append(bytes < 1024 ? bytes + " B" : (bytes / 1024) + " KB");
                }
                if (!lastApp.isEmpty()) {
                    sb.append(" · 最近 ").append(host.labelOf(lastApp));
                }
                if (summaryText != null) {
                    summaryText.setText(sb.toString());
                }
            });
        }, "typelog-summary").start();
    }


    void refreshList() {
        if (adapter == null) {
            return;
        }
        final int first = listRecords.getFirstVisiblePosition();
        View v0 = listRecords.getChildAt(0);
        final int top = v0 == null ? 0 : v0.getTop();
        final boolean keep = listRecords.getCount() > 0;

        new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            LogStore store = new LogStore(host.getFilesDir(), 0);

            // 时间范围先算出来，好据此**只读需要的日期**。
            // 之前是先读完所有日期再过滤 —— 数据攒到几天后，每次刷新都要把几万个
            // 版本的 JSON 全解析、全排序、全分段，于是"越大越慢"。
            final long from = rangeMinutes <= 0 ? 0
                    : System.currentTimeMillis() - rangeMinutes * 60_000L;
            final String fromDay = from > 0
                    ? new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                          .format(new java.util.Date(from))
                    : null;
            // 只取"最新 N 行"：不再遍历日期、不再有按天缓存的回退分支 ——
            // 存储层现在只有 SQLite，一条带索引的查询就够。
            final java.util.List<LogStore.Row> rows =
                    new java.util.ArrayList<>(store.readNewest(from, SEGMENT_ROW_CAP));
            java.util.Collections.sort(rows, (a, b) -> a.ts.compareTo(b.ts));
            lastDays = store.days().size();
            lastRows = rows.size();
            lastSegmented = rows.size();
            // 分层原则（本项目最该守住的一条）：
            //   **过滤只作用于"显示"，不作用于"分段"。**
            // 边界记录（发送标记 ev=send、空文本）是分段的唯一可靠依据，必须原样交给
            // 分段器。曾经在这里把它们 continue 掉，分段器看不到任何边界，只能退回用
            // 文本形态去猜 —— 实测后果："好 / 好不好 / 你不好 / 你不会" 四条独立消息
            // 被合并成一条（界面显示「合并 4 版」）。
            // 它们不会出现在结果里：Burst.groupNewestFirst 已负责过滤空段。
            // 每页最多显示 400 段；"加载更早"每点一次多一页
            final int segCap = MAX_SEGMENTS;
            java.util.List<LogStore.Row> kept = new java.util.ArrayList<>();
            for (LogStore.Row r : rows) {
                final boolean boundary = "send".equals(r.ev) || r.text.isEmpty();
                if (from > 0 && Burst.msOf(r.ts) < from) {
                    continue;
                }
                if (!appFilter.isEmpty() && !appFilter.contains(r.app)) {
                    continue;
                }
                if (!boundary && !query.isEmpty() && !r.text.contains(query)) {
                    continue;
                }
                kept.add(r);
            }

            final java.util.List<Object> out = new java.util.ArrayList<>();
            if (showRawRows) {
                for (int i = kept.size() - 1; i >= 0 && out.size() < segCap; i--) {
                    LogStore.Row rr = kept.get(i);
                    if (rr.text.isEmpty()) {
                        continue;      // 逐条视图只显示有正文的版本
                    }
                    out.add(rr);
                }
            } else {
                java.util.List<Burst> bursts = Burst.groupNewestFirst(kept);
                for (int i = 0; i < bursts.size() && out.size() < segCap; i++) {
                    out.add(bursts.get(i));
                }
            }

            // 是否因为上限而截断（摘要里要明说，不能静默少给）。
            // 两种上限都要算：段数上限、以及"只读最新 N 行"的行数上限。
            final boolean truncated = out.size() >= segCap
                    || rows.size() >= SEGMENT_ROW_CAP;
            final int totalSegs = showRawRows ? kept.size() : -1;
            lastMs = System.currentTimeMillis() - t0;
            // 诊断面板与探针读的是宿主上的那份统计，这里同步过去。
            // （之所以不直接删掉宿主字段：诊断/探针是排查手段，接口尽量别动。）
            host.lastListMs = lastMs;
            host.lastListDays = lastDays;
            host.lastListRows = lastRows;
            host.lastListSegmented = lastSegmented;
            host.ui.post(() -> {
                items.clear();
                items.addAll(out);
                adapter.notifyDataSetChanged();
                // 截断必须让用户看见：否则「最近 24 小时」只显示一部分，
                // 会被当成"时间范围没生效"（用户实际报过这个问题）。
                if (truncated && summaryText != null) {
                    String cur = summaryText.getText().toString();
                    if (!cur.contains("仅显示最近")) {
                        summaryText.setText(cur + " · 仅显示最近 " + segCap + " 段");
                    }
                }
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




    void doSearch() {
        if (searchBox == null) {
            return;
        }
        query = searchBox.getText().toString().trim();
        if (query.isEmpty()) {
            host.toast("已取消筛选");
        }
        refreshList();
        if (!query.isEmpty()) {
            host.toast("已筛选包含「" + query + "」的记录");
        }
    }

}
