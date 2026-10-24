package com.draftguard;

/**
 * 采集状态机的离线测试 —— **这是全项目最该有测试的一处**。
 *
 * <p>它守护的是"会不会丢字"。历史上每一次丢字都出在这里：
 * <ul>
 *   <li>发送前敲的最后一个字消失（去抖期间那一版没落盘）</li>
 *   <li>删到空只留下「Y」（把删除残渣当草稿救了）</li>
 *   <li>重复事件把"变短"趋势覆盖掉，导致清空被误判成发送</li>
 *   <li>8 次相同粘贴只记 2 次（同分钟去重把正常重复输入吃掉）</li>
 * </ul>
 */
public class CaptureStateTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean ok, String extra) {
        if (ok) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + (extra == null ? "" : extra));
        }
    }

    private static CaptureState.Settings settings(boolean ignoreDeletions, int minChars) {
        return new CaptureState.Settings(ignoreDeletions, minChars, 200_000);
    }

    private static void testNormalTyping() {
        System.out.println("1) 正常输入：去抖，不立即落盘");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = settings(true, 1);
        CaptureState.Decision d = st.onText("f1", "你", false, "21:33", s);
        check("第一次输入 → 挂起（SCHEDULE）", d.kind == CaptureState.Kind.SCHEDULE, d.kind.name());
        check("delta 是全文长度", d.delta == 1, "delta=" + d.delta);

        d = st.onText("f1", "你好", false, "21:33", s);
        check("继续输入 → 仍挂起", d.kind == CaptureState.Kind.SCHEDULE, d.kind.name());
        check("delta 是本轮新增", d.delta == 1, "delta=" + d.delta);
        check("文本正确", "你好".equals(d.text), d.text);
    }

    private static void testClearAfterSend() {
        System.out.println("2) 一次性清空（发送/按钮）→ 先抢救挂起的那一版");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = settings(true, 1);
        st.onText("f1", "我等着你回", false, "21:33", s);
        st.onText("f1", "我等着你回来", false, "21:33", s);

        CaptureState.Decision d = st.onText("f1", "", false, "21:33", s);
        check("清空 → FLUSH_THEN_SKIP", d.kind == CaptureState.Kind.FLUSH_THEN_SKIP,
                d.kind.name());
        check("要抢救的是清空前那一版（含最后一个字）",
                "我等着你回来".equals(d.flushText), String.valueOf(d.flushText));
        check("诊断行写明是 flush", d.note.contains("flush"), d.note);
    }

    private static void testDeleteToEmpty() {
        System.out.println("3) 逐个删除到空 → 不救，避免留下「Y」这种残渣");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = settings(true, 1);
        st.onText("f1", "JT5514607829298", false, "21:33", s);
        // 逐个删除：每次内容都变短
        for (String t : new String[]{"JT551460782929", "JT55146078292", "JT5", "JT", "J"}) {
            st.onText("f1", t, false, "21:33", s);
        }
        CaptureState.Decision d = st.onText("f1", "", false, "21:33", s);
        check("清空 → 不抢救（SKIP）", d.kind == CaptureState.Kind.SKIP, d.kind.name());
        check("记为碎片而不是删除", d.skip == CaptureState.Skip.FRAGMENT, d.skip.name());
        check("诊断行写明 skip(fragment)", d.note.contains("skip(fragment)"), d.note);
        check("flushText 为空（没有要救的）", d.flushText == null, String.valueOf(d.flushText));
    }

    private static void testDuplicateEventDoesNotResetTrend() {
        System.out.println("4) 输入法补发的重复事件不得改变趋势（「Y」bug 的根因）");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = settings(true, 1);
        st.onText("f1", "ABC", false, "21:33", s);
        // 删除一个字符：变短
        st.onText("f1", "AB", false, "21:33", s);
        // 输入法补发"内容相同"的重复事件 —— 它不该把 prevShrunk 覆盖成 false
        st.onText("f1", "AB", false, "21:33", s);
        // 再删
        st.onText("f1", "A", false, "21:33", s);
        // 又补发重复事件
        st.onText("f1", "A", false, "21:33", s);

        CaptureState.Decision d = st.onText("f1", "", false, "21:33", s);
        check("清空时仍判为碎片（趋势没被重复事件冲掉）",
                d.skip == CaptureState.Skip.FRAGMENT && d.kind == CaptureState.Kind.SKIP,
                d.kind + "/" + d.skip + " note=" + d.note);
    }

    private static void testPlainDelete() {
        System.out.println("5) 删除但没清空 → 跳过，状态仍要更新");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = settings(true, 1);
        st.onText("f1", "好", false, "21:33", s);
        st.onText("f1", "好不好", false, "21:33", s);
        CaptureState.Decision d = st.onText("f1", "好不", false, "21:33", s);
        check("变短 → SKIP/DELETE", d.kind == CaptureState.Kind.SKIP
                && d.skip == CaptureState.Skip.DELETE, d.kind + "/" + d.skip);
        // 状态里的文本要跟着变，否则下一次 added 会算错
        CaptureState.Pending p = st.pendingOf("f1");
        check("状态已更新为最新文本", "好不".equals(p.text), p.text);
        check("chars 同步", p.chars == 2, "chars=" + p.chars);
    }

    private static void testIgnoreDeletionsOff() {
        System.out.println("6) 关掉「忽略删除」时，删除也记录");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = settings(false, 1);
        st.onText("f1", "好不好", false, "21:33", s);
        CaptureState.Decision d = st.onText("f1", "好不", false, "21:33", s);
        check("变短 → 仍然挂起记录", d.kind == CaptureState.Kind.SCHEDULE, d.kind.name());
    }

    private static void testDeletionFragment() {
        System.out.println("7) 清空后出现的截断碎片 → 跳过");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = settings(true, 1);
        st.onText("f1", "JT5514607829298", false, "21:33", s);
        st.onText("f1", "", false, "21:33", s);          // 清空
        CaptureState.Decision d = st.onText("f1", "JT5", false, "21:33", s);
        check("「JT5」是碎片 → SKIP/FRAGMENT",
                d.kind == CaptureState.Kind.SKIP && d.skip == CaptureState.Skip.FRAGMENT,
                d.kind + "/" + d.skip);
    }

    private static void testMinChars() {
        System.out.println("8) 最少字数设置：新框里的单字不记");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = settings(true, 2);
        CaptureState.Decision d = st.onText("f1", "我", false, "21:33", s);
        check("minChars=2 时单字 → SKIP/NOISE",
                d.kind == CaptureState.Kind.SKIP && d.skip == CaptureState.Skip.NOISE,
                d.kind + "/" + d.skip);

        CaptureState st2 = new CaptureState();
        CaptureState.Decision d2 = st2.onText("f1", "我们", false, "21:33", s);
        check("两个字 → 正常挂起", d2.kind == CaptureState.Kind.SCHEDULE, d2.kind.name());
    }

    private static void testHugeText() {
        System.out.println("9) 超大文本：不去抖，直接落盘");
        CaptureState st = new CaptureState();
        CaptureState.Settings s = new CaptureState.Settings(true, 1, 10);
        CaptureState.Decision d = st.onText("f1", "0123456789ABCDEF", false, "21:33", s);
        check("超过缓存上限 → COMMIT_NOW", d.kind == CaptureState.Kind.COMMIT_NOW, d.kind.name());
    }

    private static void testAddedChars() {
        System.out.println("10) addedChars：新增字数的判定（删除过滤依赖它）");
        check("空 → 3 个字", CaptureState.addedChars(0, "", "abc") == 3, null);
        check("abc → abcd 新增 1", CaptureState.addedChars(3, "abc", "abcd") == 1, null);
        check("abcd → abc 变短算 0", CaptureState.addedChars(4, "abcd", "abc") == 0, null);
        check("同长度不同内容算 1（倒着加的输入法）",
                CaptureState.addedChars(3, "abc", "abd") == 1, null);
        check("完全重写（无共同前缀且长 2 以上）算全文长度",
                CaptureState.addedChars(3, "abc", "XYZQW") == 5, null);
        check("空前缀文本为 null 也不炸", CaptureState.addedChars(0, null, "x") == 1, null);
    }

    public static void main(String[] args) {
        testNormalTyping();
        testClearAfterSend();
        testDeleteToEmpty();
        testDuplicateEventDoesNotResetTrend();
        testPlainDelete();
        testIgnoreDeletionsOff();
        testDeletionFragment();
        testMinChars();
        testHugeText();
        testAddedChars();

        System.out.println();
        System.out.println("结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
