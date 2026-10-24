package com.draftguard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * 无障碍服务"到底有没有在跑"的判定。
 *
 * <h3>为什么单独成类</h3>
 * 这套判定踩过三次坑，每次都是"界面显示正常、实际不记录"（本项目最危险的一类 bug）：
 * <ol>
 *   <li>只判断"是否在 {@code enabled_accessibility_services} 里" —— MIUI 停用后条目仍在，
 *       界面显示"正在记录"而实际不记。</li>
 *   <li>改用 secure settings 判断"已启用" —— 本机读不到该值，误判成"未启用"。</li>
 *   <li>现在：以 {@link AccessibilityManager}（系统给应用的正式 API）为主判据，
 *       secure settings 只作辅助，并把**判定依据**一起返回，供诊断面板显示。</li>
 * </ol>
 * 抽出来后，这套判定不再散落在 Activity 里，也不会被界面代码随意改。
 *
 * <h3>四条状态</h3>
 * <ul>
 *   <li>{@link #OK} 已连接 —— 真的在记录</li>
 *   <li>{@link #CONNECTING} 刚开启，还在连（有宽限期，避免刚开就报警）</li>
 *   <li>{@link #STALLED} 列表里有、但没连上（被系统/MIUI 停用）← 最需要用户动手的</li>
 *   <li>{@link #OFF} 没启用</li>
 * </ul>
 */
final class ServiceState {

    private ServiceState() {
    }

    /** 我们的服务没启用 */
    static final int OFF = 0;
    /** 刚启动，还在连 */
    static final int CONNECTING = 1;
    /** 服务开着却连不上（被系统停用） */
    static final int STALLED = 2;
    /** 正常 */
    static final int OK = 3;

    /** 判定结果 + 判定依据（依据要显示在诊断面板里，否则又变成"猜"） */
    static final class Result {
        int state;
        String reason = "";

        boolean recording() {
            return state == OK;
        }
    }

    /**
     * 探测服务状态。
     *
     * @param running    服务进程自己报告的"我连上了"（{@link TypelogService#running}）
     * @param launchedAt 应用启动时刻，用于给"刚开启"留连接宽限期
     * @param graceMs    宽限期毫秒
     */
    static Result detect(Context ctx, boolean running, long launchedAt, long graceMs) {
        Result r = new Result();
        boolean byManager = listedByManager(ctx);
        boolean bySettings = listedBySettings(ctx);
        boolean listed = byManager || bySettings;
        r.reason = "服务在已启用列表=" + (byManager ? "是" : "否")
                + "　secure设置里有=" + (bySettings ? "是" : "否")
                + "　服务已连接=" + (running ? "是" : "否");

        if (running) {
            r.state = OK;
            return r;
        }
        if (!listed) {
            r.state = OFF;
            return r;
        }
        r.state = (System.currentTimeMillis() - launchedAt < graceMs) ? CONNECTING : STALLED;
        return r;
    }

    /** 系统是否启用了无障碍（这台 MIUI 上没有"总开关"可点，只作为辅助信息） */
    static boolean masterOn(Context ctx) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统报告的"已启用无障碍服务"里是否包含我们 */
    static boolean listedByManager(Context ctx) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) {
                return false;
            }
            for (AccessibilityServiceInfo info : list) {
                android.content.pm.ResolveInfo ri = info.getResolveInfo();
                if (ri == null || ri.serviceInfo == null) {
                    continue;
                }
                if (ctx.getPackageName().equals(ri.serviceInfo.packageName)
                        && ri.serviceInfo.name != null
                        && ri.serviceInfo.name.contains("TypelogService")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 备用判据：secure settings 里是否列了我们的服务（有些 ROM 上读不到） */
    static boolean listedBySettings(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) {
                return false;
            }
            ComponentName me = new ComponentName(ctx, TypelogService.class);
            return flat.contains(me.flattenToString())
                    || flat.contains(me.flattenToShortString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 状态名（诊断面板用） */
    static String name(int state) {
        switch (state) {
            case OK:
                return "已连接";
            case CONNECTING:
                return "连接中";
            case STALLED:
                return "被系统停用";
            default:
                return "未启用";
        }
    }

    /** 采集状态文案（含"该怎么办"） */
    static String text(int state) {
        switch (state) {
            case OK:
                return "● 已开启，正在记录";
            case CONNECTING:
                return "◌ 正在连接无障碍服务…";
            case STALLED:
                // 实测 MIUI：列表里显示"开启"，实际服务并没连上，
                // 而系统那个 accessibility_enabled 标志也不是用户能打开的开关
                // （本机无障碍页根本没有总开关）。所以指引统一成"关掉再打开一次"。
                return "⚠ 服务没有真正连上 —— 现在打的字不会记录\n"
                        + "   到 系统设置 → 无障碍 → 已下载的应用 → DraftGuard，\n"
                        + "   把它**关掉再打开一次**\n"
                        + "   （列表里显示「开启」却没连上，在 MIUI 上很常见；\n"
                        + "     同时建议把省电策略设为「无限制」并打开「自启动」）";
            default:
                return "○ 未开启 —— 现在不会记录任何内容\n"
                        + "   到 系统设置 → 无障碍 → 已下载的应用 → 打开 DraftGuard";
        }
    }
}
