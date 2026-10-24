package com.draftguard;

/**
 * 采集决策的离线测试（纯逻辑，不碰 Android API）。
 *
 * <h3>为什么这个测试重要</h3>
 * 这里每一条断言，对应的都是**真机上花了很久才查出来的 bug**，
 * 而且当时的排查过程都很痛苦 —— 因为决策逻辑埋在 Service 里、无法单测，
 * 只能靠"把决策写进诊断日志 → 让用户复现 → 读日志"来反推。
 *
 * 抽成纯函数之后，这些场景变成了秒级可验证的断言。
 */
public class DecisionsTest {

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

    /** isDeletionFragment：清空之后出现的截断碎片 */
    private static void testDeletionFragment() {
        System.out.println("1) 删除碎片判定（「删完只留下一个 Y」那个 bug）");

        check("刚清空后出现的「J」是碎片（JT5 的前缀）",
                CaptureDecisions.isDeletionFragment(0, "JT5514607829298", "J"), null);

        check("刚清空后出现的「JT5」也是碎片",
                CaptureDecisions.isDeletionFragment(0, "JT5514607829298", "JT5"), null);

        check("刚清空后出现的完整原文不算碎片（长度相等）",
                !CaptureDecisions.isDeletionFragment(0, "JT5514607829298", "JT5514607829298"), null);

        check("不是前缀的全新内容不算碎片",
                !CaptureDecisions.isDeletionFragment(0, "JT5514607829298", "你好"), null);

        check("清空之前那一版非空时（chars>0）不判碎片",
                !CaptureDecisions.isDeletionFragment(5, "abcdef", "ab"), null);

        check("空文本不算碎片",
                !CaptureDecisions.isDeletionFragment(0, "abc", ""), null);

        check("没有历史文本时不算碎片",
                !CaptureDecisions.isDeletionFragment(0, null, "J"), null);
    }

    /** isSameMinuteDedup：同分钟内容相同才去重 */
    private static void testDedup() {
        System.out.println("2) 同分钟去重（「53 个版本」和「8 次粘贴只记 2 次」两个 bug）");

        check("同分钟 + 内容相同 → 去重",
                CaptureDecisions.isSameMinuteDedup("777440361990583", "00:40",
                        "777440361990583", "00:40"), null);

        check("同分钟 + 内容不同 → 不去重（重复粘贴要能记录）",
                !CaptureDecisions.isSameMinuteDedup("777440361990583", "00:40",
                        "77744036199058", "00:40"), null);

        check("跨分钟 + 内容相同 → 不去重",
                !CaptureDecisions.isSameMinuteDedup("777440361990583", "00:41",
                        "777440361990583", "00:40"), null);

        check("没有落盘记录时不去重",
                !CaptureDecisions.isSameMinuteDedup("abc", "00:40", null, null), null);

        check("null 文本不去重（防 NPE）",
                !CaptureDecisions.isSameMinuteDedup(null, "00:40", "abc", "00:40"), null);
    }

    /** isShrink + shouldUpdateTrend：趋势判断（重复事件不得覆盖趋势） */
    private static void testTrend() {
        System.out.println("3) 变短判定与趋势更新（输入法补发重复事件的坑）");

        check("15 字 → 3 字算变短", CaptureDecisions.isShrink(15, "JT5"), null);
        check("3 字 → 15 字不算变短", !CaptureDecisions.isShrink(3, "JT5514607829298"), null);
        check("长度不变不算变短", !CaptureDecisions.isShrink(3, "JT5"), null);
        check("清空时（chars=0）不算变短", !CaptureDecisions.isShrink(0, ""), null);

        check("内容变了 → 要更新趋势",
                CaptureDecisions.shouldUpdateTrend("JT5", "JT55"), null);
        check("内容没变（输入法补发的重复事件）→ 不更新趋势 ← 这个 bug 的根因",
                !CaptureDecisions.shouldUpdateTrend("JT5", "JT5"), null);
        check("null 保护", !CaptureDecisions.shouldUpdateTrend(null, "abc"), null);
    }

    public static void main(String[] args) {
        testDeletionFragment();
        testDedup();
        testTrend();

        System.out.println();
        System.out.println("结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
