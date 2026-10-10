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
    /** 把 ISO 时间戳整体平移指定毫秒（用于构造"几小时后"的记录） */
    static String shift(String iso, long deltaMs) {
        try {
            String pat = "yyyy-MM-dd'T'HH:mm:ss.SSS";
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat(pat, java.util.Locale.US);
            java.util.Date d = f.parse(iso);
            return f.format(new java.util.Date(d.getTime() + deltaMs));
        } catch (Exception e) {
            return iso;
        }
    }

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

    /**
     * 秒级粒度的流：用于测试"几秒内快速改字"这类判据。
     * 原有的 stream 是分钟粒度（每条相隔 1 分钟），足够测长期分段，
     * 但测不出"3 秒内整词重打"这种情况。
     */
    static List<LogStore.Row> streamSec(String app, String field, int startSec, String... versions) {
        List<LogStore.Row> rows = new ArrayList<LogStore.Row>();
        int s = startSec;
        for (String v : versions) {
            LogStore.Row r = new LogStore.Row();
            r.app = app;
            r.field = field;
            r.text = v;
            r.chars = v.length();
            r.comp = false;
            r.ts = String.format("2026-01-01T00:%02d:%02d.000", s / 60, s % 60);
            r.minute = String.format("%02d:%02d", s / 60, s % 60);
            rows.add(r);
            s += 3;      // 每条相隔 3 秒
        }
        return rows;
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

    /** 构造一条秒级记录（用于精确控制间隔） */
    static LogStore.Row sec(String app, String field, String text, int secAt) {
        LogStore.Row r = new LogStore.Row();
        r.app = app;
        r.field = field;
        r.text = text;
        r.chars = text.length();
        r.comp = false;
        r.ts = String.format("2026-01-01T00:%02d:%02d.000", secAt / 60, secAt % 60);
        r.minute = String.format("%02d:%02d", secAt / 60, secAt % 60);
        return r;
    }

    /** 发送标记记录（ev=send，无文本），它就是分段用的"真信号" */
    static LogStore.Row sendMark(String app, String field, int secAt) {
        LogStore.Row r = sec(app, field, "", secAt);
        r.ev = "send";
        return r;
    }

    /**
     * 一条消息 → 发送 → 另一条消息。
     *
     * @param withSend 是否插入发送标记：false 用来复现"发送没被检测到"的应用
     */
    static List<LogStore.Row> twoMessages(String app, String field,
                                          String first, String second, boolean withSend) {
        List<LogStore.Row> rows = new ArrayList<LogStore.Row>();
        rows.add(sec(app, field, first, 0));
        if (withSend) {
            rows.add(sendMark(app, field, 2));
        }
        rows.add(sec(app, field, second, 4));
        return rows;
    }

    static void testSendSplitsSimilarShortMessage() {
        final String APP = "com.tencent.mobileqq";
        final String F = APP + "#input";

        System.out.println("\n== 19. 新消息恰好是上文的一个字（有发送信号）==");
        // 上一条「你好」，这一条只发「好」—— 判据 11 会认为"在改同一个词"，
        // 只有发送边界能证明这是两条独立的消息。
        List<Burst> a = Burst.group(twoMessages(APP, F, "你好", "好", true));
        check("有发送信号时必须分成 2 段", a.size() == 2, "实际 " + a.size() + " 段：" + dump(a));
        check("后一段内容是「好」", a.size() == 2 && "好".equals(a.get(1).text),
                a.size() == 2 ? a.get(1).text : dump(a));
        check("前一段仍是「你好」", a.size() == 2 && "你好".equals(a.get(0).text),
                a.size() == 2 ? a.get(0).text : dump(a));

        System.out.println("\n== 20. 同样两条，但发送没被检测到（已知代价）==");
        List<Burst> b = Burst.group(twoMessages(APP, F, "你好", "好", false));
        // 「好」是「你好」的真子串 ⇒ 判据 11 明确排除这种形态，所以即使没有发送信号
        // 也能正确分成两段。否则上一条会在显示里消失（只剩「好」）。
        check("无发送信号时也分成 2 段（子串不算重打）", b.size() == 2,
                "实际 " + b.size() + " 段：" + dump(b));
        check("前一段仍保留为「你好」", b.size() == 2 && "你好".equals(b.get(0).text),
                b.size() == 2 ? b.get(0).text : dump(b));
        // 反例护栏：整词重打（互不为子串）必须仍然合并，否则「来发展→开发者」会被拆开
        List<Burst> b2 = Burst.group(twoMessages(APP, F, "来发展", "开发者", false));
        check("整词重打仍合并（互不为子串）", b2.size() == 1,
                "实际 " + b2.size() + " 段：" + dump(b2));

        System.out.println("\n== 21. 新消息与上文完全无共同字（无发送信号也应分段）==");
        List<Burst> c = Burst.group(twoMessages(APP, F, "你好", "晚安", false));
        check("无共同实义字 → 分成 2 段", c.size() == 2, "实际 " + c.size() + " 段：" + dump(c));

        System.out.println("\n== 22. 连发多个不同单字（有发送信号，逐条发送）==");
        List<LogStore.Row> rows = new ArrayList<LogStore.Row>();
        String[] one = {"一", "二", "三", "四", "五", "六"};
        int t = 0;
        for (String v : one) {
            rows.add(sec(APP, F, v, t));
            rows.add(sendMark(APP, F, t + 1));
            t += 3;
        }
        List<Burst> d = Burst.group(rows);
        check("6 个单字 = 6 段", d.size() == 6, "实际 " + d.size() + " 段：" + dump(d));
        check("段内容按顺序为 一..六",
                d.size() == 6 && "一".equals(d.get(0).text) && "六".equals(d.get(5).text),
                dump(d));

        System.out.println("\n== 23. 补字场景：上一条已发出，本条在其后追加一字 ==");
        // 用户实录：打「越来越神奇里」发出，再打「了」发出。
        List<LogStore.Row> rows2 = new ArrayList<LogStore.Row>();
        rows2.add(sec(APP, F, "越来越神奇里", 0));
        rows2.add(sendMark(APP, F, 1));
        rows2.add(sec(APP, F, "了", 3));
        rows2.add(sendMark(APP, F, 4));
        List<Burst> e = Burst.group(rows2);
        check("两组各 1 段，共 2 段", e.size() == 2, "实际 " + e.size() + " 段：" + dump(e));
        check("第二段是「了」", e.size() == 2 && "了".equals(e.get(1).text),
                e.size() == 2 ? e.get(1).text : dump(e));
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

        System.out.println("\n== 9. 清空是消息边界（聊天场景：不能吞消息）==");
        List<Burst> b9 = Burst.group(stream(APP, F, "", 5,
                "你好", "", "在吗", "", "哈哈"));
        check("三条消息分成 3 段", b9.size() == 3, dump(b9));
        check("三条内容都在", b9.size() == 3
                        && "你好".equals(b9.get(0).text)
                        && "在吗".equals(b9.get(1).text)
                        && "哈哈".equals(b9.get(2).text),
                dump(b9));
        List<Burst> b9b = Burst.group(stream(APP, F, "", 5,
                "今天晚上", "今天晚上吃", "", "走吧"));
        check("清空后新内容独立成段", b9b.size() == 2, dump(b9b));

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

        System.out.println("\n== 15. 中间改一个字（豆包实录：锤他→锤它、可以买→可以吗）==");
        SendBoundary.resetForTest();
        List<Burst> b15 = Burst.group(streamSec(APP, F, 0,
                "没有这个是个实验的臭虫半死不活锤他",
                "没有这个是个实验的臭虫半死不活锤它"));
        check("长句中改 1 字：合并为 1 段", b15.size() == 1, dump(b15));
        check("取改对后的内容", b15.size() == 1
                        && "没有这个是个实验的臭虫半死不活锤它".equals(b15.get(0).text),
                b15.isEmpty() ? "空" : b15.get(0).text);

        List<Burst> b15b = Burst.group(streamSec(APP, F, 0, "这个可以买", "这个可以吗"));
        check("短句中改 1 字：合并", b15b.size() == 1, dump(b15b));

        List<Burst> b15c = Burst.group(streamSec(APP, F, 0, "你好呀", "小朋友"));
        check("无共同开头：仍分段", b15c.size() == 2, dump(b15c));

        System.out.println("\n== 14. 切出去很久回来接着写草稿（应合并）==");
        SendBoundary.resetForTest();   // 见第 12 项说明：必须先清掉全局发送状态
        List<LogStore.Row> b14 = new ArrayList<LogStore.Row>();
        LogStore.Row first14 = row(APP, F, "今天我去超市买了", 0);
        b14.add(first14);
        // 第二条 = 第一条时间 + 3 小时（按第一条实际时间戳推算，避免基准不一致）
        LogStore.Row late = row(APP, F, "今天我去超市买了鸡蛋和牛奶", 0);
        late.ts = shift(first14.ts, 3 * 60 * 60 * 1000L);
        late.minute = late.ts.substring(11, 16);
        b14.add(late);
        check("间隔 3 小时的续写合并为 1 段", Burst.group(b14).size() == 1, dump(Burst.group(b14)));

        List<LogStore.Row> b14b = new ArrayList<LogStore.Row>();
        LogStore.Row first14b = row(APP, F, "今天我去超市买了", 0);
        b14b.add(first14b);
        LogStore.Row other = row(APP, F, "完全不一样的另一句话在这里", 0);
        other.ts = shift(first14b.ts, 3 * 60 * 60 * 1000L);   // 3 小时后，且非续写
        other.minute = other.ts.substring(11, 16);
        b14b.add(other);
        check("间隔 3 小时的非续写分段", Burst.group(b14b).size() == 2, dump(Burst.group(b14b)));

        System.out.println("\n== 13. 整词打错重打（共同开头为 0，仍应合并）==");
        SendBoundary.resetForTest();
        List<Burst> b13 = Burst.group(streamSec(APP, F, 0, "来发展", "开发者"));
        check("短间隔+短内容：合并为 1 段", b13.size() == 1, dump(b13));
        check("取改对后的内容", b13.size() == 1 && "开发者".equals(b13.get(0).text),
                b13.isEmpty() ? "空" : b13.get(0).text);
        List<Burst> b13b = Burst.group(streamSec(APP, F, 0, "你好呀", "小朋友"));
        check("无共同开头也要分段（连发消息）", b13b.size() == 2, dump(b13b));
        List<Burst> b13c = Burst.group(streamSec(APP, F, 0,
                "好耶我要去做一个很长的事情了", "完全不同的另一句长话在这里"));
        check("长内容无共同开头：分段", b13c.size() == 2, dump(b13c));

        System.out.println("\n== 12. 点了发送 ⇒ 必须分段（最可靠的判据）==");
        // 注意：SendBoundary 是全局状态，且记录的是**真实当前时间**，
        // 而测试用的时间戳是固定的过去时间 —— 若不重置，
        // 后面的用例会因为"当前时刻晚于测试记录时刻"而全部被判为"已发送"。
        SendBoundary.resetForTest();
        List<LogStore.Row> rows12 = new ArrayList<LogStore.Row>();
        LogStore.Row r1 = row(APP, F, "你好呀", 1);
        LogStore.Row r2 = row(APP, F, "你好呀我很饿", 0);
        rows12.add(r1);
        rows12.add(r2);
        // 无发送信号时：共同开头很长 → 合并为一段
        check("无发送信号时相似内容合并", Burst.group(rows12).size() == 1, dump(Burst.group(rows12)));
        // 插入一条"发送"记录（夹在两条文本之间）→ 必须分段。
        // 注意：判据来自**落盘的发送记录**，不是内存里的时间戳 ——
        // 后者用"现在"去比历史记录，会把所有历史都判成"已发送"。
        List<LogStore.Row> rows12b = new ArrayList<LogStore.Row>();
        rows12b.add(r1);
        LogStore.Row send = new LogStore.Row();
        send.app = APP;
        send.field = F;
        send.text = "";
        send.ev = "send";
        send.ts = shift(r1.ts, 500);
        send.minute = send.ts.substring(11, 16);
        rows12b.add(send);
        rows12b.add(r2);
        List<Burst> seg = Burst.group(rows12b);
        check("两条文本间夹着发送记录 ⇒ 分段", seg.size() == 2, dump(seg));
        check("发送记录本身不产生段", seg.size() == 2
                        && "你好呀".equals(seg.get(0).text), dump(seg));
        SendBoundary.resetForTest();

        System.out.println("\n== 11. 占位文字识别（小米笔记那条默认文本）==");
        check("小米笔记默认文本被识别", PlainText.isPlaceholder("开始书写或 创建思维笔记"), "漏了");
        check("带省略号也识别", PlainText.isPlaceholder("开始书写…"), "漏了");
        check("真正的输入不被误判", !PlainText.isPlaceholder("这是测试"), "误伤");
        check("长句子不被误判", !PlainText.isPlaceholder("好耶我做了个小软件能记录手机上打的字"), "误伤");
        check("空输入框首条占位被拦", PlainText.looksLikeEmptyFieldHint("开始书写或 创建思维笔记"), "漏了");
        check("正常内容不被形态特征误伤", !PlainText.looksLikeEmptyFieldHint("今天天气不错"), "误伤");
        check("以开始结尾的正常短句不误伤", !PlainText.looksLikeEmptyFieldHint("会议开始了"), "误伤");

        // 注意：必须放在汇总之前，否则新用例不计入打印的项数（曾这样排错过）
        testSendSplitsSimilarShortMessage();

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
