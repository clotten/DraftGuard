package com.draftguard;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记录列表的适配器：一张卡片 = 一条记录（或一段合并后的草稿）。
 *
 * <h3>为什么从 MainActivity 里搬出来</h3>
 * 它原本是 Activity 的私有内部类（约 140 行），导致 Activity 里混杂了
 * "列表怎么渲染"和"页面怎么装配"两件事。搬出来之后：
 * <ul>
 *   <li>渲染逻辑只依赖 {@link MainActivity} 暴露的几个工具（dp / rounded / 图标 / 应用名）</li>
 *   <li>长按删除通过回调回到宿主，适配器不需要知道"删除"怎么实现</li>
 *   <li>图标请求（{@code requestIcon}）本来只有它在用，一并搬进来</li>
 * </ul>
 *
 * <h3>列表项是两种类型</h3>
 * <ul>
 *   <li>{@link Burst} —— 合并视图里的一段（可能由多个版本合成）</li>
 *   <li>{@link LogStore.Row} —— 逐条视图里的一版</li>
 * </ul>
 * 两者都按同一张卡片渲染，只是头部信息不同。
 */
final class RecordAdapter extends android.widget.BaseAdapter {

    private final MainActivity host;
    private final List<Object> items;
    /** 已经请求过图标的包名（避免重复请求） */
    private final Set<String> iconRequested = new HashSet<>();

    RecordAdapter(MainActivity host, List<Object> items) {
        this.host = host;
        this.items = items;
    }

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
        final Object item = items.get(position);
        bindRow(row, item);
        // 长按删除：隐私数据需要能单点删掉（具体怎么删由宿主决定）
        row.setOnLongClickListener(v -> {
            host.confirmDelete(item);
            return true;
        });
        return row;
    }

    /** 行结构：图标 | （应用名 + 元信息） / 正文 */
    private LinearLayout buildRow() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(host.rounded(MainActivity.COL_CARD, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(host.dp(12), host.dp(4), host.dp(12), host.dp(4));
        row.setLayoutParams(lp);
        row.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(12));

        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(host);
        int sz = host.dp(30);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = host.dp(10);
        icon.setLayoutParams(ilp);
        icon.setTag("icon");
        head.addView(icon);

        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(host);
        name.setTextColor(MainActivity.COL_FG);
        name.setTextSize(14);
        name.setTag("name");
        col.addView(name);

        TextView meta = new TextView(host);
        meta.setTextColor(MainActivity.COL_DIM);
        meta.setTextSize(11);
        meta.setPadding(0, host.dp(2), 0, 0);
        meta.setTag("meta");
        col.addView(meta);

        head.addView(col);
        row.addView(head);

        TextView body = new TextView(host);
        body.setTextColor(MainActivity.COL_FG);
        body.setTextSize(14);
        body.setPadding(0, host.dp(8), 0, 0);
        body.setLineSpacing(host.dp(3), 1f);
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
            String today = host.DAY.format(new Date());
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

        name.setText(host.labelOf(app));
        meta.setText(head);
        body.setText(text);

        Drawable d = host.iconCache.get(app);
        if (d != null) {
            icon.setImageDrawable(d);
        } else {
            icon.setImageDrawable(host.letterIcon(host.labelOf(app), app));
            requestIcon(app);
        }
    }

    /** 列表里第一次见到某个应用时，后台取一次它的图标 */
    private void requestIcon(final String pkg) {
        if (iconRequested.contains(pkg)) {
            return;
        }
        iconRequested.add(pkg);
        new Thread(() -> {
            Drawable d = null;
            try {
                d = host.getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable ignored) {
            }
            synchronized (host.iconCache) {
                host.iconCache.put(pkg, d);
            }
            host.ui.post(() -> notifyDataSetChanged());
        }, "typelog-icon").start();
    }
}
