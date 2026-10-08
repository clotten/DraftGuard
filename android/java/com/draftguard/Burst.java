package com.draftguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 把"逐字版本流"合并成"一段段完整的话"，用于给人看的视图。
 *
 * 背景：存储层为了防丢，会把每一次文本变化都落盘（你 → 你好 → 你好呀 → …）。
 * 原始数据必须保留 —— 崩在"你好呀"那一步，能捞回来的就是当时那一版。
 * 但**看的时候**不该显示这个增量过程，人想看的是"我最后打出来的整段话"。
 *
 * 合并规则（连续两条满足才算同一段）：
 *   · 同一个应用、同一个输入框
 *   · 时间间隔不超过 GAP_MS（默认 5 分钟）
 *   · 后一版是前一版的延伸：以前一版为前缀，或反之（允许中间插字的少量回退）
 *
 * 合并后只保留最后那一版（即这段的最终形态），并记下这段包含多少个版本。
 */
final class Burst {

    /** 超过这个间隔就算"另一段"，不会跟上一段合并 */
    static final long GAP_MS = 5 * 60 * 1000L;

    String app = "";
    String field = "";
    String text = "";
    String firstTs = "";
    String lastTs = "";
    String minute = "";
    int versions = 1;

    /** 这一段的最终形态是否有未上屏（拼音）状态 */
    boolean comp;

    static List<Burst> group(List<LogStore.Row> rows) {
        List<Burst> raw = new ArrayList<>();
        for (LogStore.Row r : rows) {
            raw.add(fromRow(r));
        }
        // 按时间正序处理（输入是顺序就是这个顺序，但保险起见排一次）
        Collections.sort(raw, new Comparator<Burst>() {
            @Override
            public int compare(Burst a, Burst b) {
                return Long.compare(msOf(a.firstTs), msOf(b.firstTs));
            }
        });

        List<Burst> out = new ArrayList<>();
        Burst cur = null;
        for (Burst b : raw) {
            if (cur != null && sameBurst(cur, b)) {
                cur.text = b.text;
                cur.lastTs = b.lastTs;
                cur.minute = b.minute;
                cur.comp = b.comp;
                cur.versions++;
            } else {
                cur = b;
                out.add(cur);
            }
        }
        return out;
    }

    /** 合并后按时间倒序（新的在前），跟原来列表的习惯一致 */
    static List<Burst> groupNewestFirst(List<LogStore.Row> rows) {
        List<Burst> list = group(rows);
        Collections.reverse(list);
        return list;
    }

    private static boolean sameBurst(Burst cur, Burst next) {
        if (!cur.app.equals(next.app) || !cur.field.equals(next.field)) {
            return false;
        }
        if (msOf(next.firstTs) - msOf(cur.lastTs) > GAP_MS) {
            return false;
        }
        String a = cur.text == null ? "" : cur.text;
        String b = next.text == null ? "" : next.text;
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }
        // 后一版是前一版的延伸，或反之（允许回退：删了又打属于同一段写作过程）
        return b.startsWith(a) || a.startsWith(b);
    }

    private static Burst fromRow(LogStore.Row r) {
        Burst b = new Burst();
        b.app = r.app == null ? "" : r.app;
        b.field = r.field == null ? "" : r.field;
        b.text = r.text == null ? "" : r.text;
        b.firstTs = r.ts == null ? "" : r.ts;
        b.lastTs = b.firstTs;
        b.minute = r.minute == null ? "" : r.minute;
        b.comp = r.comp;
        return b;
    }

    /** "2026-10-08T23:04:01.234" -> 毫秒；拿不到就返回 0 */
    static long msOf(String iso) {
        if (iso == null || iso.length() < 23) {
            return 0L;
        }
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US);
            java.util.Date d = f.parse(iso.substring(0, 23));
            return d == null ? 0L : d.getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }
}
