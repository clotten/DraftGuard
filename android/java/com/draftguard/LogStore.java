package com.draftguard;
import android.util.Log;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 存储层。
 *
 * 目录结构（应用私有目录，其他应用读不到）：
 *   files/logs/<yyyy-MM-dd>/<app>.jsonl     按天 + 按 App 分文件，一行一条完整快照
 *   files/logs/<yyyy-MM-dd>/index.json     该天记录过的 App 与中文名
 *
 * 我们的“准确”，靠的是三个设计：
 *   1) 每条记录存的是**输入框当前完整文本**，不是按键增量 —— 丢一次请求不会把文字拼错；
 *   2) 文件句柄常开 + 每次写完 flush —— 不做缓冲，进程被杀也不丢已写内容；
 *   3) 按“时-分”打桶，同一个 App 在某一分钟内的所有版本都留在同一文件里，事后按时段还原。
 */
final class LogStore {

    private static final String TAG = "DraftGuardStore";

    static final String PKG_SELF = "com.draftguard";

    /**
     * 只跳过系统壳与"非应用窗口"。故意**不再跳过本应用自己**：
     * 用户要求"在记录 App 里搜索时打的字也要记"。
     * 输入法自己的候选栏之所以被跳过，是因为它的事件包名是输入法，会被下面的
     * TypelogService 判定为 IME 后跳过。
     */
    private static final String[] SKIP_PKGS = {
            "com.android.systemui",
            "com.android.settings.intelligence",
            "android",
    };

    private static final Pattern TS = Pattern.compile("\"ts\":\"([^\"]*)\"");
    private static final Pattern APP = Pattern.compile("\"app\":\"([^\"]*)\"");
    private static final Pattern FIELD = Pattern.compile("\"field\":\"([^\"]*)\"");
    private static final Pattern COMP = Pattern.compile("\"comp\":(true|false)");
    private static final Pattern TEXT = Pattern.compile("\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final File root;
    private final long retentionDays;

    // ---- 自证用：把最近一次真实写入的内容与统计留在内存里，界面直接显示 ----
    volatile String lastLine = "";
    volatile long lastWriteAt;
    volatile long writtenRows;
    volatile long skippedRows;
    volatile long byteCount;
    volatile String lastError = "";
    private final Map<String, RandomAccessFile> indexHandles = new HashMap<>();

    /** 分钟桶 -> 已打开的文件，保持句柄常开 */
    private final Map<String, RandomAccessFile> handles = new HashMap<>();
    private final Map<String, Map<String, String>> dayIndexCache = new HashMap<>();

    LogStore(File filesDir, long retentionDays) {
        this.root = new File(filesDir, "draftguard");
        this.retentionDays = retentionDays;
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
    }

    File root() {
        return root;
    }

    static boolean isSkippedPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        for (String s : SKIP_PKGS) {
            if (s.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 落一条记录。返回 false 表示这条被跳过（内容与上次完全相同且没跨分钟）。
     * detail 可为 null。
     */
    synchronized boolean append(Record r) throws Exception {
        File dayDir = new File(root, r.day);
        if (!dayDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dayDir.mkdirs();
        }
        File f = new File(dayDir, r.app + ".jsonl");
        boolean existsBefore = f.exists();
        RandomAccessFile raf = handles.get(r.app);
        if (raf == null) {
            raf = new RandomAccessFile(f, "rw");
            raf.seek(raf.length());
            handles.put(r.app, raf);
        }
        String line = Json.line(r);
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        raf.write(bytes);
        raf.getFD().sync();   // 真落盘，不是只进页缓存
        byteCount += bytes.length;
        lastLine = line;
        lastWriteAt = System.currentTimeMillis();
        writtenRows++;

        if (!existsBefore && r.appLabel != null && !r.appLabel.isEmpty()) {
            indexPut(r.day, r.app, r.appLabel);
        }
        cleanupOldDays();
        return true;
    }

    /** 同一天、同一 App 的 jsonl 已存在，就不必重复写 index */
    private void indexPut(String day, String app, String label) {
        Map<String, String> idx = dayIndex(day);
        if (idx.containsKey(app)) {
            return;
        }
        idx.put(app, label);
        writeIndex(day, idx);
    }

    void putAppLabel(String day, String app, String label) {
        if (label == null || label.isEmpty()) {
            return;
        }
        synchronized (this) {
            Map<String, String> idx = dayIndex(day);
            if (label.equals(idx.get(app))) {
                return;
            }
            idx.put(app, label);
            dayIndexCache.put(day, idx);   // 关键：也要更新内存缓存，否则下次读到的还是旧值
            writeIndex(day, idx);
        }
    }

    private Map<String, String> dayIndex(String day) {
        Map<String, String> cached = dayIndexCache.get(day);
        if (cached != null) {
            return cached;
        }
        Map<String, String> m = new LinkedHashMap<String, String>();
        File f = new File(new File(root, day), "index.json");
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                m.putAll(Json.parseObject(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
            }
        }
        dayIndexCache.put(day, m);
        return m;
    }

    /**
     * 直写覆盖 index.json（不用"临时文件 + rename"：那种写法在部分设备上 rename 会失败，
     * 而且失败是静默的，结果就是"记录在、中文名却永远填不上"）。
     * 索引文件很小，直接覆盖 + fsync 更可靠，出错也能留下 lastError 供诊断。
     */
    private void writeIndex(String day, Map<String, String> idx) {
        RandomAccessFile raf = null;
        try {
            File dir = new File(root, day);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File f = new File(dir, "index.json");
            raf = indexHandles.get(day);
            if (raf == null) {
                raf = new RandomAccessFile(f, "rw");
                indexHandles.put(day, raf);
            }
            byte[] bytes = Json.object(idx).getBytes(StandardCharsets.UTF_8);
            raf.setLength(0);
            raf.seek(0);
            raf.write(bytes);
            raf.getFD().sync();
        } catch (Throwable t) {
            lastError = "index 写入失败: " + t;
        }
    }

    private void cleanupOldDays() {
        if (retentionDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600_000L;
        File[] days = root.listFiles();
        if (days == null) {
            return;
        }
        for (File d : days) {
            if (!d.isDirectory()) {
                continue;
            }
            if (d.lastModified() < cutoff) {
                deleteTree(d);
            }
        }
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

    // ------------------------------------------------------------------ 读取

    static final class Row {
        String ts = "";
        String minute = "";
        String app = "";
        String field = "";
        String text = "";
        boolean comp;
        int chars;

        String key() {
            return minute + "|" + field + "|" + text;
        }
    }

    List<String> days() {
        List<String> out = new ArrayList<>();
        File[] ds = root.listFiles();
        if (ds != null) {
            for (File d : ds) {
                if (d.isDirectory()) {
                    out.add(d.getName());
                }
            }
        }
        Collections.sort(out);
        Collections.reverse(out);
        return out;
    }

    /** 单个应用的汇总（供"应用"页面显示图标 + 名字 + 条数） */
    static final class AppStat {
        String app = "";
        String label = "";
        int rows;
        long bytes;
        /** 最近一条记录的时间（ISO 字符串，可直接比较大小） */
        String lastTs = "";
        /** 出现过该应用的不同天数 */
        int days;

        String display() {
            return (label == null || label.isEmpty()) ? app : label;
        }
    }

    /**
     * 汇总所有日期下各应用的记录量。
     *
     * 刻意**只数行、不解析 JSON** —— 应用可能累积几十天、上千条，
     * 若逐条解析会很慢，而这里只需要"条数 / 体积 / 最近时间"。
     *
     * @param maxDays 最多回看多少天（0 = 全部）
     */
    Map<String, AppStat> appsSummary(int maxDays) {
        Map<String, AppStat> out = new LinkedHashMap<>();
        List<String> ds = days();
        int n = 0;
        for (String day : ds) {
            if (maxDays > 0 && n >= maxDays) {
                break;
            }
            n++;
            File dir = new File(root, day);
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            Map<String, String> idx = dayIndex(day);
            for (File f : files) {
                String name = f.getName();
                if (!name.endsWith(".jsonl")) {
                    continue;
                }
                String app = name.substring(0, name.length() - ".jsonl".length());
                AppStat st = out.get(app);
                if (st == null) {
                    st = new AppStat();
                    st.app = app;
                    out.put(app, st);
                }
                st.days++;
                st.bytes += f.length();
                // 数行 + 取最后一条的时间（顺序读一遍，不做 JSON 解析）
                String last = null;
                int cnt = 0;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.isEmpty()) {
                            cnt++;
                            last = line;
                        }
                    }
                } catch (Exception ignored) {
                }
                st.rows += cnt;
                if (last != null) {
                    String ts = group(TS, last);
                    if (ts.compareTo(st.lastTs) > 0) {
                        st.lastTs = ts;
                    }
                }
                String lb = idx.get(app);
                if (lb != null && !lb.isEmpty() && (st.label == null || st.label.isEmpty())) {
                    st.label = lb;
                }
            }
        }
        return out;
    }

    List<Row> readDay(String day, int max) {
        List<Row> out = new ArrayList<>();
        File dir = new File(root, day);
        File[] fs = dir.listFiles();
        if (fs == null) {
            return out;
        }
        List<File> files = new ArrayList<>();
        for (File f : fs) {
            if (f.getName().endsWith(".jsonl")) {
                files.add(f);
            }
        }
        Collections.sort(files);
        for (File f : files) {
            if (max > 0 && out.size() >= max) {
                break;
            }
            readFile(f, null, out, max);
        }
        return out;
    }

    void search(String day, String query, int limit, List<Row> out) {
        File dir = new File(root, day);
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        List<File> files = new ArrayList<>();
        for (File f : fs) {
            if (f.getName().endsWith(".jsonl")) {
                files.add(f);
            }
        }
        Collections.sort(files);
        Collections.reverse(files);   // 新的优先
        for (File f : files) {
            if (out.size() >= limit) {
                break;
            }
            // 关键：limit 是"总共最多要几条"，由 readFile 自己控制增量，
            // 不能因为前一个文件读够了就跳过后面的文件（那会漏掉其它 App 的记录）
            readFile(f, query, out, limit);
        }
    }

    private void readFile(File f, String query, List<Row> out, int max) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String text = group(TEXT, line);
                if (query != null && !query.isEmpty() && !text.contains(query)) {
                    continue;
                }
                Row r = new Row();
                r.ts = group(TS, line);
                r.app = f.getName().replace(".jsonl", "");
                r.field = group(FIELD, line);
                r.comp = "true".equals(group(COMP, line));
                r.text = text;
                r.chars = text.length();
                r.minute = r.ts.length() >= 16 ? r.ts.substring(11, 16) : "";
                out.add(r);
                if (max > 0 && out.size() >= max) {
                    return;   // 只停"这个文件"，进而是"整个搜索"的增量上限
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String group(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (!m.find()) {
            return "";
        }
        String raw = m.group(1);
        // 这些字段的原始内容本来就是 JSON 字符串转义过的，这里还原
        return unescape(raw);
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    break;
                default: sb.append(n);
            }
        }
        return sb.toString();
    }

    synchronized void closeAll() {
        for (RandomAccessFile raf : handles.values()) {
            try {
                raf.close();
            } catch (Exception ignored) {
            }
        }
        handles.clear();
        for (RandomAccessFile raf : indexHandles.values()) {
            try {
                raf.close();
            } catch (Exception ignored) {
            }
        }
        indexHandles.clear();
    }

    synchronized long totalBytes() {
        return sizeOf(root);
    }

    private static long sizeOf(File f) {
        if (f.isFile()) {
            return f.length();
        }
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                n += sizeOf(k);
            }
        }
        return n;
    }

    /**
     * 原始文件清单：直接列出磁盘上每个 App 文件的行数与最后一条内容。
     * 绕开所有缓存与界面逻辑，是"到底存没存下来"最硬的证据。
     */
    List<String> fileInventory(String day, int lastChars) {
        List<String> out = new ArrayList<String>();
        File dir = new File(root, day);
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) {
            out.add("（" + day + " 目录不存在或为空）");
            return out;
        }
        List<File> files = new ArrayList<File>();
        for (File f : fs) {
            if (f.getName().endsWith(".jsonl")) {
                files.add(f);
            }
        }
        Collections.sort(files);
        for (File f : files) {
            List<Row> rows = new ArrayList<Row>();
            readFile(f, null, rows, 0);
            String last = rows.isEmpty() ? "" : rows.get(rows.size() - 1).text;
            if (last.length() > lastChars) {
                last = "…" + last.substring(last.length() - lastChars);
            }
            out.add(f.getName().replace(".jsonl", "")
                    + "\n    行数 " + rows.size() + "　" + f.length() + " 字节"
                    + "\n    最后一条：" + last.replace("\n", "⏎"));
        }
        if (out.isEmpty()) {
            out.add("（" + day + " 目录下没有任何 jsonl 文件）");
        }
        return out;
    }
    /** 供服务层使用：今天各 App 的中文名 */
    Map<String, String> labels(String day) {
        synchronized (this) {
            return new LinkedHashMap<>(dayIndex(day));
        }
    }

    /**
     * 清空全部记录。
     *
     * 必须先关掉所有文件句柄：记录文件是常开句柄的，Linux 上"删掉仍被打开的文件"
     * 不会真的释放，日志目录会看起来清空了却还占着空间。所以顺序是：
     * 关句柄 → 递归删目录 → 重建空目录 → 清掉内存里的索引缓存与统计。
     */
    /**
     * 清空前先把现有记录打包备份到同一目录下的 backup-<时间>.zip。
     *
     * 为什么要有它：清空是**不可恢复**操作，而它可能被误触发
     * （比如调试时用命令行 clear）。备份成本几 KB，但能救回全部历史。
     */
    File backupAll() {
        try {
            File[] days = root.listFiles();
            boolean has = false;
            if (days != null) {
                for (File d : days) {
                    if (d.isDirectory() && d.listFiles() != null && d.listFiles().length > 0) {
                        has = true;
                        break;
                    }
                }
            }
            if (!has) {
                return null;
            }
            String name = "backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.US).format(new java.util.Date()) + ".zip";
            File out = new File(root, name);
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(out)))) {
                for (File day : days) {
                    if (!day.isDirectory()) {
                        continue;
                    }
                    File[] files = day.listFiles();
                    if (files == null) {
                        continue;
                    }
                    for (File f : files) {
                        if (!f.isFile()) {
                            continue;
                        }
                        zos.putNextEntry(new java.util.zip.ZipEntry(day.getName() + "/" + f.getName()));
                        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                zos.write(buf, 0, n);
                            }
                        }
                        zos.closeEntry();
                    }
                }
            }
            lastBackup = out.getName();
            Log.i(TAG, "清空前已备份：" + out.getAbsolutePath());
            return out;
        } catch (Throwable t) {
            lastError = "备份失败: " + t;
            return null;
        }
    }

    /** 最近一次备份文件名（供界面显示） */
    volatile String lastBackup = "";
    /** 最近一次备份的完整路径（清空前自动生成） */
    volatile String lastBackupPath = "";

    synchronized void clearAll() {
        // 先把现有记录打包备份，再删。清空不可恢复，而它可能被误触发
        // （调试时用命令行 clear 就是一次真实误操作）。备份只有几 KB，却能救回全部历史。
        File backup = backupAll();
        if (backup != null) {
            File keep = new File(root.getParentFile(), backup.getName());
            if (backup.renameTo(keep)) {
                lastBackupPath = keep.getAbsolutePath();
            } else {
                lastBackupPath = backup.getAbsolutePath();
            }
        }
        closeAll();
        deleteTree(root);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        dayIndexCache.clear();
        synchronized (this) {
            lastLine = "";
            lastWriteAt = 0;
            writtenRows = 0;
            skippedRows = 0;
            byteCount = 0;
            lastError = "";
        }
    }
    /** 当前盘上有多少条记录（清理前给用户看一眼） */
    int countAll() {
        int n = 0;
        File[] days = root.listFiles();
        if (days == null) {
            return 0;
        }
        for (File d : days) {
            if (!d.isDirectory()) {
                continue;
            }
            File[] fs = d.listFiles();
            if (fs == null) {
                continue;
            }
            for (File f : fs) {
                if (!f.getName().endsWith(".jsonl")) {
                    continue;
                }
                List<Row> rows = new ArrayList<Row>();
                readFile(f, null, rows, 0);
                n += rows.size();
            }
        }
        return n;
    }
}
