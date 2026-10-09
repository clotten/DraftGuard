package com.draftguard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 拼接 / 解析。
 *
 * 为什么不直接用系统的 org.json：它只在设备上可用（android.jar 里是空实现），
 * 用它会让存储层无法在电脑上离线测试。自己拼转义规则很小、可控，
 * 也避开了"应用被打包后行为不一致"的风险。
 */
final class Json {

    private Json() {
    }

    /** 按 JSON 规范转义一个字符串（控制字符转成十六进制转义形式） */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** 还原上面的转义（读日志时用） */
    static String unesc(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s == null ? "" : s;
        }
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

    /** 拼一条记录行（不含换行符） */
    static String line(Record r) {
        StringBuilder sb = new StringBuilder(256 + r.text.length());
        sb.append('{');
        kv(sb, "ts", r.ts, true);
        num(sb, "ms", r.ms);
        kv(sb, "day", r.day, false);
        kv(sb, "minute", r.minute, false);
        kv(sb, "bucket", r.day + " " + r.minute, false);
        kv(sb, "app", r.app, false);
        kv(sb, "ev", r.ev, false);
        // 输入框标识必须存盘：分段（Burst）要按"同一输入框"归组，
        // 如果只在渲染时重新计算，控件树一变标识就变，同一段话会被拆成多段
        // （实测豆包：间隔 1.7 分钟的续写被分成两段）。存下来后分段完全可复现。
        if (r.field != null && !r.field.isEmpty()) {
            kv(sb, "field", r.field, false);
        }
        num(sb, "chars", r.text.length());
        num(sb, "delta", r.delta);
        sb.append(",\"comp\":").append(r.comp);
        if (r.detail != null && !r.detail.isEmpty()) {
            kv(sb, "detail", r.detail, false);
        }
        kv(sb, "text", r.text, false);
        sb.append('}');
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"');
    }

    private static void num(StringBuilder sb, String k, long v) {
        sb.append(",\"").append(k).append("\":").append(v);
    }

    /** 拼索引文件（包名 -> 中文名） */
    static String object(Map<String, String> map) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            kv(sb, e.getKey(), e.getValue(), first);
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 解析上面 object() 产生的扁平字符串对象。
     * 只处理"字符串键 → 字符串值"这一种形状，够用且不容易出错。
     */
    static Map<String, String> parseObject(String s) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (s == null) {
            return out;
        }
        int i = s.indexOf('{');
        if (i < 0) {
            return out;
        }
        i++;
        while (i < s.length()) {
            while (i < s.length() && (s.charAt(i) == ',' || Character.isWhitespace(s.charAt(i)))) {
                i++;
            }
            if (i >= s.length() || s.charAt(i) == '}') {
                break;
            }
            if (s.charAt(i) != '"') {
                break;
            }
            int[] pos = new int[]{i};
            String key = readString(s, pos);
            i = pos[0];
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ':')) {
                i++;
            }
            if (i >= s.length() || s.charAt(i) != '"') {
                break;
            }
            pos[0] = i;
            String val = readString(s, pos);
            i = pos[0];
            out.put(key, val);
        }
        return out;
    }

    /** 从 s[i] 处的引号开始读一个字符串，返回原文（已反转义），并把下标推到闭引号之后 */
    private static String readString(String s, int[] io) {
        int i = io[0];
        if (i >= s.length() || s.charAt(i) != '"') {
            return "";
        }
        i++;
        StringBuilder raw = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                raw.append(c).append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                i++;
                break;
            }
            raw.append(c);
            i++;
        }
        io[0] = i;
        return unesc(raw.toString());
    }
}
