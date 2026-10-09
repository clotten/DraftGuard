#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step11.py —— 区分"系统无障碍总开关关着"与"我们的服务没开"。

用户反馈："我现在开启无障碍了还是会说记录停了"。
实测：enabled_accessibility_services 里有我们，但 accessibility_enabled=0，
Bound services 为空 —— 也就是用户在列表里打开了 DraftGuard，
却没打开 MIUI 无障碍页**顶部那个总开关**。

原来的四态会把这种情况笼统归为"已被系统停用"，指引不够准。
新增第五态 SVC_MASTER_OFF，直接说"总开关是关的"。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import replace_method   # noqa: E402

MAIN = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

SERVICE_STATE = r'''// ── 服务状态 ──
    private static final int SVC_OFF = 0;         // 我们的服务没启用
    private static final int SVC_CONNECTING = 1;  // 刚启动，还在连
    private static final int SVC_STALLED = 2;     // 服务开着却连不上（被系统停用）
    private static final int SVC_OK = 3;          // 正常
    private static final int SVC_MASTER_OFF = 4;  // 列表里有我们，但系统总开关是关的

    /** Activity 启动时刻，用于"宽限期"判断 */
    private long launchedAt;

    /** 服务连接宽限期：刚打开应用时服务往往还在连接，不能立刻报"被停用" */
    private static final long CONNECT_GRACE_MS = 6000;

    /**
     * 服务状态。
     *
     * 踩过的坑（两次）：
     *  1) 原来只判断"是否出现在 enabled_accessibility_services 里"，
     *     而 MIUI 停用服务后列表条目仍保留 → 界面显示"正在记录"，实际一条不记。
     *  2) 用户"在列表里打开了 DraftGuard"却仍报停用 —— 因为 MIUI 无障碍页
     *     **顶部还有一个总开关**（Settings.Secure.ACCESSIBILITY_ENABLED），
     *     总开关关着时，列表里的服务也不会被绑定。
     *     所以这两种情况必须分开说，指引才准确。
     */
    private int serviceState() {
        if (TypelogService.running) {
            return SVC_OK;
        }
        boolean listed = false;
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat != null) {
                ComponentName me = new ComponentName(this, TypelogService.class);
                listed = flat.contains(me.flattenToString())
                        || flat.contains(me.flattenToShortString());
            }
        } catch (Throwable ignored) {
        }
        if (!listed) {
            return SVC_OFF;
        }
        // 列表里有我们，但系统总开关关着 → 服务不可能被绑定
        try {
            String master = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (master != null && !"1".equals(master.trim())) {
                return SVC_MASTER_OFF;
            }
        } catch (Throwable ignored) {
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
                    + "**页面最上方**的那个总开关也要打开。";
        } else if (st == SVC_STALLED) {
            why = "无障碍服务曾经打开过，但现在没有连接。\n\n"
                    + "常见原因：系统（尤其是 MIUI）为了省电会自动停用它。";
        } else {
            why = "还没有打开无障碍服务。";
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
    src = replace_method(src, "private int serviceState() {", SERVICE_STATE)
    print("  ✓ 新增第五态：系统无障碍总开关关着")
    MAIN.write_text(src, encoding="utf-8")
    print(f"已写入 {MAIN}")


if __name__ == "__main__":
    main()
