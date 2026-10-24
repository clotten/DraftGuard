package com.draftguard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具页文案的离线测试（纯逻辑，不碰 Android API）。
 *
 * <p>这些文案以前散在 {@code refreshTools()} 里和 View 交织，改一个字都无法验证；
 * 抽成 {@link ToolsText} 的纯函数后，可以像这样断言。
 */
public class ToolsTextTest {

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

    private static LogStore.Row row(String app, String text, boolean comp, int chars) {
        LogStore.Row r = new LogStore.Row();
        r.app = app;
        r.text = text;
        r.comp = comp;
        r.chars = chars;
        r.ts = "2026-10-24T21:33:01.000";
        r.minute = "21:33";
        return r;
    }

    private static void testStats() {
        System.out.println("1) 统计卡片文案（statsText）");
        List<LogStore.Row> rows = new ArrayList<>();
        rows.add(row("com.tencent.mobileqq", "看一眼就懂了", false, 6));
        rows.add(row("com.tencent.mobileqq", "合并", true, 2));
        rows.add(row("com.tencent.mm", "", false, 0));      // 空文本（分段边界）不计入
        rows.add(row("com.tencent.mm", "实习三千五", false, 5));

        String s = ToolsText.statsText(rows, 1300 * 1024);
        check("只统计有正文的版本（3 条）", s.contains("今天落盘版本数：3"), s);
        check("统计输入法未上屏（1 条）", s.contains("其中输入法未上屏：1 条"), s);
        check("占用空间按 KB 显示", s.contains("占用空间：1300 KB"), s);
        check("包含最后一条正文", s.contains("实习三千五"), s);
        check("包含保存位置说明", s.contains("应用私有目录"), s);

        String small = ToolsText.statsText(new ArrayList<LogStore.Row>(), 512);
        check("空列表 + 小体积用字节", small.contains("占用空间：512 字节"), small);
        check("空列表不输出最后一条", !small.contains("最后一条"), small);
    }

    private static void testApps() {
        System.out.println("2) 应用清单文案（appsText）");
        List<LogStore.Row> rows = new ArrayList<>();
        rows.add(row("com.tencent.mobileqq", "abc", false, 3));
        rows.add(row("com.tencent.mobileqq", "abcd", false, 4));
        rows.add(row("com.tencent.mm", "你好", false, 2));

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("com.tencent.mobileqq", "QQ");
        labels.put("com.tencent.mm", "微信");

        String s = ToolsText.appsText(rows, labels);
        check("用显示名而不是包名", s.contains("· QQ") && s.contains("· 微信"), s);
        check("同时保留包名", s.contains("com.tencent.mobileqq"), s);
        check("QQ 两条", s.contains("2 条"), s);
        check("最长字数取最大值（4）", s.contains("最长 4 字"), s);

        String empty = ToolsText.appsText(new ArrayList<LogStore.Row>(), labels);
        check("没有记录时给出提示", empty.contains("今天还没有记录"), empty);
    }

    public static void main(String[] args) {
        testStats();
        testApps();
        System.out.println();
        System.out.println("结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
