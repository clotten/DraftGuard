package com.draftguard;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把合并后的段落渲染成**易读的文本块**。
 *
 * 为什么要单独做：原来是把 918 段直接拼成一个字符串塞进一个 TextView ——
 * 结果是一整面文字墙，长内容（实测最长 228 字）与下一段糊在一起，
 * 根本没法"一段一段"地看。
 *
 * 这里的做法：
 *   · 每段的"时间 + 应用 + 字数"做成**小号灰字页眉**，与正文视觉分离
 *   · 正文单独占段，长文本按原文换行，不与页眉混排
 *   · 段与段之间用分隔线，一眼能数清有几段
 *   · 应用名用主题色，扫视时容易定位
 */
final class BurstRenderer {

    private static final int COL_DIM = Color.parseColor("#8B95A7");
    private static final int COL_ACCENT = Color.parseColor("#5B9DFF");
    private static final int COL_FG = Color.parseColor("#E8ECF3");

    private BurstRenderer() {
    }

    static CharSequence render(List<Burst> bursts, Map<String, String> labels,
                              int totalRows, boolean truncated, int maxSegments) {
        SpannableStringBuilder sb = new SpannableStringBuilder();

        // 顶部说明
        String head = "【合并视图】共 " + bursts.size() + " 段"
                + (truncated ? "（仅显示最近 " + maxSegments + " 段）" : "")
                + "　原始 " + totalRows + " 个版本已全部存盘\n"
                + "同一次连续输入只显示最后成型的整段。\n";
        append(sb, head, COL_DIM, 0.85f, false);

        if (bursts.isEmpty()) {
            append(sb, "\n该范围内没有记录。换个时间范围或应用再试。\n", COL_DIM, 1f, false);
            return sb;
        }

        for (Burst b : bursts) {
            // 段间分隔
            append(sb, "\n", COL_FG, 1f, false);

            // 页眉：时间 · 应用 · 字数 · 合并版本数
            StringBuilder h = new StringBuilder();
            h.append(b.firstTs, 11, 16);
            if (!b.firstTs.substring(11, 16).equals(b.lastTs.substring(11, 16))) {
                h.append("–").append(b.lastTs, 11, 16);
            }
            h.append("　").append(name(labels, b.app));
            h.append("　").append(b.text.length()).append(" 字");
            if (b.versions > 1) {
                h.append("　合并 ").append(b.versions).append(" 版");
            }
            if (b.comp) {
                h.append("　末尾未上屏");
            }
            append(sb, h.toString(), COL_ACCENT, 0.78f, false);

            // 正文：单独起行，长文本原样换行
            append(sb, "\n", COL_FG, 1f, false);
            append(sb, b.text, COL_FG, 1.05f, false);
            append(sb, "\n", COL_FG, 1f, false);
        }

        if (truncated) {
            append(sb, "\n… 还有更早的记录未显示。收窄时间范围，或点「导出到下载目录」看全部。\n",
                    COL_DIM, 0.85f, false);
        }
        return sb;
    }

    /** 逐条原始版本视图 */
    static CharSequence renderRaw(List<LogStore.Row> rows, Map<String, String> labels,
                                 int shown, boolean truncated) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String head = "【逐条视图】显示 " + shown + " / " + rows.size() + " 个原始版本（新→旧）\n"
                + "这是每次文本变化都存一版的真相，用于排查；看内容请切回合并视图。\n";
        append(sb, head, COL_DIM, 0.85f, false);

        for (LogStore.Row r : rows) {
            append(sb, "\n", COL_FG, 1f, false);
            StringBuilder h = new StringBuilder();
            h.append(r.ts, 11, 19).append("　").append(name(labels, r.app))
             .append("　").append(r.chars).append(" 字");
            if (r.comp) {
                h.append("　未上屏");
            }
            append(sb, h.toString(), COL_ACCENT, 0.78f, false);
            append(sb, "\n", COL_FG, 1f, false);
            append(sb, r.text, COL_FG, 1.05f, false);
            append(sb, "\n", COL_FG, 1f, false);
        }
        if (truncated) {
            append(sb, "\n… 还有更多未显示，请收窄范围或导出查看。\n", COL_DIM, 0.85f, false);
        }
        return sb;
    }

    static String name(Map<String, String> labels, String app) {
        String n = labels == null ? null : labels.get(app);
        return (n == null || n.isEmpty()) ? app : n;
    }

    private static void append(SpannableStringBuilder sb, String text, int color,
                               float scale, boolean bold) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        if (color != 0) {
            sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (scale != 1f) {
            sb.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) {
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 从段落里取出出现过的应用（用于生成筛选列表） */
    static List<String> appsOf(List<Burst> bursts) {
        Set<String> set = new LinkedHashSet<>();
        for (Burst b : bursts) {
            set.add(b.app);
        }
        return new ArrayList<>(set);
    }
}
