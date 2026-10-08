package com.draftguard;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并视图（Burst）的离线测试。
 *
 * 重点验证：改错别字时，错别字版本与改对后的版本会合并成一段（只显示最后正确的），
 * 而不是各显示一段。同时确认"明显是另一段话"时不会被错误合并。
 *
 * 跑在普通 JVM 上，不需要设备。
 */
public class BurstTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok, String extra) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (ok ? "" : "   <- " + extra));
        if (ok) pass++; else fail++;
    }

    /** 造一条记录，ts 用"分钟偏移"表示，方便控制时间间隔 */
    static LogStore.Row row(String app, String field, String text, int minutesAgo) {
        LogStore.Row r = new LogStore.Row();
        long ms = 1_700_000_000_000L - minutesAgo * 60_000L;
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US);
        r.ts = f.format(new java.util.Date(ms));
        r.minute = r.ts.substring(11, 16);
        r.app = app;
        r.field = field;
        r.text = text;
        r.chars = text.length();
        r.comp = false;
        return r;
    }

    static List<LogStore.Row> stream(String app, String field, String base, int startMin,
                                     String... versions) {
        List<LogStore.Row> rows = new ArrayList<LogStore.Row>();
        int m = startMin;
        for (String v : versions) {
            rows.add(row(app, field, v, m));
            m--;   // 越往后越"新"（分钟数越小）
        }
        // 反转为时间正序
        java.util.Collections.reverse(rows);
        return rows;
    }

    public static void main(String[] args) {
        final String APP = "com.tencent.mm";
        final String F = APP + "#field";

        System.out.println("== 1. 纯粹逐字输入（应合并成一段，只显示最后）==");
        List<Burst> b1 = Burst.group(stream(APP, F, "", 5, "你", "你好", "你好呀", "你好呀我"));
        check("4 个版本合并为 1 段", b1.size() == 1, "实际 " + b1.size());
        check("取最后一版内容", b1.size() == 1 && "你好呀我".equals(b1.get(0).text),
                b1.isEmpty() ? "空" : b1.get(0).text);
        check("版本数记为 4", b1.size() == 1 && b1.get(0).versions == 4,
                b1.isEmpty() ? "空" : String.valueOf(b1.get(0).versions));

        System.out.println("\n== 2. 改错别字（核心场景：错字版与改对版必须合并）==");
        // 你好呀我饿 → 删成 你好呀我 → 改成 你好呀我很饿
        List<Burst> b2 = Burst.group(stream(APP, F, "", 5,
                "你好", "你好呀", "你好呀我", "你好呀我饿", "你好呀我", "你好呀我很", "你好呀我很饿"));
        check("错字与改对合并为 1 段", b2.size() == 1, "实际 " + b2.size() + " 段，内容：" + dump(b2));
        check("只显示改对后的整句", b2.size() == 1 && "你好呀我很饿".equals(b2.get(0).text),
                b2.isEmpty() ? "空" : b2.get(0).text);
        System.out.println("     调试: " + dump(b2));
        // 该序列只有"删除"和"等长替换"能被判定；末尾追加与末字前插入形态相同，无法区分
        check("统计出修改次数", b2.size() == 1 && b2.get(0).edits >= 1,
                b2.isEmpty() ? "空" : String.valueOf(b2.get(0).edits));

        System.out.println("\n== 2b. 多次修正（连续改好几个错字）==");
        List<Burst> b2b = Burst.group(stream(APP, F, "", 5,
                "今天天气不", "今天天气不好", "今天天汽不好", "今天天气很好",
                "今天天气很好啊", "今天天气很好呀"));
        check("多次修正仍为 1 段", b2b.size() == 1, dump(b2b));
        check("只显示最终版本", b2b.size() == 1 && "今天天气很好呀".equals(b2b.get(0).text),
                b2b.isEmpty() ? "空" : b2b.get(0).text);
        check("统计出多次修正", b2b.size() == 1 && b2b.get(0).edits >= 3,
                b2b.isEmpty() ? "空" : String.valueOf(b2b.get(0).edits));
        System.out.println("\n== 3. 改一个词（中间替换）==");
        List<Burst> b3 = Burst.group(stream(APP, F, "", 5,
                "今天天气不错", "今天天气不好", "今天天气很好"));
        check("同句改词仍为 1 段", b3.size() == 1, dump(b3));
        check("取最后改法", b3.size() == 1 && "今天天气很好".equals(b3.get(0).text),
                b3.isEmpty() ? "空" : b3.get(0).text);

        System.out.println("\n== 4. 中间插字（分叉幅度极小）==");
        List<Burst> b4 = Burst.group(stream(APP, F, "", 5,
                "我去学校", "我去学校了", "我去学校了吧"));
        check("插字合并为 1 段", b4.size() == 1, dump(b4));

        System.out.println("\n== 5. 时间间隔过大（应分成两段）==");
        List<LogStore.Row> rows5 = new ArrayList<LogStore.Row>();
        rows5.add(row(APP, F, "第一段话", 30));
        rows5.add(row(APP, F, "第二段话", 20));   // 间隔 10 分钟 > 5 分钟
        List<Burst> b5 = Burst.group(rows5);
        check("间隔 10 分钟分成 2 段", b5.size() == 2, dump(b5));

        System.out.println("\n== 6. 换了应用（应分成两段）==");
        List<LogStore.Row> rows6 = new ArrayList<LogStore.Row>();
        rows6.add(row(APP, F, "微信里的话", 3));
        rows6.add(row("com.tencent.mobileqq", "com.tencent.mobileqq#input", "QQ里的话", 2));
        List<Burst> b6 = Burst.group(rows6);
        check("不同应用分成 2 段", b6.size() == 2, dump(b6));

        System.out.println("\n== 7. 同一个应用里的另一个输入框（应分成两段）==");
        List<LogStore.Row> rows7 = new ArrayList<LogStore.Row>();
        rows7.add(row(APP, APP + "#input_a", "输入框A的话", 3));
        rows7.add(row(APP, APP + "#input_b", "输入框B的话", 2));
        List<Burst> b7 = Burst.group(rows7);
        check("不同输入框分成 2 段", b7.size() == 2, dump(b7));

        System.out.println("\n== 8. 整段重写（几乎无共同开头、长度差大，应分成两段）==");
        List<LogStore.Row> rows8 = new ArrayList<LogStore.Row>();
        rows8.add(row(APP, F, "一二三四五六七八九十", 3));
        rows8.add(row(APP, F, "完全不同的另一句话来了", 2));
        List<Burst> b8 = Burst.group(rows8);
        check("整段重写分成 2 段", b8.size() == 2, dump(b8));

        System.out.println("\n== 9. 清空后重打（应算同一段）==");
        List<Burst> b9 = Burst.group(stream(APP, F, "", 5, "写错了", "", "重新写对"));
        check("清空重打合并为 1 段", b9.size() == 1, dump(b9));
        check("取重打后的内容", b9.size() == 1 && "重新写对".equals(b9.get(0).text),
                b9.isEmpty() ? "空" : b9.get(0).text);

        System.out.println("\n== 10. 倒序输出（新的在前）==");
        List<LogStore.Row> rows10 = new ArrayList<LogStore.Row>();
        rows10.add(row(APP, F, "早的话", 30));
        rows10.add(row(APP, F, "晚的话", 1));
        List<Burst> b10 = Burst.groupNewestFirst(rows10);
        check("新的排在前面", b10.size() == 2 && "晚的话".equals(b10.get(0).text), dump(b10));

        System.out.println("\n== 判据本身（回归：曾经的错误理解）==");
        check("你是我很 以 你是我 开头（是的，所以不能用 startsWith 判改字）",
                "你好呀我很".startsWith("你好呀我"), "前缀判定失效");
        check("等长替换算修正", Burst.isMidEdit("今天天气不好", "今天天气很好"), "未识别");
        check("往后追加不算修正", !Burst.isMidEdit("你好呀我", "你好呀我饿"), "误判为修正");
        check("中间插字（等长可判定时）算修正", Burst.isMidEdit("你好呀我", "你好呀很"), "未识别");
        System.out.println("     注：你好呀我→你好呀我很 与 →你好呀我饿 形态相同，仅凭文本不可区分");
        check("删字算一次修改", Burst.isMidEdit("你好呀我饿", "你好呀我"), "未识别");
        check("短句微调不算整段重写", !Burst.isFullRewrite("你好呀我", "你好呀我很"), "误判为重写");
        check("换句子算整段重写", Burst.isFullRewrite("第一句话在这里哦", "完全不同的另一段内容"), "未识别");

        System.out.println("\n结果：通过 " + pass + " 项，失败 " + fail + " 项");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String dump(List<Burst> bs) {
        StringBuilder sb = new StringBuilder("段数=" + bs.size() + " [");
        for (Burst b : bs) {
            sb.append("\"").append(b.text).append("\"(v").append(b.versions)
              .append(",e").append(b.edits).append(") ");
        }
        return sb.append("]").toString();
    }
}
