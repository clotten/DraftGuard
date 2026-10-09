#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step10.py —— 采集状态要区分"列表里有"和"真的连着"。

用户反馈："系统的无障碍总开关是关的，但最上面显示采集状态是开启的，
           提示不够好，弹窗的话也要检测吧，给好提示"

根因：isServiceEnabled() 先看 TypelogService.running，但**取不到时还会退回
"是否在 enabled_accessibility_services 列表里"** —— 而 MIUI 把服务停用后
列表里的条目还在，于是界面显示"正在记录"，实际一条都不记。

改法：
  · 四态：已连接 / 连接中（启动宽限期，避免误报）/ 已被系统停用 / 未启用
  · 采集状态卡按状态给不同文案与操作指引
  · 服务被停用时弹窗提醒（带节流，且只在"以前开过"时提醒，不打扰新用户）
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import replace_method   # noqa: E402

MAIN = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

SERVICE_STATE = r'''// ── 服务状态：四态。关键区别是"在系统列表里"≠"真的连上了" ──
    private static final int SVC_OFF = 0;        // 没启用
    private static final int SVC_CONNECTING = 1; // 在列表里，但还刚启动，给它一点时间
    private static final int SVC_STALLED = 2;    // 在列表里，却一直没连上（被系统停用）
    private static final int SVC_OK = 3;         // 真的连上了

    /** Activity 启动时刻，用于"宽限期"判断 */
    private long launchedAt;

    /** 服务连接宽限期：刚打开应用时服务往往还在连接，不能立刻报"被停用" */
    private static final long CONNECT_GRACE_MS = 6000;

    /**
     * 服务状态。
     *
     * 踩过的坑：原来只判断"是否出现在 enabled_accessibility_services 里"，
     * 而 MIUI 停用服务后**列表条目仍然保留**，于是界面一直显示"正在记录"，
     * 实际一条都不记 —— 用户看着是好的，数据却在丢。
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
        // 在列表里但没连上：可能是刚启动还在连，也可能已被系统停用
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
            case SVC_STALLED:
                return "⚠ 服务已被系统停用 —— 现在打的字**不会**被记录\n"
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

    /** 上次弹"服务已停用"的时间，用于节流 */
    private long lastStalledWarnAt;

    /**
     * 服务被系统停用时给一个弹窗。
     *
     * 只在"以前确实开过"（Prefs.serviceEnabledAt > 0）时提醒，
     * 免得第一次装应用就弹一个用户看不懂的东西；并做 5 分钟节流。
     */
    private void warnIfServiceStalled() {
        int st = serviceState();
        boolean previouslyOn = Prefs.serviceEnabledAt(this) > 0;
        if (st != SVC_STALLED && !(st == SVC_OFF && previouslyOn)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStalledWarnAt < 5 * 60 * 1000L) {
            return;
        }
        lastStalledWarnAt = now;
        new android.app.AlertDialog.Builder(this)
                .setTitle("记录已经停了")
                .setMessage("无障碍服务没有连接，现在打的字不会被保存。\n\n"
                        + "常见原因：系统（尤其是 MIUI）为了省电会自动停用它。\n\n"
                        + "打开后建议顺手做两件事：\n"
                        + "· 应用管理 → DraftGuard → 省电策略 → 无限制\n"
                        + "· 打开「自启动」权限\n\n"
                        + "打开后回到本应用，「工具」页会显示「已开启，正在记录」。")
                .setPositiveButton("去系统设置", (d, w) -> openAccessibilitySettings())
                .setNegativeButton("知道了", null)
                .show();
    }
'''


def main() -> None:
    src = MAIN.read_text(encoding="utf-8")

    # 记录启动时刻
    if "launchedAt" not in src:
        anchor = "        setContentView(R.layout.activity_main);"
        if anchor not in src:
            raise SystemExit("onCreate 锚点未找到")
        src = src.replace(anchor,
                          anchor + "\n        launchedAt = System.currentTimeMillis();", 1)
        print("  ✓ onCreate：记录启动时刻")

    # 替换 isServiceEnabled 为四态实现
    src = replace_method(src, "private boolean isServiceEnabled() {", SERVICE_STATE)
    print("  ✓ 四态服务状态 + 弹窗提醒")

    # 采集状态卡改用 serviceStateText()
    old = '''        setCardText(statusView, "采集状态",
                (isServiceEnabled() ? "● 已开启，正在记录"
                        : "○ 未开启 —— 现在不会记录任何内容\\n"
                          + "   到 系统设置 → 无障碍 → DraftGuard 重新打开")'''
    new = '''        setCardText(statusView, "采集状态", serviceStateText()'''
    if old not in src:
        raise SystemExit("采集状态卡锚点未找到")
    src = src.replace(old, new, 1)
    print("  ✓ 采集状态卡改用四态文案")

    # 「去开启 / 检查服务」按钮改为语义化命名 + 直接去系统设置
    old2 = '''        Button btnOpen = button("去开启 / 检查服务");
        btnOpen.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));'''
    new2 = '''        Button btnOpen = button("打开系统无障碍设置");
        btnOpen.setOnClickListener(v -> openAccessibilitySettings());'''
    if old2 in src:
        src = src.replace(old2, new2, 1)
        print("  ✓ 按钮改为直接打开系统无障碍设置")

    MAIN.write_text(src, encoding="utf-8")
    print(f"已写入 {MAIN}")

    # onResume 里挂上检测（延迟到宽限期之后再判断）
    src = MAIN.read_text(encoding="utf-8")
    old3 = """        lastStatsRefresh = System.currentTimeMillis();
        refreshSummary();
        refreshList();
        refreshTools();"""
    new3 = """        lastStatsRefresh = System.currentTimeMillis();
        refreshSummary();
        refreshList();
        refreshTools();
        // 宽限期之后再判断服务是否真的连上，避免刚打开就误报"已停用"
        ui.postDelayed(this::checkAndWarnService, CONNECT_GRACE_MS + 1500);"""
    if old3 not in src:
        raise SystemExit("onResume 锚点未找到")
    src = src.replace(old3, new3, 1)

    # 检测方法：刷新界面状态 + 必要时弹窗
    old4 = "    /** 上次弹\"服务已停用\"的时间，用于节流 */"
    new4 = """    /** 宽限期后检查一次：刷新状态显示，必要时弹窗提醒 */
    private void checkAndWarnService() {
        if (statusView != null) {
            setCardText(statusView, "采集状态", serviceStateText());
        }
        warnIfServiceStalled();
    }

    /** 上次弹"服务已停用"的时间，用于节流 */"""
    if old4 not in src:
        raise SystemExit("节流字段锚点未找到")
    src = src.replace(old4, new4, 1)
    MAIN.write_text(src, encoding="utf-8")
    print("  ✓ onResume 挂钩检测与弹窗")


if __name__ == "__main__":
    main()
