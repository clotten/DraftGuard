package com.draftguard;

import com.draftguard.LogStore;
import com.draftguard.Record;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 存储层离线验证：不依赖安卓设备，直接跑 LogStore 的写入 / 读回 / 搜索三条路径。
 * 目的：确认"记录到了但显示不出来"到底是写的问题还是读的问题。
 */
public class StoreTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok, String extra) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (ok ? "" : "   <- " + extra));
        if (ok) pass++; else fail++;
    }

    static Record rec(String app, String text, Date t, boolean comp) {
        Record r = new Record();
        SimpleDateFormat ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat min = new SimpleDateFormat("HH:mm", Locale.US);
        r.ts = ts.format(t);
        r.ms = t.getTime();
        r.day = day.format(t);
        r.minute = min.format(t);
        r.app = app;
        r.appLabel = app.equals("com.tencent.mm") ? "微信" : "字迹留存";
        r.field = app + "#search";
        r.text = text;
        r.delta = text.length();
        r.comp = comp;
        return r;
    }

    public static void main(String[] args) throws Exception {
        File files = Files.createTempDirectory("typelog-store-test").toFile();
        LogStore store = new LogStore(files, 0);
        Date now = new Date();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        System.out.println("临时目录：" + files.getAbsolutePath());

        System.out.println("\n== 1. 写入 ==");
        check("写入第 1 条", store.append(rec("com.draftguard", "你好", now, false)), "append 返回 false");
        check("写入第 2 条", store.append(rec("com.draftguard", "你好世界", now, false)), "append 返回 false");
        check("写入微信那条", store.append(rec("com.tencent.mm", "微信里的测试文字", now, false)), "append 返回 false");
        check("写入一条带换行/引号的", store.append(
                rec("com.draftguard", "第一行\n第二行 \"引号\" \\反斜杠", now, false)), "append 返回 false");

        System.out.println("\n== 2. 磁盘上的文件 ==");
        File[] days = store.root().listFiles();
        check("生成了日期目录", days != null && days.length == 1,
                days == null ? "null" : String.valueOf(days.length));
        if (days != null && days.length > 0) {
            File[] fs = days[0].listFiles();
            StringBuilder names = new StringBuilder();
            if (fs != null) {
                for (File f : fs) {
                    names.append(f.getName()).append("(").append(f.length()).append("B) ");
                }
            }
            System.out.println("    目录内容：" + names);
            check("生成了两个 App 的 jsonl + index",
                    fs != null && fs.length >= 3, names.toString());
        }

        System.out.println("\n== 3. 读回（这是界面上「今天记录过的 App / 看今天全部记录」走的路）==");
        List<LogStore.Row> rows = store.readDay(day.format(now), 0);
        check("读回 4 条", rows.size() == 4, "读到 " + rows.size() + " 条");
        for (LogStore.Row r : rows) {
            System.out.println("    [" + r.ts + "] " + r.app + " chars=" + r.chars
                    + " text=<" + r.text.replace("\n", "\\n") + ">");
        }
        boolean hasHello = false;
        boolean hasWechat = false;
        boolean hasQuote = false;
        for (LogStore.Row r : rows) {
            if ("你好世界".equals(r.text)) hasHello = true;
            if ("微信里的测试文字".equals(r.text)) hasWechat = true;
            if (r.text.contains("第二行") && r.text.contains("\"引号\"") && r.text.contains("\\反斜杠")) {
                hasQuote = true;
            }
        }
        check("文本内容正确读回（你好世界）", hasHello, "没找到");
        check("跨 App 内容正确读回（微信）", hasWechat, "没找到");
        check("换行/引号/反斜杠正确反转义", hasQuote, "没找到");

        System.out.println("\n== 4. 搜索（界面「搜」按钮走的路）==");
        List<LogStore.Row> hit1 = new java.util.ArrayList<LogStore.Row>();
        store.search(day.format(now), "世界", 60, hit1);
        check("搜索能命中", hit1.size() == 1, "命中 " + hit1.size());
        List<LogStore.Row> hit2 = new java.util.ArrayList<LogStore.Row>();
        store.search(day.format(now), "微信", 60, hit2);
        check("搜微信能命中", hit2.size() == 1, "命中 " + hit2.size());
        List<LogStore.Row> hit3 = new java.util.ArrayList<LogStore.Row>();
        store.search(day.format(now), "不存在的词abcxyz", 60, hit3);
        check("搜不存在的词命中 0", hit3.isEmpty(), "命中 " + hit3.size());

        System.out.println("\n== 4b. 回归：第一个文件行数超过上限时，不能漏掉后面文件的记录 ==");
        // 复现真机上的情况：本应用自己那个文件被占位提示文字塞满（远超 limit），
        // 微信的记录在另一个文件里，排在后面。旧代码读完第一个文件就直接 return，微信永远搜不到。
        LogStore big = new LogStore(Files.createTempDirectory("typelog-big").toFile(), 0);
        for (int i = 0; i < 150; i++) {
            big.append(rec("com.draftguard", "噪音占位文字" + i, now, false));
        }
        big.append(rec("com.tencent.mm", "我走爱鲜蜂", now, false));
        List<LogStore.Row> hitWx = new java.util.ArrayList<LogStore.Row>();
        big.search(day.format(now), "我走爱鲜蜂", 60, hitWx);
        check("噪声文件在前也能搜到微信记录", hitWx.size() == 1, "命中 " + hitWx.size());
        List<LogStore.Row> hitRec = new java.util.ArrayList<LogStore.Row>();
        big.search(day.format(now), "噪音占位文字149", 60, hitRec);
        check("同一文件内的记录不受影响", hitRec.size() == 1, "命中 " + hitRec.size());
        check("搜索结果不超过上限", hitWx.size() + hitRec.size() <= 120, "超了");
        List<LogStore.Row> hitAll = new java.util.ArrayList<LogStore.Row>();
        big.search(day.format(now), "噪音占位文字", 60, hitAll);
        check("上限确实生效（不超过 60 条）", hitAll.size() == 60, "实际 " + hitAll.size());

        System.out.println("\n== 5. App 名字索引（界面显示中文名靠它）==");
        java.util.Map<String, String> labels = store.labels(day.format(now));
        System.out.println("    index = " + labels);
        check("索引里有微信", "微信".equals(labels.get("com.tencent.mm")), String.valueOf(labels));
        check("索引里有字迹留存", "字迹留存".equals(labels.get("com.draftguard")),
                String.valueOf(labels));

        System.out.println("\n== 6. 分钟字段（按分钟分类）==");
        java.util.Set<String> minutes = new java.util.HashSet<String>();
        for (LogStore.Row r : rows) minutes.add(r.minute);
        System.out.println("    出现的分钟：" + minutes);
        check("分钟字段非空且格式为 HH:MM",
                !minutes.isEmpty() && minutes.iterator().next().matches("\\d{2}:\\d{2}"),
                String.valueOf(minutes));

        System.out.println("\n== 7. 重新打开（模拟应用重启后再读）==");
        LogStore store2 = new LogStore(files, 0);
        List<LogStore.Row> rows2 = store2.readDay(day.format(now), 0);
        check("重启后仍能读回 4 条", rows2.size() == 4, "读到 " + rows2.size());
        check("重启后索引仍在",
                "微信".equals(store2.labels(day.format(now)).get("com.tencent.mm")),
                String.valueOf(store2.labels(day.format(now))));

        System.out.println("\n== 8. 原始文件内容（人工核对格式）==");
        File f = new File(new File(store.root(), day.format(now)), "com.draftguard.jsonl");
        if (f.exists()) {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String shown = line.length() > 220 ? line.substring(0, 220) + "…" : line;
                System.out.println("    " + shown);
            }
        } else {
            check("jsonl 文件存在", false, f.getAbsolutePath());
        }

        System.out.println("\n结果：通过 " + pass + " 项，失败 " + fail + " 项");
        System.exit(fail == 0 ? 0 : 1);
    }
}
