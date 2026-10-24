package com.draftguard;

import java.util.HashMap;
import java.util.Map;

/**
 * 采集状态机：**去抖、挂起版本、清空抢救、删除过滤**都在这里。
 *
 * <h3>为什么它必须单独存在</h3>
 * 这段逻辑原本是 {@code TypelogService.handleText} 里的 130 行，夹在
 * "读无障碍事件"和"写数据库"之间。它是整个项目**唯一致命的地方**：
 * 判错一次就是静默丢字，而且不会报错、不会崩溃，用户几天后才发现。
 * 上午为了"删完只留下一个 Y"这个 bug 排查了四轮，就是因为这段逻辑既不能单测、
 * 也无法从外部观察。
 *
 * <h3>它是什么、不是什么</h3>
 * <ul>
 *   <li><b>是</b>：每个输入框的状态（{@link Pending}）+ "这次事件该怎么办"的决策</li>
 *   <li><b>不是</b>：不碰 Android API、不写库、不记日志、不知道时间</li>
 * </ul>
 * 调用方拿到 {@link Decision} 后自己决定怎么执行（写库、记账、打诊断）。
 * 正因如此，本类可以在普通 JVM 上跑测试（见 {@code test/CaptureStateTest.java}）。
 *
 * <h3>三条容易改错的规则（都有测试守着）</h3>
 * <ol>
 *   <li><b>清空时先抢救</b>：去抖期间内存里那一版是唯一副本，输入框被清空（发送）
 *       时必须先把它写掉，否则"发送前敲的最后一个字"会消失。</li>
 *   <li><b>只救一次性清空</b>：上一步在变短（逐个删除）时不能救，
 *       否则救下来的只是删除残渣（「Y」）。</li>
 *   <li><b>重复事件不改趋势</b>：输入法每次删除会先补发一个"内容相同"的事件，
 *       它不能把"上一步在变短"覆盖成 false，否则紧接着的清空就被误判成发送。</li>
 * </ol>
 */
final class CaptureState {

    /** 一次事件的处置方式 */
    enum Kind {
        /** 立刻落盘（超大文本，不值得挂在内存里） */
        COMMIT_NOW,
        /** 挂起，等去抖时间到再落盘 */
        SCHEDULE,
        /** 丢弃本次，但要先把 {@link Decision#flushText} 那一版写掉（发送/按钮清空） */
        FLUSH_THEN_SKIP,
        /** 丢弃本次，不写任何东西 */
        SKIP
    }

    /** 跳过原因（对应界面上的各类计数器） */
    enum Skip {
        NONE,
        /** 清空之后的截断碎片 */
        FRAGMENT,
        /** 删除操作（按设置不记录） */
        DELETE,
        /** 占位提示 / 单字碎片 */
        NOISE
    }

    /** 采集相关的设置快照（避免本类直接依赖 Prefs） */
    static final class Settings {
        boolean ignoreDeletions = true;
        int minChars = 1;
        int cacheLimit = 200_000;

        Settings(boolean ignoreDeletions, int minChars, int cacheLimit) {
            this.ignoreDeletions = ignoreDeletions;
            this.minChars = minChars;
            this.cacheLimit = cacheLimit;
        }
    }

    /** 决策结果 */
    static final class Decision {
        Kind kind = Kind.SKIP;
        Skip skip = Skip.NONE;
        /** 本次要写的文本（COMMIT_NOW / SCHEDULE 时有意义） */
        String text = "";
        /** 不为 null 表示"先把这一版写掉"（FLUSH_THEN_SKIP） */
        String flushText;
        boolean comp;
        int delta;
        /** 诊断行（清空那一刻的判定依据，便于远程排查） */
        String note = "";
        /**
         * 是否要撤掉当前挂起的去抖定时器。
         *
         * <p><b>这是本类最容易踩坏的一条契约。</b>SKIP 时必须为 false ——
         * 因为"打几个字又立刻删掉"时，那几个字还挂在定时器上，
         * 一旦把定时器撤掉，尾巴就永远不会落盘（用户实测：打「…厉害的」后
         * 又打了「大肥鱼」立刻删，记录里只有「…厉害的」）。
         */ 
        boolean cancelPending;
    }

    /** 每个输入框的状态 */
    static final class Pending {
        String text = "";
        String lastWriteText;
        String lastWriteMinute;
        boolean prevShrunk;
        String lastNonEmptyText;
        String minute = "";
        int chars;
        boolean comp;
    }

    private final Map<String, Pending> pending = new HashMap<>();

    Pending pendingOf(String field) {
        return pending.get(field);
    }

    void clear() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }

    /**
     * 收到一次文本变化，返回该怎么办。
     *
     * @param field  输入框标识
     * @param text   输入框此刻的完整文本
     * @param comp   是否处于输入法未上屏状态
     * @param minute 当前分钟（HH:mm）
     */
    Decision onText(String field, String text, boolean comp, String minute, Settings s) {
        Decision d = new Decision();
        Pending p = pending.get(field);
        if (p == null) {
            p = new Pending();
            pending.put(field, p);
        }

        // ① 清空之后的截断碎片（删除的尾巴）
        if (CaptureDecisions.isDeletionFragment(p.chars, p.lastNonEmptyText, text)) {
            d.kind = Kind.SKIP;
            d.skip = Skip.FRAGMENT;
            d.cancelPending = false;      // 挂起的尾巴要照常落盘
            return d;
        }
        if (!text.isEmpty()) {
            p.lastNonEmptyText = text;
        }

        // ② 本事件是否"变短"。清空事件本身也是变短，所以判断要靠**上一个事件**。
        final boolean shrunkNow = CaptureDecisions.isShrink(p.chars, text);
        final int added = addedChars(p.chars, p.text, text);

        // ③ 删除（按设置不记录）
        if (s.ignoreDeletions && added <= 0) {
            return handleClearOrDelete(d, p, text, comp, minute, shrunkNow);
        }
        // ④ 全是空白
        if (added <= 0 && text.trim().isEmpty()) {
            return handleClearOrDelete(d, p, text, comp, minute, shrunkNow);
        }
        // ⑤ 新建输入框里只有一个字符：多半是打了一个字又立刻删掉
        if (s.minChars > 1 && p.chars == 0 && text.trim().length() < 2) {
            d.kind = Kind.SKIP;
            d.skip = Skip.NOISE;
            d.cancelPending = false;
            return d;
        }
        // ⑥ 输入框首条就是默认占位文字
        if (p.chars == 0 && PlainText.looksLikeEmptyFieldHint(text)) {
            d.kind = Kind.SKIP;
            d.skip = Skip.NOISE;
            d.cancelPending = false;
            return d;
        }
        // ⑦ 超大文本：不去抖，直接写（免得内存里挂着几十兆）
        if (text.length() > s.cacheLimit) {
            updateState(p, text, comp, minute, shrunkNow);
            d.kind = Kind.COMMIT_NOW;
            d.cancelPending = true;       // 马上自己写，旧定时器要撤
            d.text = text;
            d.comp = comp;
            d.delta = 0;
            return d;
        }
        // ⑧ 正常路径：去抖
        int delta = text.length() - p.chars;
        updateState(p, text, comp, minute, shrunkNow);
        d.kind = Kind.SCHEDULE;
        d.cancelPending = true;           // 重新挂起，旧的撤掉
        d.text = text;
        d.comp = comp;
        d.delta = delta;
        return d;
    }

    /** 清空 / 删除：决定"救不救挂起的那一版" */
    private Decision handleClearOrDelete(Decision d, Pending p, String text,
                                         boolean comp, String minute, boolean shrunkNow) {
        if (text.isEmpty()) {
            // 一次性清空（发送/按钮）才救；逐个删除到空时不救，否则救下来的是残渣
            d.note = "CLEAR chars=" + p.chars + " prevShrunk=" + p.prevShrunk
                    + " prev=<" + oneLine(p.text, 12) + "> -> "
                    + (p.prevShrunk ? "skip(fragment)" : "flush");
            if (p.prevShrunk) {
                d.kind = Kind.SKIP;
                d.skip = Skip.FRAGMENT;
                d.cancelPending = false;   // 逐个删除：让挂起的尾巴照常落盘
            } else {
                d.kind = Kind.FLUSH_THEN_SKIP;
                d.cancelPending = true;    // 一次性清空：自己写，撤掉定时器
                d.flushText = p.text;
                d.text = p.text;
                d.comp = p.comp;
            }
            p.prevShrunk = false;      // 已经空了，下一次从头算
            p.text = "";
            p.chars = 0;
            p.comp = comp;
            p.minute = minute;
            return d;
        }
        // 还在变短（逐个删除）：只更新趋势，不写
        // 关键：内容没变的重复事件不得覆盖趋势（输入法每次删除都会补发一个）
        if (CaptureDecisions.shouldUpdateTrend(text, p.text)) {
            p.prevShrunk = shrunkNow;
        }
        p.text = text;
        p.chars = text.length();
        p.comp = comp;
        p.minute = minute;
        d.kind = Kind.SKIP;
        d.skip = Skip.DELETE;
        d.cancelPending = false;          // 删除不打断挂起的落盘
        return d;
    }

    private static void updateState(Pending p, String text, boolean comp,
                                    String minute, boolean shrunkNow) {
        // 重复事件不改趋势判断（同上）
        if (!text.equals(p.text)) {
            p.prevShrunk = shrunkNow;
        }
        p.text = text;
        p.chars = text.length();
        p.comp = comp;
        p.minute = minute;
    }

    /**
     * 这一版比上一版"多了几个字"。
     *
     * <p>倒着加的输入法（某些九宫格）会先出现占位字符，所以**同长度但内容不同**
     * 按"新增"处理，只有真的变短才算删除。
     */
    static int addedChars(int charsBefore, String prev, String text) {
        if (text == null) {
            return 0;
        }
        if (charsBefore <= 0) {
            return text.length();
        }
        if (prev == null) {
            prev = "";
        }
        if (text.length() < prev.length()) {
            return 0;
        }
        if (text.length() == prev.length()) {
            return text.equals(prev) ? 0 : 1;
        }
        int common = 0;
        int min = Math.min(text.length(), prev.length());
        while (common < min && text.charAt(common) == prev.charAt(common)) {
            common++;
        }
        if (text.length() - prev.length() >= 2 && common == 0) {
            return text.length();       // 完全重写
        }
        return text.length() - prev.length();
    }

    /** 单行化并截断（诊断输出用，避免把换行塞进 logcat） */
    static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String v = s.replace('\n', ' ').replace('\r', ' ');
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }
}
