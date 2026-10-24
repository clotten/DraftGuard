package com.draftguard;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 查询索引。
 *
 * <h3>它在架构里的位置</h3>
 * <b>JSONL 仍然是权威数据</b>（追加写 + fsync，崩溃安全；也是导出格式）。
 * 这个索引只是"查询加速层"：读列表、搜索、删除都走它，随时可以从 JSONL 重建。
 *
 * <h3>为什么不直接把存储换成 SQLite</h3>
 * {@code android.database.sqlite} 在 {@code android.jar} 里只有空桩 ——
 * 普通 JVM（也就是 CI 和 22 项存储测试）根本跑不起来。
 * 所以本类**必须**能被 try/catch 兜住：{@link LogStore} 构造时用 try/catch 创建它，
 * 失败就自动降级回 JSONL 路径，测试与 CI 完全不受影响。
 *
 * <h3>为什么能加速</h3>
 * 数据攒到 17 天 / 21109 个版本 / 9.4 MB 后，原来每次刷新都要把所有日期的
 * JSON 全解析 + 全排序，实测一次要 1 秒以上。这里用索引把"读某一天"变成
 * 一次带索引的查询，把"按应用统计"变成一条 GROUP BY（在 C 层做）。
 */
final class SqlIndex {

    private static final String TAG = "SqlIndex";
    private static final String DB_NAME = "draftguard.db";
    private static final int DB_VERSION = 1;

    private SQLiteDatabase db;
    private String lastError = "";

    /**
     * 进程内单例。
     *
     * 为什么必须单例：LogStore 会被反复 new（每次刷新列表都 new 一个），
     * 如果每个实例都 openOrCreateDatabase，就会得到一堆连接 ——
     * 实测表现是 "database is locked" + "SQLiteConnection was leaked"，
     * 进而构造异常、自动降级回 JSONL，优化全部失效（读 2 万行还是 2.6 秒）。
     */
    private static SqlIndex shared;

    static synchronized SqlIndex get(File filesDir) {
        if (shared == null) {
            shared = new SqlIndex(filesDir);
        }
        return shared;
    }

    /** 测试/清空场景用：丢掉单例，下次会重新打开 */
    static synchronized void reset() {
        if (shared != null) {
            shared.close();
            shared = null;
        }
    }

    private SqlIndex(File filesDir) {
        File f = new File(filesDir, DB_NAME);
        db = SQLiteDatabase.openOrCreateDatabase(f, null);
        // WAL 会写进数据库文件、下次打开自动沿用，所以这里的 PRAGMA 失败不算致命
        // （另一条连接正开着时，改 journal_mode 会报 database is locked）
        try {
            db.execSQL("PRAGMA journal_mode=WAL");
        } catch (Throwable ignored) {
        }
        try {
            // FULL：每条提交都真落盘，和 JSONL 的 fsync 同等持久性
            db.execSQL("PRAGMA synchronous=FULL");
        } catch (Throwable ignored) {
        }
        createSchema();
        try {
            db.setVersion(DB_VERSION);
        } catch (Throwable ignored) {
        }
    }

    private void createSchema() {
        db.execSQL("CREATE TABLE IF NOT EXISTS rows ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts TEXT, ms INTEGER NOT NULL, day TEXT, minute TEXT,"
                + "app TEXT NOT NULL, app_label TEXT, field TEXT, ev TEXT,"
                + "chars INTEGER, delta INTEGER, comp INTEGER,"
                + "text TEXT, detail TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ms  ON rows(ms DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_day ON rows(day, app)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_app ON rows(app, ms DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fld ON rows(field, ms)");
    }

    // ── 写 ──────────────────────────────────────────────────────────

    /** 写入一条（由 LogStore.append 在 JSONL 写成功之后调用） */
    boolean insert(Record r) {
        try {
            ContentValues v = new ContentValues(13);
            v.put("ts", r.ts);
            v.put("ms", r.ms);
            v.put("day", r.day);
            v.put("minute", r.minute);
            v.put("app", r.app);
            v.put("app_label", r.appLabel);
            v.put("field", r.field);
            v.put("ev", r.ev);
            v.put("chars", r.text == null ? 0 : r.text.length());
            v.put("delta", r.delta);
            v.put("comp", r.comp ? 1 : 0);
            v.put("text", r.text);
            v.put("detail", r.detail);
            return db.insert("rows", null, v) >= 0;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Log.w(TAG, "insert 失败", t);
            return false;
        }
    }

    /** 迁移用：从 Row 反推 ms/day 后写入（Row 没有这两个字段） */
    boolean insertRow(LogStore.Row r) {
        try {
            long ms = Burst.msOf(r.ts);
            String day = r.ts.length() >= 10 ? r.ts.substring(0, 10) : "";
            ContentValues v = new ContentValues(13);
            v.put("ts", r.ts);
            v.put("ms", ms);
            v.put("day", day);
            v.put("minute", r.minute);
            v.put("app", r.app);
            v.put("field", r.field);
            v.put("ev", r.ev);
            v.put("chars", r.chars);
            v.put("delta", 0);
            v.put("comp", r.comp ? 1 : 0);
            v.put("text", r.text);
            return db.insert("rows", null, v) >= 0;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return false;
        }
    }

    /** 迁移时批量插入：自己开事务，比逐条快一个数量级 */
    int insertRows(List<LogStore.Row> rows) {
        int n = 0;
        db.beginTransaction();
        try {
            for (LogStore.Row r : rows) {
                if (insertRow(r)) {
                    n++;
                }
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        } finally {
            try {
                db.endTransaction();
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 删除匹配的记录；返回删除行数。跨天也能处理。 */
    int deleteRange(String app, String field, String fromTs, String toTs) {
        try {
            String to = (toTs == null || toTs.isEmpty()) ? fromTs : toTs;
            StringBuilder where = new StringBuilder("app = ? AND ts >= ? AND ts <= ?");
            List<String> args = new ArrayList<>();
            args.add(app);
            args.add(fromTs);
            args.add(to);
            if (field != null && !field.isEmpty()) {
                where.append(" AND field = ?");
                args.add(field);
            }
            return db.delete("rows", where.toString(), args.toArray(new String[0]));
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Log.w(TAG, "deleteRange 失败", t);
            return 0;
        }
    }

    void clearAll() {
        try {
            db.delete("rows", null, null);
            db.execSQL("VACUUM");
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        }
    }

    // ── 读 ──────────────────────────────────────────────────────────

    long count() {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM rows", null);
            return c.moveToFirst() ? c.getLong(0) : 0;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return -1;
        } finally {
            closeQuietly(c);
        }
    }

    /** 某一天的全部记录（按时间正序）。就是列表页的主查询。 */
    List<LogStore.Row> readDay(String day, int max) {
        List<LogStore.Row> out = new ArrayList<>();
        Cursor c = null;
        try {
            String sql = "SELECT ts, minute, app, field, text, comp, chars, ev"
                    + " FROM rows WHERE day = ? ORDER BY ms ASC";
            if (max > 0) {
                sql = sql + " LIMIT " + max;
            }
            c = db.rawQuery(sql, new String[]{day});
            while (c.moveToNext()) {
                LogStore.Row r = new LogStore.Row();
                r.ts = str(c, 0);
                r.minute = str(c, 1);
                r.app = str(c, 2);
                r.field = str(c, 3);
                r.text = str(c, 4);
                r.comp = c.getInt(5) != 0;
                r.chars = c.getInt(6);
                r.ev = str(c, 7).isEmpty() ? "text" : str(c, 7);
                out.add(r);
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Log.w(TAG, "readDay 失败", t);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /**
     * 只取最新的 limit 行（按时间正序返回，便于直接分段）。
     *
     * 为什么需要它：界面最多显示 400 段，把 2 万行全搬进 Java 再砍到 6000 行是白费
     * —— 实测"全部时间"因此要 2.6 秒。把上限推进 SQL，只搬真正要用的行。
     */
    List<LogStore.Row> readNewest(long fromMs, int limit) {
        List<LogStore.Row> out = new ArrayList<>();
        Cursor c = null;
        try {
            String sql = "SELECT ts, minute, app, field, text, comp, chars, ev"
                    + " FROM rows" + (fromMs > 0 ? " WHERE ms >= " + fromMs : "")
                    + " ORDER BY ms DESC"
                    + (limit > 0 ? " LIMIT " + limit : "");
            c = db.rawQuery(sql, null);
            while (c.moveToNext()) {
                LogStore.Row r = new LogStore.Row();
                r.ts = str(c, 0);
                r.minute = str(c, 1);
                r.app = str(c, 2);
                r.field = str(c, 3);
                r.text = str(c, 4);
                r.comp = c.getInt(5) != 0;
                r.chars = c.getInt(6);
                r.ev = str(c, 7).isEmpty() ? "text" : str(c, 7);
                out.add(r);
            }
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            Log.w(TAG, "readNewest 失败", e);
        } finally {
            closeQuietly(c);
        }
        // SQL 给的是新→旧，分段要正序
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * 只读某个应用的记录（走 (app, ms) 索引）。
     *
     * 原来"应用详情"是遍历所有日期、把每一天的文件全读出来再按包名过滤 ——
     * 看一个 QQ 的历史等于把全部 2 万行读一遍再扔掉 90%。
     */
    List<LogStore.Row> readApp(String app, long fromMs, int limit) {
        List<LogStore.Row> out = new ArrayList<>();
        Cursor c = null;
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT ts, minute, app, field, text, comp, chars, ev"
                            + " FROM rows WHERE app = ?");
            List<String> args = new ArrayList<>();
            args.add(app);
            if (fromMs > 0) {
                sql.append(" AND ms >= ").append(fromMs);
            }
            sql.append(" ORDER BY ms DESC");
            if (limit > 0) {
                sql.append(" LIMIT ").append(limit);
            }
            c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
            while (c.moveToNext()) {
                LogStore.Row r = new LogStore.Row();
                r.ts = str(c, 0);
                r.minute = str(c, 1);
                r.app = str(c, 2);
                r.field = str(c, 3);
                r.text = str(c, 4);
                r.comp = c.getInt(5) != 0;
                r.chars = c.getInt(6);
                r.ev = str(c, 7).isEmpty() ? "text" : str(c, 7);
                out.add(r);
            }
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            Log.w(TAG, "readApp 失败", e);
        } finally {
            closeQuietly(c);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * 补齐 app_label。
     *
     * 为什么要它：迁移时是从 JSONL 的 Row 反推的，而 Row 里没有应用显示名，
     * 于是所有迁移过来的行 app_label 都是空的 —— 界面就退回显示包名。
     * 这里用当天的 index.json 补一次（只更新空值的行）。
     *
     * @return 更新了多少行
     */
    int fillLabel(String day, String app, String label) {
        if (label == null || label.isEmpty()) {
            return 0;
        }
        try {
            ContentValues v = new ContentValues(1);
            v.put("app_label", label);
            return db.update("rows", v,
                    "day = ? AND app = ? AND (app_label IS NULL OR app_label = '')",
                    new String[]{day, app});
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            return 0;
        }
    }

    /** 某个应用有记录的日期（新 → 旧） */
    List<String> appDays(String app) {
        List<String> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT day FROM rows WHERE app = ?"
                    + " GROUP BY day ORDER BY day DESC", new String[]{app});
            while (c.moveToNext()) {
                out.add(str(c, 0));
            }
        } catch (Throwable e) {
            lastError = String.valueOf(e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /** 某个应用在某一天的记录（正序），供"按天翻页"使用 */
    List<LogStore.Row> readAppDay(String app, String day) {
        List<LogStore.Row> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT ts, minute, app, field, text, comp, chars, ev"
                    + " FROM rows WHERE app = ? AND day = ? ORDER BY ms ASC",
                    new String[]{app, day});
            while (c.moveToNext()) {
                LogStore.Row r = new LogStore.Row();
                r.ts = str(c, 0);
                r.minute = str(c, 1);
                r.app = str(c, 2);
                r.field = str(c, 3);
                r.text = str(c, 4);
                r.comp = c.getInt(5) != 0;
                r.chars = c.getInt(6);
                r.ev = str(c, 7).isEmpty() ? "text" : str(c, 7);
                out.add(r);
            }
        } catch (Throwable e) {
            lastError = String.valueOf(e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /** 有记录的日期，新 → 旧 */
    List<String> days() {
        List<String> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT DISTINCT day FROM rows ORDER BY day DESC", null);
            while (c.moveToNext()) {
                out.add(str(c, 0));
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /** 按应用汇总：直接一条 GROUP BY，在 SQLite 内部完成，不用把行搬进 Java */
    Map<String, LogStore.AppStat> appsSummary(int maxDays) {
        Map<String, LogStore.AppStat> out = new LinkedHashMap<>();
        Cursor c = null;
        try {
            String where = "";
            if (maxDays > 0) {
                List<String> ds = days();
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < ds.size() && i < maxDays; i++) {
                    if (in.length() > 0) {
                        in.append(',');
                    }
                    in.append('\'').append(ds.get(i).replace("'", "''")).append('\'');
                }
                where = in.length() == 0 ? " WHERE 1=0 " : " WHERE day IN (" + in + ") ";
            }
            c = db.rawQuery("SELECT app, MAX(app_label), COUNT(*),"
                    + " SUM(LENGTH(text)), MAX(ts), COUNT(DISTINCT day), MAX(ms)"
                    + " FROM rows " + where + " GROUP BY app ORDER BY MAX(ms) DESC", null);
            while (c.moveToNext()) {
                LogStore.AppStat a = new LogStore.AppStat();
                a.app = str(c, 0);
                a.label = str(c, 1);
                a.rows = c.getInt(2);
                a.bytes = c.getLong(3);
                a.lastTs = str(c, 4);
                a.days = c.getInt(5);
                out.put(a.app, a);
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /** 搜索。day 为空表示跨所有日期。 */
    void search(String day, String query, int limit, List<LogStore.Row> out) {
        if (query == null || query.isEmpty()) {
            return;
        }
        Cursor c = null;
        try {
            String sql = "SELECT ts, minute, app, field, text, comp, chars, ev"
                    + " FROM rows WHERE text LIKE ?";
            List<String> args = new ArrayList<>();
            args.add("%" + query + "%");
            if (day != null && !day.isEmpty()) {
                sql = sql + " AND day = ?";
                args.add(day);
            }
            sql = sql + " ORDER BY ms DESC";
            if (limit > 0) {
                sql = sql + " LIMIT " + limit;
            }
            c = db.rawQuery(sql, args.toArray(new String[0]));
            while (c.moveToNext()) {
                LogStore.Row r = new LogStore.Row();
                r.ts = str(c, 0);
                r.minute = str(c, 1);
                r.app = str(c, 2);
                r.field = str(c, 3);
                r.text = str(c, 4);
                r.comp = c.getInt(5) != 0;
                r.chars = c.getInt(6);
                r.ev = str(c, 7).isEmpty() ? "text" : str(c, 7);
                out.add(r);
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        } finally {
            closeQuietly(c);
        }
    }

    /** 数据库文件占多少字节（含 -wal / -shm） */
    long bytesOnDisk() {
        try {
            File f = new File(db.getPath());
            long n = f.length();
            n += new File(f.getPath() + "-wal").length();
            n += new File(f.getPath() + "-shm").length();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    String lastError() {
        return lastError;
    }

    void close() {
        try {
            if (db != null) {
                db.close();
            }
        } catch (Throwable ignored) {
        }
        db = null;
    }

    // ── 小工具 ──────────────────────────────────────────────────────

    private static String str(Cursor c, int i) {
        String s = c.getString(i);
        return s == null ? "" : s;
    }

    private static void closeQuietly(Cursor c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
