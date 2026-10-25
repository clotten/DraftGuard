package com.draftguard;

import android.content.Context;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「工具」页的**纯文本构建**（不碰 View、不读库）。
 *
 * <h3>为什么单独成类</h3>
 * 原来这些字符串散在 {@code refreshTools()} 里，和 {@code setCardText(...)} 调用交织，
 * 于是"文案"没法单独验证、也没法复用。抽成纯函数后：
 * <ul>
 *   <li>{@link #statsText} / {@link #appsText} 只依赖"行列表"，**可以在普通 JVM 上单测**</li>
 *   <li>界面那一侧只剩"读库 → 调用本类 → 贴到卡片上"三件事</li>
 *   <li>文案改错会直接体现在测试里，而不是等到用户看见</li>
 * </ul>
 */
final class ToolsText {

    private ToolsText() {
    }

    /** 实时预览的输入（由调用方从服务静态字段填好，本类不直接依赖 TypelogService） */
    static final class Live {
        String ts = "";
        String app = "";
        String appLabel = "";
        String field = "";
        String text = "";
    }

    /** 采集计数的输入 */
    static final class Status {
        long written;
        long evAll;
        long evText;
        long evCaptured;
        long evTraverseHit;
        long skippedPassword;
    }

    /** 实时预览卡片：最近一次输入 */
    static String livePreview(Live in) {
        if (in == null || TextUtils.isEmpty(in.ts)) {
            return "还没有捕获到输入。开启服务后，去任意 App 打几个字试试。";
        }
        String app = TextUtils.isEmpty(in.appLabel) ? in.app : in.appLabel;
        return "时间：" + in.ts + "\n"
                + "应用：" + (TextUtils.isEmpty(app) ? "（未知）" : app) + "\n"
                + "输入框：" + in.field + "\n"
                + "当前字数：" + in.text.length() + "\n"
                + "—— 最近一次内容 ——\n" + TextUtil.tail(in.text, 200);
    }

    /** 采集状态卡片（状态文案由 ServiceState 提供，这里补上计数） */
    static String status(String stateText, Status s) {
        return stateText
                + "\n本次会话落盘 " + s.written + " 条（服务重启会归零，历史不会丢）"
                + "\n收到事件 " + s.evAll
                + "（文本变化 " + s.evText + "）"
                + "\n取到文本 " + s.evCaptured
                + "　兜底找回 " + s.evTraverseHit
                + "　跳过密码框 " + s.skippedPassword;
    }

    /** 统计卡片：今天的版本数 / 未上屏 / 占用空间 / 最后一条 */
    static String statsText(List<LogStore.Row> rows, long bytes) {
        int total = 0;
        int compCount = 0;
        for (LogStore.Row r : rows) {
            if (r.text.isEmpty()) {
                continue;
            }
            total++;
            if (r.comp) {
                compCount++;
            }
        }
        StringBuilder st = new StringBuilder();
        st.append("今天落盘版本数：").append(total).append("\n");
        st.append("其中输入法未上屏：").append(compCount).append(" 条\n");
        st.append("占用空间：")
                .append(bytes < 1024 ? bytes + " 字节" : (bytes / 1024) + " KB").append("\n");
        if (!rows.isEmpty()) {
            LogStore.Row last = rows.get(rows.size() - 1);
            st.append("最后一条（从磁盘读回）：\n")
                    .append(last.ts.replace("T", " ").substring(0, 19)).append("\n")
                    .append(TextUtil.tail(last.text, 120)).append("\n");
        }
        st.append("保存位置：应用私有目录（其他 App 读不到）");
        return st.toString();
    }

    /** 应用清单卡片：今天记录过哪些应用、各多少条、最长多少字 */
    static String appsText(List<LogStore.Row> rows, Map<String, String> labels) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> chars = new LinkedHashMap<>();
        for (LogStore.Row r : rows) {
            if (r.text.isEmpty()) {
                continue;
            }
            Integer c = counts.get(r.app);
            counts.put(r.app, c == null ? 1 : c + 1);
            Integer mx = chars.get(r.app);
            chars.put(r.app, mx == null ? r.chars : Math.max(mx, r.chars));
        }
        if (counts.isEmpty()) {
            return "今天还没有记录。";
        }
        StringBuilder ap = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            ap.append("· ").append(TextUtil.name(labels, e.getKey()))
                    .append("（").append(e.getKey()).append("）  ")
                    .append(e.getValue()).append(" 条，最长 ")
                    .append(chars.get(e.getKey())).append(" 字\n");
        }
        return ap.toString().trim();
    }

    /**
     * 设置项文案。页面与设置弹窗**共用这一份**，避免两处不一致。
     * 顺序即 index，与 {@code applySetting(which)} 一一对应。
     */
    static String[] settingItems(Context ctx) {
        return new String[]{
                "后台保活：" + (Prefs.keepAlive(ctx) ? "开启（通知栏常驻）" : "关闭"),
                "只记录有焦点的输入框：" + (Prefs.focusOnly(ctx) ? "开启" : "关闭"),
                "忽略删除操作：" + (Prefs.ignoreDeletions(ctx)
                        ? "开启（删字不新建记录）" : "关闭（删除也记录）"),
                "排除输入法键盘事件：" + (Prefs.skipIme(ctx) ? "开启" : "关闭"),
                "最少记录字数（当前 " + Prefs.minChars(ctx) + " 字）",
                "旧记录保留：永不自动删除（只在你主动删除时减少）",
                "轮询兜底：" + (Prefs.polling(ctx) ? "开启" : "关闭"),
                "诊断日志：" + (Prefs.debug(ctx) ? "开启" : "关闭"),
                "记录文本被清空：" + (Prefs.keepEmpty(ctx) ? "记录" : "不记录"),
                "不记录的 App…",
                "显示保存位置",
        };
    }
}
