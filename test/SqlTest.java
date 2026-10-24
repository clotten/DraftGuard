package com.draftguard;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 存储层离线测试：用 <b>sqlite-jdbc</b> 在普通 JVM 上跑**真的 SQLite**。
 *
 * <h3>为什么不用 android.database.sqlite</h3>
 * {@code android.jar} 里只有空桩，调用会抛 {@code RuntimeException: Stub!}。
 * 所以这里换成 JDBC 驱动 —— 引擎还是同一个 SQLite。
 *
 * <h3>测的是什么</h3>
 * 直接引用 {@link LogStore} 里的 {@code static final String SQL_*} 常量，
 * 保证"测试跑的 SQL"和"生产跑的 SQL"是同一串字符。
 * 这样 v2.39.0 那个"少一个空格 → 拼成 rowsGROUP BY → 静默回退"的 bug 就能被测出来。
 *
 * <h3>跑法</h3>
 * <pre>
 *   tools/fetch-test-jars.ps1          # 下载 sqlite-jdbc + slf4j（到 build/jars）
 *   javac -cp "$ANDROID_JAR;build/jars/*" -d build/test ...
 *   java  -cp "build/test;$ANDROID_JAR;build/jars/*" com.draftguard.SqlTest
 * </pre>
 */
public class SqlTest {

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

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        File tmp = Files.createTempDirectory("draftguard-sql").toFile();
        String dbPath = new File(tmp, LogStore.DB_NAME).getAbsolutePath();
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement s = c.createStatement();

        System.out.println("1) 建表与索引（用生产代码里的同一批常量）");
        s.execute(LogStore.SQL_CREATE_ROWS);
        for (String sql : LogStore.SQL_CREATE_INDEXES) {
            s.execute(sql);
        }
        check("建表 + " + LogStore.SQL_CREATE_INDEXES.length + " 个索引", true, null);

        // 幂等：再执行一次不应报错（升级场景会重复执行）
        s.execute(LogStore.SQL_CREATE_ROWS);
        for (String sql : LogStore.SQL_CREATE_INDEXES) {
            s.execute(sql);
        }
        check("重复建表不报错（幂等）", true, null);

        System.out.println("2) 写入（用生产代码里的 SQL_INSERT）");
        insert(s, "2026-10-24T21:33:01.000", 1790L, "2026-10-24", "21:33",
                "com.tencent.mobileqq", "QQ", "f1", "text", "看一眼就懂了");
        insert(s, "2026-10-24T21:34:01.000", 1791L, "2026-10-24", "21:34",
                "com.tencent.mobileqq", "QQ", "f1", "text", "带\"引号\"和\n换行");
        insert(s, "2026-10-24T21:35:01.000", 1792L, "2026-10-24", "21:35",
                "com.tencent.mobileqq", "QQ", "f1", "send", "");
        insert(s, "2026-10-23T10:00:00.000", 1700L, "2026-10-23", "10:00",
                "com.tencent.mm", "微信", "f2", "text", "实习三千五");
        check("插入 4 条", count(s, "SELECT COUNT(*) FROM rows") == 4, null);

        System.out.println("3) 读某一天（SQL_READ_DAY）");
        check("2026-10-24 有 3 条", count(s, LogStore.SQL_READ_DAY.replace("?", "'2026-10-24'")
                .replace("SELECT ts, minute, app, field, text, comp, chars, ev",
                        "SELECT COUNT(*)")) == 3, null);

        System.out.println("4) 按应用统计（SQL_APPS_SUMMARY，空 where 会暴露少空格的 bug）");
        try {
            ResultSet rs = s.executeQuery(String.format(LogStore.SQL_APPS_SUMMARY, ""));
            int apps = 0;
            String firstLabel = null;
            int firstCount = 0;
            while (rs.next()) {
                if (apps == 0) {
                    firstLabel = rs.getString(2);
                    firstCount = rs.getInt(3);
                }
                apps++;
            }
            check("统计到 2 个应用", apps == 2, "实际 " + apps);
            check("第一个是 QQ 且 3 条（按 MAX(ms) 倒序）",
                    "QQ".equals(firstLabel) && firstCount == 3,
                    firstLabel + "/" + firstCount);
            rs.close();
        } catch (SQLException e) {
            check("SQL_APPS_SUMMARY（空 where）能执行", false, e.getMessage());
        }

        System.out.println("5) 按应用统计（带 where）");
        try {
            ResultSet rs = s.executeQuery(String.format(LogStore.SQL_APPS_SUMMARY,
                    " WHERE day IN ('2026-10-23') "));
            rs.next();
            check("只统计 10-23 时第一个是 微信",
                    "微信".equals(rs.getString(2)), rs.getString(2));
            rs.close();
        } catch (SQLException e) {
            check("SQL_APPS_SUMMARY（带 where）能执行", false, e.getMessage());
        }

        System.out.println("6) 读某个应用某一天（SQL_READ_APP_DAY）");
        {
            java.sql.PreparedStatement ps = c.prepareStatement(LogStore.SQL_READ_APP_DAY);
            ps.setString(1, "com.tencent.mobileqq");
            ps.setString(2, "2026-10-24");
            ResultSet r2 = ps.executeQuery();
            int n2 = 0;
            while (r2.next()) {
                n2++;
            }
            r2.close();
            ps.close();
            check("QQ 在 10-24 有 3 条", n2 == 3, "实际 " + n2);
        }

        System.out.println("7) 该应用有哪些天（SQL_APP_DAYS）");
        ResultSet rs = s.executeQuery(LogStore.SQL_APP_DAYS.replace("?", "'com.tencent.mobileqq'"));
        int days = 0;
        while (rs.next()) {
            days++;
        }
        rs.close();
        check("QQ 分布在 1 天", days == 1, "实际 " + days);

        System.out.println("8) 搜索（SQL_SEARCH，含换行/引号的内容也能命中）");
        rs = s.executeQuery(String.format(LogStore.SQL_SEARCH, "")
                .replaceFirst("\\?", "'%引号%'").replaceFirst("\\?", "10"));
        int hits = 0;
        while (rs.next()) {
            hits++;
        }
        rs.close();
        check("搜索命中 1 条", hits == 1, "实际 " + hits);

        System.out.println("9) 最新 N 条（SQL_READ_NEWEST）");
        rs = s.executeQuery(String.format(LogStore.SQL_READ_NEWEST, "")
                .replaceFirst("\\?", "2"));
        int newest = 0;
        while (rs.next()) {
            newest++;
        }
        rs.close();
        check("LIMIT 2 只返回 2 条", newest == 2, "实际 " + newest);

        System.out.println("10) 删除（同 deleteRange 用的条件）");
        s.executeUpdate("DELETE FROM rows WHERE app = 'com.tencent.mm'"
                + " AND ts >= '2026-10-23T10:00:00.000' AND ts <= '2026-10-23T10:00:00.000'");
        check("删完剩 3 条", count(s, LogStore.SQL_COUNT) == 3, null);

        System.out.println("11) 标签查询（SQL_APP_LABELS）");
        rs = s.executeQuery(LogStore.SQL_APP_LABELS.replace("?", "'2026-10-24'"));
        String lab = null;
        while (rs.next()) {
            lab = rs.getString(2);
        }
        rs.close();
        check("10-24 的应用名是 QQ", "QQ".equals(lab), String.valueOf(lab));

        System.out.println("12) 导出 JSONL 行格式");
        LogStore.Row r = new LogStore.Row();
        r.ts = "2026-10-24T21:33:01.000";
        r.minute = "21:33";
        r.app = "com.tencent.mobileqq";
        r.field = "f1";
        r.text = "看一眼就懂了";
        r.chars = 6;
        String line = LogStore.jsonLine("2026-10-24", r);
        check("导出行的 ts 正确", line.contains("\"ts\":\"2026-10-24T21:33:01.000\""), line);
        check("导出行含 bucket", line.contains("\"bucket\":\"2026-10-24 21:33\""), line);

        c.close();
        //noinspection ResultOfMethodCallIgnored
        new File(dbPath).delete();
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();

        System.out.println();
        System.out.println("结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void insert(Statement s, String ts, long ms, String day, String minute,
                               String app, String label, String field, String ev,
                               String text) throws Exception {
        java.sql.PreparedStatement ps = s.getConnection().prepareStatement(LogStore.SQL_INSERT);
        ps.setString(1, ts);
        ps.setLong(2, ms);
        ps.setString(3, day);
        ps.setString(4, minute);
        ps.setString(5, app);
        ps.setString(6, label);
        ps.setString(7, field);
        ps.setString(8, ev);
        ps.setInt(9, text.length());
        ps.setInt(10, 0);
        ps.setInt(11, 0);
        ps.setString(12, text);
        ps.setString(13, null);
        ps.executeUpdate();
        ps.close();
    }

    private static int count(Statement s, String sql) throws Exception {
        ResultSet rs = s.executeQuery(sql);
        int n = rs.next() ? rs.getInt(1) : 0;
        rs.close();
        return n;
    }
}
