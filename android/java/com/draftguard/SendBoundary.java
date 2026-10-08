package com.draftguard;

/**
 * 「用户点了发送」这一信号的持有者。
 *
 * 为什么单独抽一个类：它是消息分段最可靠的判据（点了发送 ⇒ 上一条消息结束），
 * 但记录它的地方是 Android 的 AccessibilityService。如果 Burst 直接依赖那个服务类，
 * 离线测试就会因为加载 Android 框架而抛 "Stub!" —— 合并逻辑正是最需要测试的部分。
 *
 * 所以：服务负责"检测并写入"，Burst 只读；两边都通过这个纯 Java 类交互。
 */
final class SendBoundary {

    private SendBoundary() {
    }

    private static volatile long lastSendTs;
    private static volatile String lastSendPkg = "";
    private static volatile long count;

    /** 服务检测到"发送"点击时调用 */
    static void mark(String pkg) {
        lastSendPkg = pkg == null ? "" : pkg;
        lastSendTs = System.currentTimeMillis();
        count++;
    }

    /** 这条记录的时刻之后，是否发生过发送 */
    static boolean sendAfter(String isoTs) {
        long t = Burst.msOf(isoTs);
        return lastSendTs > 0 && t > 0 && lastSendTs > t;
    }

    static long lastTs() {
        return lastSendTs;
    }

    static String lastPkg() {
        return lastSendPkg;
    }

    static long count() {
        return count;
    }

    /** 仅供测试：重置状态 */
    static void resetForTest() {
        lastSendTs = 0;
        lastSendPkg = "";
        count = 0;
    }

    /** 仅供测试：伪造一次"刚发生发送" */
    static void markForTest(long ts, String pkg) {
        lastSendTs = ts;
        lastSendPkg = pkg == null ? "" : pkg;
        count++;
    }
}
