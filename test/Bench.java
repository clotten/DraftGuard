package com.draftguard;

/*
 * 性能基准（不参与打包，需要手动跑）：
 *
 *   1. 从手机上导出 zip，解压到 <某个目录>/draftguard/<日期>/...
 *   2. javac ... test/Bench.java + 相关源文件
 *   3. java -cp ... com.draftguard.Bench <某个目录>
 *
 * 用途：数据攒多了之后，"越大越慢"到底慢在读盘还是慢在分段，
 * 光看界面是分不出来的。这个基准把两段拆开计时，避免优化错地方。
 *
 * 2026-10 实测（17 天 / 21109 行，桌面 JVM）：
 *   读盘 1019 ms / 排序 8 ms / 全量分段 644 ms（3535 段）
 *   → 分段改"只取最新 6000 行"后 113 ms（1083 段）
 *   → 结论：读盘和分段各占一半，两个都要治
 */

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Bench {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        LogStore st = new LogStore(root, 0);
        System.out.println("后端: " + LogStore.backend);

        long t0 = System.currentTimeMillis();
        List<LogStore.Row> all = new ArrayList<>();
        for (String day : st.days()) {
            all.addAll(st.readDay(day, 0));
        }
        long t1 = System.currentTimeMillis();
        Collections.sort(all, (x, y) -> x.ts.compareTo(y.ts));
        long t2 = System.currentTimeMillis();

        System.out.println("读盘: " + (t1 - t0) + " ms, " + all.size() + " 行");
        System.out.println("排序: " + (t2 - t1) + " ms");

        List<Burst> full = Burst.groupNewestFirst(new ArrayList<>(all));
        long t3 = System.currentTimeMillis();
        System.out.println("全量分段: " + (t3 - t2) + " ms -> " + full.size() + " 段");

        List<LogStore.Row> cap = all.size() > 6000
                ? new ArrayList<>(all.subList(all.size() - 6000, all.size()))
                : all;
        long t4 = System.currentTimeMillis();
        List<Burst> capped = Burst.groupNewestFirst(cap);
        long t5 = System.currentTimeMillis();
        System.out.println("限 6000 行: " + (t5 - t4) + " ms -> " + capped.size() + " 段");

        System.out.println("改前合计: " + (t3 - t0) + " ms");
        System.out.println("改后合计: " + (t2 - t0 + t5 - t4) + " ms");
    }
}
