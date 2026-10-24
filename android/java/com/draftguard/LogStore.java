package com.draftguard;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据访问层：<b>只用 SQLite</b>。
 *
 * <h3>为什么不再有 JSONL</h3>
 * v2.37–v2.39 曾经是"JSONL 权威 + SQLite 索引"双路径，连续咬出三个 bug：
 * <ul>
 *   <li>每个实例各开一个连接 → {@code database is locked} → 静默降级回 JSONL，优化全废</li>
 *   <li>迁移时没有应用显示名 → 界面退回显示包名</li>
 *   <li>统计 SQL 少一个空格 → 静默回退慢路径，数字还算错</li>
 * </ul>
 * 共同点：同一份数据两个真相，任何不同步都表现为"看起来正常"。
 * 现在只有一个真相：{@code draftguard.db}。JSONL 只作为<b>导出格式</b>保留。
 *
 * <h3>可测试性</h3>
 * 所有 SQL 都是 {@code static final} 常量 —— {@code test/SqlTest.java} 用 sqlite-jdbc
 * 在普通 JVM 上执行同一批常量，所以"SQL 拼错"这类问题能进 CI。
 */
final class LogStore {

    private static final String TAG = "LogStore";
    static final String DB_NAME = SqlSchema.DB_NAME;

    // SQL 全部在 SqlSchema（单一来源，测试引用同一批常量）

    // ════════════════════════════════════════════ 进程内单例

    private static LogStore shared;

    /**
     * 单例：多次 new 会开多个连接。实测多连接会 {@code database is locked}，
     * 并触发 SQLiteConnectionPool 的连接泄漏告警。
     */
    static synchronized LogStore get(File filesDir) {
        if (shared == null) {
            shared = new LogStore(filesDir, 0);
        }
        return shared;
    }

    // ════════════════════════════════════════════ 状态

    private final File root;
    private final long retentionDays;
    private SQLiteDatabase db;

    static volatile String backend = "未初始化";
    /** 诊断：库里有几行 */
    static volatile long indexRows;
    /** 诊断：本次从旧 JSONL 导入了多少行（0 = 无需迁移） */
    static volatile int indexImported;
    static volatile String diagLastBackupPath = "";
    static volatile String diagLastError = "";
    static volatile String diagLastLine = "";
    static volatile long diagLastWriteAt;
    static volatile long diagWrittenRows;
    static volatile long diagByteCount;
    static volatile int diagSkippedRows;

    /** 最近一次写入失败的原因（服务端诊断用） */
    String lastError = "";

    LogStore(File filesDir, long retentionDays) {
        this.root = new File(filesDir, "draftguard");
        this.retentionDays = retentionDays;
        try {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
            db = SQLiteDatabase.openOrCreateDatabase(new File(filesDir, DB_NAME), null);
            // WAL 写进库文件后会自动沿用；另一条连接开着时改 journal_mode 会报 locked，不致命
            try {
                db.execSQL("PRAGMA journal_mode=WAL");
            } catch (Throwable ignored) {
            }
            try {
                // 每条提交都落盘，与原先的 fsync 同等持久性
                db.execSQL("PRAGMA synchronous=FULL");
            } catch (Throwable ignored) {
            }
            db.execSQL(SqlSchema.CREATE_ROWS);
            for (String sql : SqlSchema.CREATE_INDEXES) {
                db.execSQL(sql);
            }
            backend = "SQLite";
        } catch (Throwable t) {
            // 普通 JVM（没带 sqlite 驱动）会走到这里。
            // 明确标记"不可用"，不再像以前那样静默降级成另一套读法。
            db = null;
            backend = "SQLite 不可用";
            diagLastError = "打开数据库失败：" + t;
            Log.w(TAG, "打开数据库失败", t);
        }
    }

    File root() {
        return root;
    }

    /** 兼容旧调用方；现在只有一种后端 */
    boolean hasIndex() {
        return db != null;
    }

    // ════════════════════════════════════════════ 写

    /** 写入一条快照；已落盘才返回 true */
    synchronized boolean append(Record r) {
        if (db == null || r == null || !insert(r)) {
            return false;
        }
        indexRows++;
        diagWrittenRows = indexRows;
        diagLastWriteAt = System.currentTimeMillis();
        diagLastLine = r.text == null ? "" : r.text;
        diagByteCount = onDisk();
        lastError = "";
        return true;
    }

    private boolean insert(Record r) {
        try {
            db.execSQL(SqlSchema.INSERT, new Object[]{
                    r.ts, r.ms, r.day, r.minute, r.app,
                    r.appLabel == null ? "" : r.appLabel, r.field, r.ev,
                    r.text == null ? 0 : r.text.length(), r.delta,
                    r.comp ? 1 : 0, r.text, r.detail});
            return true;
        } catch (Throwable t) {
            diagLastError = String.valueOf(t);
            lastError = String.valueOf(t);
            Log.w(TAG, "写入失败", t);
            return false;
        }
    }

    /** 补写某天的应用显示名（先写入数据、后拿到图标名的情况） */
    void putAppLabel(String day, String app, String label) {
        if (db == null || label == null || label.isEmpty()) {
            return;
        }
        try {
            db.execSQL(SqlSchema.UPDATE_LABEL,
                    new Object[]{label, day, app});
        } catch (Throwable ignored) {
        }
    }

    /** 删除匹配的记录，返回删除行数。跨天也能处理。 */
    synchronized int deleteRange(String app, String field, String fromTs, String toTs) {
        if (db == null || app == null || app.isEmpty() || fromTs == null || fromTs.isEmpty()) {
            return 0;
        }
        String to = (toTs == null || toTs.isEmpty()) ? fromTs : toTs;
        try {
            int n;
            if (field == null || field.isEmpty()) {
                n = db.delete("rows", "app = ? AND ts >= ? AND ts <= ?",
                        new String[]{app, fromTs, to});
            } else {
                n = db.delete("rows", "app = ? AND ts >= ? AND ts <= ? AND field = ?",
                        new String[]{app, fromTs, to, field});
            }
            indexRows = Math.max(0, indexRows - n);
            return n;
        } catch (Throwable t) {
            diagLastError = String.valueOf(t);
            return 0;
        }
    }

    /** 清空（先备份再删；备份路径写在 diagLastBackupPath） */
    synchronized void clearAll() {
        if (db == null) {
            return;
        }
        File backup = backupAll();
        if (backup != null) {
            diagLastBackupPath = backup.getAbsolutePath();
        }
        try {
            db.delete("rows", null, null);
            db.execSQL("VACUUM");
            indexRows = 0;
        } catch (Throwable t) {
            diagLastError = String.valueOf(t);
        }
    }

    /** 按保留天数清理 */
    synchronized void cleanupOldDays() {
        if (db == null || retentionDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        try {
            int n = db.delete("rows", "ms < ?", new String[]{String.valueOf(cutoff)});
            if (n > 0) {
                indexRows = Math.max(0, indexRows - n);
            }
        } catch (Throwable ignored) {
        }
    }

    // ════════════════════════════════════════════ 读

    List<Row> readDay(String day, int max) {
        String sql = SqlSchema.READ_DAY + (max > 0 ? " LIMIT " + max : "");
        return query(sql, new String[]{day}, false);
    }

    List<String> days() {
        return strings(SqlSchema.DAYS, null);
    }

    /** 最新 limit 行（正序返回，便于直接分段） */
    List<Row> readNewest(long fromMs, int limit) {
        String extra = fromMs > 0 ? " AND ms >= " + fromMs : "";
        return query(String.format(SqlSchema.READ_NEWEST, extra),
                new String[]{String.valueOf(limit < 1 ? 100000 : limit)}, true);
    }

    /** 某个应用最新 limit 条（正序） */
    List<Row> readApp(String app, long fromMs, int limit) {
        String extra = fromMs > 0 ? " AND ms >= " + fromMs : "";
        return query(String.format(SqlSchema.READ_APP, extra),
                new String[]{app, String.valueOf(limit < 1 ? 100000 : limit)}, true);
    }

    List<String> appDays(String app) {
        return strings(SqlSchema.APP_DAYS, new String[]{app});
    }

    List<Row> readAppDay(String app, String day) {
        return query(SqlSchema.READ_APP_DAY, new String[]{app, day}, false);
    }

    Map<String, AppStat> appsSummary(int maxDays) {
        Map<String, AppStat> out = new LinkedHashMap<>();
        Cursor c = null;
        try {
            if (db == null) {
                return out;
            }
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
            c = db.rawQuery(String.format(SqlSchema.APPS_SUMMARY, where), null);
            while (c.moveToNext()) {
                AppStat a = new AppStat();
                a.app = str(c, 0);
                a.label = str(c, 1);
                a.rows = c.getInt(2);
                a.bytes = c.getLong(3);
                a.lastTs = str(c, 4);
                a.days = c.getInt(5);
                out.put(a.app, a);
            }
        } catch (Throwable t) {
            diagLastError = String.valueOf(t);
        } finally {
            close(c);
        }
        return out;
    }

    void search(String day, String query, int limit, List<Row> out) {
        if (db == null || query == null || query.isEmpty()) {
            return;
        }
        String extra = (day == null || day.isEmpty()) ? "" : " AND day = ?";
        List<String> args = new ArrayList<>();
        args.add("%" + query + "%");
        if (!extra.isEmpty()) {
            args.add(day);
        }
        args.add(String.valueOf(limit < 1 ? 500 : limit));
        out.addAll(query(String.format(SqlSchema.SEARCH, extra), args.toArray(new String[0]), true));
    }

    Map<String, String> labels(String day) {
        Map<String, String> out = new LinkedHashMap<>();
        Cursor c = null;
        try {
            if (db == null) {
                return out;
            }
            c = db.rawQuery(SqlSchema.APP_LABELS, new String[]{day});
            while (c.moveToNext()) {
                String app = str(c, 0);
                String lab = str(c, 1);
                out.put(app, lab.isEmpty() ? app : lab);
            }
        } catch (Throwable t) {
            diagLastError = String.valueOf(t);
        } finally {
            close(c);
        }
        return out;
    }

    int countAll() {
        Cursor c = null;
        try {
            if (db == null) {
                return 0;
            }
            c = db.rawQuery(SqlSchema.COUNT, null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            close(c);
        }
    }

    long totalBytes() {
        return onDisk();
    }

    /** 诊断用：某天每个应用的行数 / 字数 / 最后一条 */
    List<String> fileInventory(String day, int lastChars) {
        List<String> out = new ArrayList<>();
        Cursor c = null;
        try {
            if (db == null) {
                return out;
            }
            c = db.rawQuery(SqlSchema.INVENTORY, new String[]{day});
            while (c.moveToNext()) {
                out.add(String.format("%-26s %5d 行  %6d 字  最后 %s",
                        str(c, 0), c.getInt(1), c.getLong(2), str(c, 3)));
            }
        } catch (Throwable t) {
            diagLastError = String.valueOf(t);
        } finally {
            close(c);
        }
        return out;
    }

    static boolean isSkippedPackage(String pkg) {
        return pkg == null || pkg.isEmpty();
    }

    /** 单例不在这里关闭（否则下次调用会重开连接、可能锁冲突） */
    void closeAll() {
    }

    // ════════════════════════════════════════════ 导出（JSONL 只作为导出格式）

    /**
     * 导出整库为 zip，结构与旧版完全一致：{@code <day>/<app>.jsonl}。
     * 分析脚本（merge_export / analyze / 提取整段）无需改动。
     *
     * @return 写入的条目数
     */
    int writeZip(OutputStream os) throws Exception {
        int entries = 0;
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os));
        try {
            for (String day : days()) {
                Map<String, List<Row>> byApp = new LinkedHashMap<>();
                for (Row r : readDay(day, 0)) {
                    List<Row> lst = byApp.get(r.app);
                    if (lst == null) {
                        lst = new ArrayList<>();
                        byApp.put(r.app, lst);
                    }
                    lst.add(r);
                }
                for (Map.Entry<String, List<Row>> e : byApp.entrySet()) {
                    zos.putNextEntry(new ZipEntry(day + "/" + e.getKey() + ".jsonl"));
                    for (Row r : e.getValue()) {
                        zos.write((jsonLine(day, r) + "\n").getBytes("UTF-8"));
                    }
                    zos.closeEntry();
                    entries++;
                }
            }
            zos.flush();
        } finally {
            try {
                zos.finish();
            } catch (Throwable ignored) {
            }
        }
        return entries;
    }

    /** 单条 Row → 与旧格式一致的 JSON 行 */
    static String jsonLine(String day, Row r) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"ts\":\"").append(r.ts).append('"');
        sb.append(",\"ms\":").append(Burst.msOf(r.ts));
        sb.append(",\"day\":\"").append(day).append('"');
        sb.append(",\"minute\":\"").append(r.minute).append('"');
        sb.append(",\"bucket\":\"").append(day).append(' ').append(r.minute).append('"');
        sb.append(",\"app\":\"").append(r.app).append('"');
        sb.append(",\"field\":\"").append(Json.esc(r.field)).append('"');
        sb.append(",\"ev\":\"").append(r.ev).append('"');
        sb.append(",\"chars\":").append(r.chars);
        sb.append(",\"comp\":").append(r.comp);
        sb.append(",\"text\":\"").append(Json.esc(r.text)).append('"');
        sb.append('}');
        return sb.toString();
    }

    /** 备份 zip（写在应用私有目录的上一级） */
    File backupAll() {
        File out = new File(root.getParentFile(), "DraftGuard-backup-" + stamp() + ".zip");
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(out);
            writeZip(fo);
            return out;
        } catch (Throwable t) {
            diagLastError = "备份失败：" + t;
            return null;
        } finally {
            try {
                if (fo != null) {
                    fo.flush();
                    fo.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    // ════════════════════════════════════════════ 迁移：旧 JSONL → SQLite

    /**
     * 老版本的数据在 {@code draftguard/<日期>/<包名>.jsonl}。
     * 首次升级时导入数据库，**只有全部导入成功才删除旧目录**。
     *
     * @return 导入行数（0 = 无需迁移）
     */
    synchronized int migrateFromJsonlIfNeeded() {
        if (db == null) {
            return 0;
        }
        File[] kids = root.listFiles();
        if (kids == null || kids.length == 0) {
            return 0;
        }
        List<File> dirs = new ArrayList<>();
        for (File d : kids) {
            if (d.isDirectory() && d.getName().matches("\\d{4}-\\d{2}-\\d{2}")) {
                dirs.add(d);
            }
        }
        if (dirs.isEmpty()) {
            return 0;
        }
        Collections.sort(dirs);

        // ── 幂等保护（重要）──
        // 场景 A：从"只有 JSONL"的老版本升级 → 库是空的 → 需要导入
        // 场景 B：从"SQLite 索引"版本升上来 → 库里已经有全部数据，磁盘上的 JSONL
        //         只是旧副本。这时如果还导入一遍，行数会翻倍。
        int inDb = countAll();
        int inFiles = 0;
        for (File d : dirs) {
            File[] fs0 = d.listFiles();
            if (fs0 == null) {
                continue;
            }
            for (File f0 : fs0) {
                if (f0.getName().endsWith(".jsonl")) {
                    inFiles += readLines(f0).size();
                }
            }
        }
        if (inDb > 0) {
            if (inDb >= inFiles) {
                // 库里已经全了：删掉旧副本（这就是断舍离的那一下）
                for (File d : dirs) {
                    deleteTree(d);
                }
                indexRows = inDb;
                Log.i(TAG, "库里已有 " + inDb + " 行（旧文件 " + inFiles + " 行），"
                        + "已删除旧 JSONL 目录");
            } else {
                Log.w(TAG, "库里只有 " + inDb + " 行，旧文件有 " + inFiles
                        + " 行 —— 为免重复，不导入也不删除，请人工确认");
            }
            return 0;
        }

        int imported = 0;
        int fromFiles = 0;
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) {
                continue;
            }
            for (File f : fs) {
                if (!f.getName().endsWith(".jsonl")) {
                    continue;
                }
                String app = f.getName().substring(0, f.getName().length() - 6);
                for (String line : readLines(f)) {
                    Record r = recordFrom(line, d.getName(), app);
                    if (r == null) {
                        continue;
                    }
                    fromFiles++;
                    if (insert(r)) {
                        imported++;
                    }
                }
            }
        }
        indexImported = imported;
        indexRows = countAll();
        if (imported > 0 && imported == fromFiles) {
            for (File d : dirs) {
                deleteTree(d);
            }
            Log.i(TAG, "迁移完成：导入 " + imported + " 行，已删除旧 JSONL 目录");
        } else if (fromFiles > 0) {
            Log.w(TAG, "迁移未删旧文件：读到 " + fromFiles + " 行，只导入 " + imported + " 行");
        }
        return imported;
    }

    /** 一行 JSON → Record（字段与 Json.line 对称） */
    static Record recordFrom(String line, String day, String app) {
        Map<String, String> m = Json.parseObject(line);
        if (m == null || m.isEmpty()) {
            return null;
        }
        Record r = new Record();
        r.ts = val(m, "ts");
        if (r.ts.isEmpty()) {
            return null;
        }
        r.day = val(m, "day").isEmpty() ? day : val(m, "day");
        r.minute = val(m, "minute");
        r.app = val(m, "app").isEmpty() ? app : val(m, "app");
        r.appLabel = val(m, "app_label");
        r.field = val(m, "field");
        r.text = val(m, "text");
        r.ev = val(m, "ev").isEmpty() ? "text" : val(m, "ev");
        r.comp = "true".equals(val(m, "comp"));
        try {
            r.delta = Integer.parseInt(val(m, "delta"));
        } catch (Throwable ignored) {
        }
        r.ms = Burst.msOf(r.ts);
        r.detail = m.get("detail");
        return r;
    }

    private static String val(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? "" : v;
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        java.io.BufferedReader rd = null;
        try {
            rd = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = rd.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    out.add(line);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (rd != null) {
                    rd.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ════════════════════════════════════════════ 内部实现

    private long onDisk() {
        try {
            File f = new File(root.getParentFile(), DB_NAME);
            long n = f.length();
            n += new File(f.getPath() + "-wal").length();
            n += new File(f.getPath() + "-shm").length();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 结果只有一列文本时的通用查询 */
    private List<String> strings(String sql, String[] args) {
        List<String> out = new ArrayList<>();
        Cursor c = null;
        try {
            if (db == null) {
                return out;
            }
            c = db.rawQuery(sql, args);
            while (c.moveToNext()) {
                out.add(str(c, 0));
            }
        } catch (Throwable t) {
            diagLastError = String.valueOf(t);
        } finally {
            close(c);
        }
        return out;
    }

    /** 统一查询入口；reverse=true 时把结果翻成正序 */
    private List<Row> query(String sql, String[] args, boolean reverse) {
        List<Row> out = new ArrayList<>();
        Cursor c = null;
        try {
            if (db == null) {
                return out;
            }
            c = db.rawQuery(sql, args);
            while (c.moveToNext()) {
                Row r = new Row();
                r.ts = str(c, 0);
                r.minute = str(c, 1);
                r.app = str(c, 2);
                r.field = str(c, 3);
                r.text = str(c, 4);
                r.comp = c.getInt(5) != 0;
                r.chars = c.getInt(6);
                String ev = str(c, 7);
                r.ev = ev.isEmpty() ? "text" : ev;
                out.add(r);
            }
        } catch (Throwable t) {
            diagLastError = String.valueOf(t);
            Log.w(TAG, "查询失败", t);
        } finally {
            close(c);
        }
        if (reverse) {
            Collections.reverse(out);
        }
        return out;
    }

    private static String str(Cursor c, int i) {
        String s = c.getString(i);
        return s == null ? "" : s;
    }

    private static void close(Cursor c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }

    // ════════════════════════════════════════════ 数据对象

    /** 一条记录（界面只需要这些字段） */
    static final class Row {
        String ts = "";
        String minute = "";
        String app = "";
        String field = "";
        String text = "";
        boolean comp;
        int chars;
        String ev = "text";

        String key() {
            return minute + "|" + field + "|" + text;
        }
    }

    /** 单个应用的汇总 */
    static final class AppStat {
        String app = "";
        String label = "";
        int rows;
        long bytes;
        String lastTs = "";
        int days;

        String display() {
            return (label == null || label.isEmpty()) ? app : label;
        }
    }
}
