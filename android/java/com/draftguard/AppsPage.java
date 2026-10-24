package com.draftguard;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 「应用」页：所有被记录过的应用 + 单个应用的按天历史。
 *
 * <h3>为什么单独成类</h3>
 * 它原本和记录页、工具页一起挤在 {@link MainActivity}（2000 多行）里，
 * 改一个页面的渲染会动到另一个页面的邻近代码。抽出来后：
 * <ul>
 *   <li>本页自己的 View（{@code appsRoot}）和状态（{@code detailApp}）都由本类持有</li>
 *   <li>宿主只提供 4 样东西：Context / 主线程 Handler / dp / 图标与标签</li>
 *   <li>页面切换由宿主调用 {@link #refresh()}，本类不关心当前在第几页</li>
 * </ul>
 *
 * <h3>按天分页</h3>
 * 单个应用的历史可能上万行。原来是把全部内容拼成一个巨大的 Spannable
 * 一次性塞进 TextView —— 数据攒多之后既慢又吃内存（用户反馈"单个应用数据加载的很慢"）。
 * 现在一次只查一天，顶部提供「← 更早 / 日期 i/N / 更晚 →」翻页。
 */
final class AppsPage {

    private final MainActivity host;
    private final LinearLayout appsRoot;
    /** 正在看哪个应用的历史（null = 停在应用列表） */
    private String detailApp = null;

    AppsPage(MainActivity host, LinearLayout appsRoot) {
        this.host = host;
        this.appsRoot = appsRoot;
    }

    String detailApp() {
        return detailApp;
    }

    /** 重建整个应用列表 */
    void refresh() {
        appsRoot.removeAllViews();
        detailApp = null;
        appsRoot.addView(host.heading("应用"));
        TextView hint = new TextView(host);
        hint.setTextColor(Color.parseColor("#8B95A7"));
        hint.setTextSize(12);
        hint.setPadding(host.dp(2), 0, 0, host.dp(10));
        hint.setText("点任意一个应用，看它里面的打字记录");
        appsRoot.addView(hint);

        final TextView loading = new TextView(host);
        loading.setTextColor(Color.parseColor("#8B95A7"));
        loading.setTextSize(13);
        loading.setPadding(host.dp(2), host.dp(8), 0, 0);
        loading.setText("统计中…");
        appsRoot.addView(loading);

        new Thread(() -> {
            LogStore store = LogStore.get(host.getFilesDir());
            // 全部历史（不限制天数）：用户要求"所有记录过的应用"
            final Map<String, LogStore.AppStat> stats = store.appsSummary(0);
            // 按最近使用时间倒序
            List<LogStore.AppStat> list = new ArrayList<>(stats.values());
            Collections.sort(list, (x, y) -> y.lastTs.compareTo(x.lastTs));

            host.ui.post(() -> {
                appsRoot.removeView(loading);
                if (list.isEmpty()) {
                    TextView empty = new TextView(host);
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

    /** 一行：图标 + 名字 + 条数（右侧） */
    private View appRow(final LogStore.AppStat st) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.parseColor("#1A1D26"));
        row.setPadding(host.dp(10), host.dp(10), host.dp(10), host.dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = host.dp(8);
        row.setLayoutParams(rlp);
        row.setClickable(true);
        row.setOnClickListener(v -> showDetail(st));

        ImageView icon = new ImageView(host);
        int sz = host.dp(42);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = host.dp(12);
        icon.setLayoutParams(ilp);
        Drawable d = host.iconCache.get(st.app);
        icon.setImageDrawable(d != null ? d : host.letterIcon(st.display(), st.app));
        icon.setTag(st.app);
        row.addView(icon);

        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(host);
        name.setTextColor(Color.parseColor("#E8ECF3"));
        name.setTextSize(15);
        name.setText(st.display());
        col.addView(name);

        TextView meta = new TextView(host);
        meta.setTextColor(Color.parseColor("#8B95A7"));
        meta.setTextSize(11);
        meta.setPadding(0, host.dp(3), 0, 0);
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

        TextView count = new TextView(host);
        count.setTextColor(Color.parseColor("#5B9DFF"));
        count.setTextSize(13);
        count.setText(st.rows + " 条");
        row.addView(count);

        return row;
    }

    /** 后台取图标，回来后只回填"还在用兜底字"的那些行 */
    private void loadIcons(final List<LogStore.AppStat> stats) {
        new Thread(() -> {
            android.content.pm.PackageManager pm = host.getPackageManager();
            for (LogStore.AppStat st : stats) {
                if (host.iconCache.containsKey(st.app)) {
                    continue;
                }
                Drawable d = null;
                try {
                    // 清单里已声明 MAIN/LAUNCHER 的 <queries>，所以这里能看到
                    // 有启动图标的第三方应用（安卓 11+ 的包可见性限制）
                    d = pm.getApplicationIcon(st.app);
                } catch (Throwable ignored) {
                    // 取不到就走 letterIcon 兜底
                }
                synchronized (host.iconCache) {
                    host.iconCache.put(st.app, d);
                }
            }
            host.ui.post(() -> {
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
                    Drawable d = host.iconCache.get(tag.toString());
                    if (d != null) {
                        ((ImageView) first).setImageDrawable(d);
                    }
                }
            });
        }, "typelog-icons").start();
    }

    // ══════════════════════════════════════════════ 应用详情（按天分页）

    /**
     * 应用详情：**按天翻页**，一次只渲染一天。
     *
     * <p>为什么改：原来是把这个应用的全部历史拼成一个巨大的 Spannable 一次性塞进
     * TextView —— 数据攒多之后既慢又吃内存（用户反馈"单个应用数据加载的很慢"）。
     * 改成一次一天：每次只查一天的量，快且确定。
     */
    private void showDetail(final LogStore.AppStat st) {
        detailApp = st.app;
        appsRoot.removeAllViews();

        // 顶部：返回 + 标题
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, host.dp(10));

        Button back = host.button("← 返回");
        back.setOnClickListener(v -> refresh());
        bar.addView(back);

        LinearLayout titleCol = new LinearLayout(host);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = host.dp(12);
        titleCol.setLayoutParams(tlp);

        TextView name = new TextView(host);
        name.setTextColor(Color.parseColor("#E8ECF3"));
        name.setTextSize(17);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setText(st.display());
        titleCol.addView(name);

        TextView sub = new TextView(host);
        sub.setTextColor(Color.parseColor("#8B95A7"));
        sub.setTextSize(11);
        sub.setPadding(0, host.dp(2), 0, 0);
        sub.setText(st.rows + " 条　" + st.app);
        titleCol.addView(sub);
        bar.addView(titleCol);
        appsRoot.addView(bar);

        // 天导航：更早 / 日期 / 更晚
        LinearLayout nav = new LinearLayout(host);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, 0, 0, host.dp(8));

        final Button older = host.button("← 更早");
        final Button newer = host.button("更晚 →");
        final TextView dayLabel = new TextView(host);
        dayLabel.setTextColor(Color.parseColor("#5B9DFF"));
        dayLabel.setTextSize(13);
        dayLabel.setGravity(Gravity.CENTER);
        dayLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(older);
        nav.addView(dayLabel);
        nav.addView(newer);
        appsRoot.addView(nav);

        final TextView body = new TextView(host);
        body.setTextColor(Color.parseColor("#E8ECF3"));
        body.setTextSize(13);
        body.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        body.setBackgroundColor(Color.parseColor("#1A1D26"));
        body.setTextIsSelectable(true);
        body.setText("读取中…");
        appsRoot.addView(body);

        final List<String> days = new ArrayList<>();
        final int[] idx = {0};

        final Runnable render = new Runnable() {
            @Override
            public void run() {
                if (days.isEmpty()) {
                    dayLabel.setText("没有记录");
                    body.setText("这个应用还没有可显示的内容。");
                    older.setEnabled(false);
                    newer.setEnabled(false);
                    return;
                }
                final String day = days.get(idx[0]);
                final int i = idx[0];
                final int total = days.size();
                dayLabel.setText(day + "　" + (i + 1) + " / " + total + " 天");
                older.setEnabled(i < total - 1);
                newer.setEnabled(i > 0);
                body.setText("读取中…");
                new Thread(() -> {
                    LogStore store = LogStore.get(host.getFilesDir());
                    List<LogStore.Row> mine = store.readAppDay(st.app, day);
                    List<Burst> bursts = Burst.groupNewestFirst(mine);
                    final android.text.SpannableStringBuilder sb =
                            new android.text.SpannableStringBuilder();
                    host.span(sb, "共 " + bursts.size() + " 段　原始 " + mine.size() + " 个版本\n",
                            Color.parseColor("#8B95A7"), 0.9f, false);
                    host.span(sb, "同一次连续输入只显示最后成型的整段。\n",
                            Color.parseColor("#8B95A7"), 0.9f, false);
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
                        host.span(sb, h.toString() + "\n", Color.parseColor("#5B9DFF"), 0.85f, false);
                        host.span(sb, b.text + "\n", Color.parseColor("#E8ECF3"), 1.0f, false);
                    }
                    host.ui.post(() -> body.setText(sb));
                }, "typelog-appday").start();
            }
        };

        older.setOnClickListener(v -> {
            if (idx[0] < days.size() - 1) {
                idx[0]++;
                render.run();
            }
        });
        newer.setOnClickListener(v -> {
            if (idx[0] > 0) {
                idx[0]--;
                render.run();
            }
        });

        // 先拿"这个应用有哪些天"，再渲染最新的一天
        new Thread(() -> {
            LogStore store = LogStore.get(host.getFilesDir());
            List<String> ds = store.appDays(st.app);
            host.ui.post(() -> {
                days.clear();
                days.addAll(ds);
                idx[0] = 0;
                render.run();
            });
        }, "typelog-appdays").start();
    }
}
