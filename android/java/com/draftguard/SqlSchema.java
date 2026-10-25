package com.draftguard;

/**
 * 数据库 schema：表结构、索引、以及所有 SQL 语句。
 *
 * <h3>为什么单独成类</h3>
 * <ul>
 *   <li>{@link LogStore} 从 970 行降到"连接 + 读写 + 导出 + 迁移"，各管一件事</li>
 *   <li>测试只需要依赖本类就能验证 SQL（不必拉起整个存储层）</li>
 *   <li>SQL 集中一处，改 schema 时不会漏掉某条散落各处的语句</li>
 * </ul>
 *
 * <h3>纪律</h3>
 * <b>所有 SQL 都必须是本类的 {@code static final String} 常量</b>，
 * {@code test/SqlTest.java} 引用的是同一批常量 —— 这样"测试跑通了但生产跑的是
 * 另一句 SQL"这种情况从根上不存在。
 *
 * 参数占位用 {@code %s} 的地方，注释里写清放什么，并且**两侧都要留空格**：
 * v2.39.0 就因为 {@code " FROM rows" + where + "GROUP BY"} 少了一个空格，
 * 空 where 时拼成 {@code rowsGROUP BY}，语法错误被 catch 吞掉，
 * 静默回退到慢路径，统计数字还算错。
 */
final class SqlSchema {

    private SqlSchema() {
    }

    static final String DB_NAME = "draftguard.db";

    // ════════════════════════════════════════════ 建表

    static final String CREATE_ROWS =
            "CREATE TABLE IF NOT EXISTS rows ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts TEXT, ms INTEGER NOT NULL, day TEXT, minute TEXT,"
                    + "app TEXT NOT NULL, app_label TEXT, field TEXT, ev TEXT,"
                    + "chars INTEGER, delta INTEGER, comp INTEGER,"
                    + "text TEXT, detail TEXT,"
                    // 逻辑删除：1 = 用户删掉了，但数据仍在库里（导出与界面都会跳过）
                    + "deleted INTEGER DEFAULT 0)";

    /** 一次建全；重复执行不报错（IF NOT EXISTS） */
    static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_ms  ON rows(ms DESC)",
            "CREATE INDEX IF NOT EXISTS idx_day ON rows(day, app)",
            "CREATE INDEX IF NOT EXISTS idx_app ON rows(app, ms DESC)",
            "CREATE INDEX IF NOT EXISTS idx_fld ON rows(field, ms)",
    };

    /**
     * 老库补 deleted 列。
     * 重复执行会抛 "duplicate column name"，由调用方 try/catch 吞掉 —— 这是
     * SQLite 上最省事的幂等迁移方式（没有 IF NOT EXISTS 可用）。
     */
    static final String ALTER_ADD_DELETED =
            "ALTER TABLE rows ADD COLUMN deleted INTEGER DEFAULT 0";

    /** 逻辑删除：只打标记，不删数据 */
    static final String MARK_DELETED =
            "UPDATE rows SET deleted = 1 WHERE app = ? AND ts >= ? AND ts <= ?%s";

    /**
     * %s 是"排除已逻辑删除"的条件，由常量 {@link #ALIVE} 提供。
     * 读查询统一带上它 —— 这样界面、搜索、导出都不必各自记得过滤。
     */
    static final String ALIVE = " AND (deleted IS NULL OR deleted = 0)";

    // ════════════════════════════════════════════ 写

    /** 13 个参数，顺序见 {@link LogStore#append} */
    static final String INSERT =
            "INSERT INTO rows(ts,ms,day,minute,app,app_label,field,ev,"
                    + "chars,delta,comp,text,detail) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";

    static final String UPDATE_LABEL =
            "UPDATE rows SET app_label = ? WHERE day = ? AND app = ?";

    // ════════════════════════════════════════════ 读

    /** SELECT 的公共列，顺序与 {@link LogStore.Row} 的映射一致 */
    static final String COLS = "ts, minute, app, field, text, comp, chars, ev";

    static final String READ_DAY =
            "SELECT " + COLS + " FROM rows WHERE day = ?" + ALIVE + " ORDER BY ms ASC";

    static final String DAYS = "SELECT DISTINCT day FROM rows WHERE 1=1" + ALIVE + " ORDER BY day DESC";

    /** %s 放可选的时间下界（空串 或 " AND ms >= 123"）；另有一个 ? 是 LIMIT */
    static final String READ_NEWEST =
            "SELECT " + COLS + " FROM rows WHERE 1=1" + ALIVE + "%s ORDER BY ms DESC LIMIT ?";

    /** %s 放可选的时间下界；参数：app, [下界已内联], LIMIT */
    static final String READ_APP =
            "SELECT " + COLS + " FROM rows WHERE app = ?" + ALIVE + "%s ORDER BY ms DESC LIMIT ?";

    static final String APP_DAYS =
            "SELECT day FROM rows WHERE app = ?" + ALIVE + " GROUP BY day ORDER BY day DESC";

    static final String READ_APP_DAY =
            "SELECT " + COLS + " FROM rows WHERE app = ? AND day = ?" + ALIVE + " ORDER BY ms ASC";

    /**
     * %s 放可选的 " WHERE day IN (...)"。
     * <b>FROM rows 后和 GROUP BY 前都必须有空格</b>，见类注释里的那次事故。
     */
    static final String APPS_SUMMARY =
            "SELECT app, MAX(app_label), COUNT(*), SUM(LENGTH(text)), MAX(ts),"
                    + " COUNT(DISTINCT day), MAX(ms) FROM rows WHERE 1=1" + ALIVE + "%s"
                    + " GROUP BY app ORDER BY MAX(ms) DESC";

    /** %s 放可选的 " AND day = ?"；参数：LIKE 模式, [day], LIMIT */
    static final String SEARCH =
            "SELECT " + COLS + " FROM rows WHERE text LIKE ?" + ALIVE + "%s ORDER BY ms DESC LIMIT ?";

    static final String APP_LABELS =
            "SELECT app, MAX(app_label) FROM rows WHERE day = ?" + ALIVE + " GROUP BY app";

    static final String COUNT = "SELECT COUNT(*) FROM rows WHERE 1=1" + ALIVE;
    /** 已被逻辑删除的行数（诊断用） */
    static final String COUNT_DELETED =
            "SELECT COUNT(*) FROM rows WHERE deleted = 1";

    static final String INVENTORY =
            "SELECT app, COUNT(*), SUM(LENGTH(text)), MAX(ts), MAX(ms)"
                    + " FROM rows WHERE day = ?" + ALIVE + " GROUP BY app ORDER BY MAX(ms) DESC";
}
