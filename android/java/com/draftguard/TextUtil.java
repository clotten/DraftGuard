package com.draftguard;

import java.util.Map;

/**
 * 纯字符串工具（不碰 Android API、不读状态）。
 *
 * <h3>为什么单独成类</h3>
 * {@link Diagnostics} 和 {@link ToolsText} 都要"取末尾若干字"和"应用名兜底"。
 * 如果这两个工具留在 Diagnostics 里，任何想用它们的类都会被连带上
 * Diagnostics → TypelogService → KeepAliveService → R 这一长串依赖，
 * 于是**本来能离线跑的纯函数也跑不了了**。
 *
 * <p>这条链是本项目真实的坑：`ToolsTextTest` 一开始就因为间接依赖 R 而编译不过。
 */
final class TextUtil {

    private TextUtil() {
    }

    /** 取末尾 n 个字符（前面加省略号） */
    static String tail(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    /** 应用显示名：有标签用标签，没有就用包名 */
    static String name(Map<String, String> labels, String app) {
        String n = labels == null ? null : labels.get(app);
        return (n == null || n.isEmpty()) ? app : n;
    }
}
