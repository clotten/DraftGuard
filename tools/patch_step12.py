#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step12.py —— 服务状态判断改用 AccessibilityManager。

问题：之前靠解析 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 判断
"我们的服务是否被启用"，但实测在本机（MIUI 12.5）读不到内容 →
判定成"未启用"，提示就变成了"还没有打开无障碍服务"，与实际不符。

改用系统给应用准备的 API：
  · AccessibilityManager.isEnabled()                     —— 总开关
  · AccessibilityManager.getEnabledAccessibilityServiceList() —— 已启用的服务
同时保留 secure settings 作为辅助判据（两个来源取"或"），
并把探测到的原始依据写进诊断面板，方便以后再出问题时一眼看清。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import replace_method   # noqa: E402

MAIN = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

NEW = r'''// ── 服务状态 ──
    private static final int SVC_OFF = 0;         // 我们的服务没启用
    private static final int SVC_CONNECTING = 1;  // 刚启动，还在连
    private static final int SVC_STALLED = 2;     // 服务开着却连不上（被系统停用）
    private static final int SVC_OK = 3;          // 正常
    private static final int SVC_MASTER_OFF = 4;  // 列表里有我们，但系统总开关是关的

    /** Activity 启动时刻，用于"宽限期"判断 */
    private long launchedAt;

    /** 服务连接宽限期：刚打开应用时服务往往还在连接，不能立刻报"被停用" */
    private static final long CONNECT_GRACE_MS = 6000;

    /** 最近一次状态探测的原始依据（写进诊断，方便排查"为什么这么判断"） */
    private volatile String lastStateProbe = "";

    /**
     * 系统无障碍总开关是否打开。
     *
     * 用 AccessibilityManager（系统给应用准备的 API），
     * 不再依赖解析 Settings.Secure —— 实测本机读不到那个值，
     * 会把"已启用"误判成"未启用"。
     */
    private boolean accessibilityMasterOn() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统报告的"已启用无障碍服务"里是否包含我们 */
    private boolean listedByManager() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            java.util.List<android.accessibilityservice.AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(
                            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) {
                return false;
            }
            for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
                android.content.pm.ResolveInfo ri = info.getResolveInfo();
                if (ri == null || ri.serviceInfo == null) {
                    continue;
                }
                if (getPackageName().equals(ri.serviceInfo.packageName)
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
    private boolean listedBySettings() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) {
                return false;
            }
            ComponentName me = new ComponentName(this, TypelogService.class);
            return flat.contains(me.flattenToString())
                    || flat.contains(me.flattenToShortString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 服务状态。
     *
     * 踩过的坑（三次，逐步收紧）：
     *  1) 只判断"是否在 enabled_accessibility_services 里" → MIUI 停用后条目仍在，
     *     界面显示"正在记录"而实际不记。
     *  2) 改用 secure settings 判断"已启用" → 本机读不到该值，误判成"未启用"。
     *  3) 现在以 AccessibilityManager 为主判据（系统给应用的正式 API），
     *     secure settings 只作辅助；并把探测依据记录到 lastStateProbe 供诊断。
     */
    private int serviceState() {
        boolean master = accessibilityMasterOn();
        boolean byManager = listedByManager();
        boolean bySettings = listedBySettings();
        boolean listed = byManager || bySettings;
        lastStateProbe = "总开关=" + (master ? "开" : "关")
                + "　服务在已启用列表=" + (byManager ? "是" : "否")
                + "　secure设置里有=" + (bySettings ? "是" : "否")
                + "　服务已连接=" + (TypelogService.running ? "是" : "否");

        if (TypelogService.running) {
            return SVC_OK;
        }
        if (!master) {
            // 总开关关着：无论列表里有没有我们，服务都不会被绑定
            return SVC_MASTER_OFF;
        }
        if (!listed) {
            return SVC_OFF;
        }
        return (System.currentTimeMillis() - launchedAt < CONNECT_GRACE_MS)
                ? SVC_CONNECTING : SVC_STALLED;
    }

    private boolean isServiceEnabled() {
        return serviceState() == SVC_OK;
    }

    /** 采集状态文案（含"该怎么办"） */
    private String serviceStateText() {
        switch (serviceState()) {
            case SVC_OK:
                return "● 已开启，正在记录";
            case SVC_CONNECTING:
                return "◌ 正在连接无障碍服务…";
            case SVC_MASTER_OFF:
                return "⚠ 系统的「无障碍」总开关是关的 —— 现在打的字不会记录\n"
                        + "   到 系统设置 → 无障碍，把**最上面**的总开关打开\n"
                        + "   （只在下面列表里打开 DraftGuard 还不够）";
            case SVC_STALLED:
                return "⚠ 服务已被系统停用 —— 现在打的字不会记录\n"
                        + "   到 系统设置 → 无障碍 → 重新打开 DraftGuard\n"
                        + "   （MIUI 等系统会自行停用，建议同时把省电策略设为「无限制」）";
            default:
                return "○ 未开启 —— 现在不会记录任何内容\n"
                        + "   到 系统设置 → 无障碍 → 打开 DraftGuard";
        }
    }

    /** 去系统无障碍设置 */
    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            toast("打不开系统设置，请手动进入：设置 → 无障碍");
        }
    }

    /** 上次弹"服务没在记录"的时间，用于节流 */
    private long lastStalledWarnAt;

    /**
     * 服务没在记录时给一个弹窗。
     *
     * 只在"以前确实开过"（Prefs.serviceEnabledAt > 0）时提醒，
     * 免得第一次装应用就弹一个用户看不懂的东西；并做 5 分钟节流。
     */
    private void warnIfServiceStalled() {
        int st = serviceState();
        boolean abnormal = (st == SVC_STALLED || st == SVC_MASTER_OFF
                || (st == SVC_OFF && Prefs.serviceEnabledAt(this) > 0));
        if (!abnormal) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStalledWarnAt < 5 * 60 * 1000L) {
            return;
        }
        lastStalledWarnAt = now;

        String why;
        if (st == SVC_MASTER_OFF) {
            why = "系统的「无障碍」总开关是关着的。\n\n"
                    + "注意：只在下面的服务列表里打开 DraftGuard 还不够，"
                    + "**页面最上方**的总开关也要打开。";
        } else if (st == SVC_STALLED) {
            why = "无障碍服务曾经打开过，但现在没有连接。\n\n"
                    + "常见原因：系统（尤其是 MIUI）为了省电会自动停用它。";
        } else {
            why = "无障碍服务没有打开。";
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("记录已经停了")
                .setMessage("现在打的字不会被保存。\n\n" + why + "\n\n"
                        + "打开后建议顺手做两件事：\n"
                        + "· 应用管理 → DraftGuard → 省电策略 → 无限制\n"
                        + "· 打开「自启动」权限\n\n"
                        + "回到本应用后，「工具」页会显示「● 已开启，正在记录」。")
                .setPositiveButton("去系统设置", (d, w) -> openAccessibilitySettings())
                .setNegativeButton("知道了", null)
                .show();
    }
'''


def main() -> None:
    src = MAIN.read_text(encoding="utf-8")
    src = replace_method(src, "private int serviceState() {", NEW)
    print("  ✓ serviceState 改用 AccessibilityManager 为主判据")

    # 诊断面板加上探测依据
    old = '''        sb.append("\\n最近 25 条原始事件：\\n");
        sb.append("诊断日志开关：").append(Prefs.debug(this) ? "已开启" : "未开启")
          .append("　无障碍服务：").append(TypelogService.running ? "已连接" : "未连接").append("\\n");'''
    new = '''        sb.append("\\n最近 25 条原始事件：\\n");
        sb.append("诊断日志开关：").append(Prefs.debug(this) ? "已开启" : "未开启")
          .append("　无障碍服务：").append(TypelogService.running ? "已连接" : "未连接").append("\\n");
        // 状态判断的原始依据：出问题时先看这一行，就知道是"读不到"还是"确实没开"
        serviceState();
        sb.append("状态探测依据：").append(lastStateProbe)
          .append("　判定=").append(serviceStateName()).append("\\n");'''
    if old not in src:
        raise SystemExit("诊断锚点未找到")
    src = src.replace(old, new, 1)

    # 状态名（诊断用）
    helper = '''/** 状态名（诊断面板显示判定结果） */
    private String serviceStateName() {
        switch (serviceState()) {
            case SVC_OK:
                return "已连接";
            case SVC_CONNECTING:
                return "连接中";
            case SVC_STALLED:
                return "被系统停用";
            case SVC_MASTER_OFF:
                return "总开关关着";
            default:
                return "未启用";
        }
    }

    /** 去系统无障碍设置 */'''
    src = src.replace("    /** 去系统无障碍设置 */", helper, 1)

    MAIN.write_text(src, encoding="utf-8")
    print("  ✓ 诊断面板新增「状态探测依据」一行")
    print(f"已写入 {MAIN}")


if __name__ == "__main__":
    main()
