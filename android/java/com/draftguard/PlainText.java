package com.draftguard;

/**
 * 纯文本判定工具：不依赖任何安卓 API，可以在电脑上离线测试。
 *
 * 从 TypelogService 里抽出来的原因：那是个 Android 服务类，一旦被加载就需要框架支持，
 * 离线测试会直接抛 "Stub!"。而"这段文字是不是输入框的占位提示"完全是纯字符串逻辑，
 * 抽出来就能在普通 JVM 上跑断言（见 test/BurstTest.java）。
 */
final class PlainText {

    private PlainText() {
    }

    /**
     * 常见占位提示语，识别为噪音（不记录）。
     *
     * 踩过的坑：库里存的是 hint 原文（"搜索记录过的文字"），结尾的省略号是界面显示时才加的，
     * 曾经按含省略号的字符串比对，一条都没拦住。
     */
    static boolean isPlaceholder(String text) {
        if (text == null) {
            return false;
        }
        String bare = text.trim().replaceAll("[.…。]+$", "").trim();
        if (bare.isEmpty()) {
            return false;
        }
        // 带空格的提示语（小米笔记的「开始书写或 创建思维笔记」就是这种）
        String compact = bare.replaceAll("\\s+", "");
        if (compact.startsWith("开始书写") || compact.startsWith("创建思维笔记")
                || compact.startsWith("写点什么") || compact.startsWith("记录你的想法")
                || compact.startsWith("点击输入") || compact.startsWith("在此输入")) {
            return true;
        }
        if (bare.startsWith("搜索记录过的文字") || bare.startsWith("在这里打字")) {
            return true;
        }
        // 聊天类应用空输入框的提示语
        if (bare.equals("发消息") || bare.equals("发送消息") || bare.equals("说点什么")
                || bare.equals("说点什么吧") || bare.equals("聊点什么") || bare.equals("输入消息")
                || bare.equals("请输入") || bare.equals("请输入内容") || bare.equals("输入内容")
                || bare.equals("搜索") || bare.equals("输入…")) {
            return true;
        }
        // "搜索"/"输入"开头的短提示；限制长度避免误伤用户真打的句子
        return (bare.startsWith("搜索") || bare.startsWith("输入")) && bare.length() <= 6;
    }

    /**
     * 输入框默认占位文字的兜底判定。
     *
     * 小米笔记的「开始书写或 创建思维笔记」有两个坑：
     *   1) 它出现在该输入框的**第一条**记录里；
     *   2) 各机型/版本措辞不同，靠关键词列表补不完。
     * 所以再加一条形态特征：以"开始/创建/点击/在此/请…"这类祈使词开头，长度不长。
     *
     * 只在该输入框的首条记录上生效，正常书写几乎不会误伤。
     */
    static boolean looksLikeEmptyFieldHint(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim().replaceAll("\\s+", "");
        if (t.isEmpty() || t.length() > 20) {
            return false;
        }
        if (t.startsWith("开始书写") || t.startsWith("开始输入") || t.startsWith("开始记录")
                || t.startsWith("创建") || t.startsWith("点击") || t.startsWith("在此")
                || t.startsWith("请在此") || t.startsWith("输入标题") || t.startsWith("写点什么")) {
            return true;
        }
        // "开始"开头的短句，但要排除"会议开始了""比赛开始"这类正常表达
        return t.startsWith("开始") && t.length() <= 14
                && !t.endsWith("了") && !t.endsWith("啦") && !t.endsWith("吧");
    }
}
