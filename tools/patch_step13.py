#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step13.py —— 两处按用户反馈修正：

1) 删掉"系统总开关"这一态。用户实测：MIUI 的无障碍页**根本没有总开关**，
   accessibility_enabled 只是"有服务被启用"的结果标志，不是可打开的开关。
   说"去打开总开关"会把人带到一个不存在的地方。
   改为统一、可执行的指引：到列表里把 DraftGuard「关掉再打开一次」。

2) 诊断展开后不实时刷新。上次把刷新从 refreshTools() 移走时只保留了
   "展开时刷一次"，事件到来后不再更新。现在：诊断展开状态下，
   收到事件就刷新（节流 2 秒）。

注意：本脚本只替换方法体，**不含字段声明** —— 之前几个脚本把字段一起塞进去，
造成重复定义，反复返工。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import replace_method   # noqa: E402

MAIN = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

SERVICE_STATE = r'''private int serviceState() {
        boolean byManager = listedByManager();
        boolean bySettings = listedBySettings();
        boolean listed = byManager || bySettings;
        lastStateProbe = "服务在已启用列表=" + (byManager ? "是" : "否")
                + "　secure设置里有=" + (bySettings ? "是" : "否")
                + "　服务已连接=" + (TypelogService.running ? "是" : "否");

        if (TypelogService.running) {
            return SVC_OK;
        }
        if (!listed) {
            return SVC_OFF;
        }
        return (System.currentTimeMillis() - launchedAt < CONNECT_GRACE_MS)
                ? SVC_CONNECTING : SVC_STALLED;
    }'''

SERVICE_STATE_TEXT = r'''private String serviceStateText() {
        switch (serviceState()) {
            case SVC_OK:
                return "● 已开启，正在记录";
            case SVC_CONNECTING:
                return "◌ 正在连接无障碍服务…";
            case SVC_STALLED:
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
    }'''

WARN = r'''private void warnIfServiceStalled() {
        int st = serviceState();
        boolean abnormal = (st == SVC_STALLED
                || (st == SVC_OFF && Prefs.serviceEnabledAt(this) > 0));
        if (!abnormal) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStalledWarnAt < 5 * 60 * 1000L) {
            return;
        }
        lastStalledWarnAt = now;

        String why = (st == SVC_STALLED)
                ? "无障碍服务曾经打开过，但现在没有真正连上。\n\n"
                  + "在「已下载的应用」列表里它可能仍显示「开启」，"
                  + "这是 MIUI 上很常见的假象。"
                : "还没有打开无障碍服务。";

        new android.app.AlertDialog.Builder(this)
                .setTitle("记录已经停了")
                .setMessage("现在打的字不会被保存。\n\n" + why + "\n\n"
                        + "解决：到 系统设置 → 无障碍 → 已下载的应用 → DraftGuard，\n"
                        + "把它「关掉再打开一次」。\n\n"
                        + "顺手再做两件事更稳：\n"
                        + "· 应用管理 → DraftGuard → 省电策略 → 无限制\n"
                        + "· 打开「自启动」权限\n\n"
                        + "回到本应用后，「工具」页会显示「● 已开启，正在记录」。")
                .setPositiveButton("去系统设置", (d, w) -> openAccessibilitySettings())
                .setNegativeButton("知道了", null)
                .show();
    }'''


def main() -> None:
    src = MAIN.read_text(encoding="utf-8")

    src = replace_method(src, "private int serviceState() {", SERVICE_STATE)
    print("  ✓ serviceState：去掉「总开关」判据")
    src = replace_method(src, "private String serviceStateText() {", SERVICE_STATE_TEXT)
    print("  ✓ serviceStateText：统一成「关掉再打开一次」")
    src = replace_method(src, "private void warnIfServiceStalled() {", WARN)
    print("  ✓ 弹窗文案同步")

    # 诊断：展开状态下收到事件就刷新（节流 2 秒）
    old = """                refreshSummary();
                refreshList();
                if (currentPage == 2) {
                    refreshTools();
                }"""
    new = """                refreshSummary();
                refreshList();
                if (currentPage == 2) {
                    refreshTools();
                    // 诊断展开时也要跟着刷新。
                    // 之前只在"展开那一刻"填一次，之后事件来了内容不更新，
                    // 看起来就像"卡住了 / 不实时"。
                    if (diagExpanded && now - lastDiagRefresh > DIAG_REFRESH_MS) {
                        lastDiagRefresh = now;
                        refreshDiagBox();
                    }
                }"""
    if old not in src:
        raise SystemExit("广播处理锚点未找到")
    src = src.replace(old, new, 1)
    print("  ✓ 事件到达时刷新诊断（节流）")

    # 节流字段与刷新方法
    helper = '''/** 诊断刷新节流间隔 */
    private static final long DIAG_REFRESH_MS = 2000;
    private long lastDiagRefresh;

    /** 只刷新诊断卡片内容（不重建整个区块，避免闪烁与滚动跳动） */
    private void refreshDiagBox() {
        if (!diagExpanded) {
            return;
        }
        if (diagView == null) {
            buildDiagBox();
            return;
        }
        new Thread(() -> {
            final String d = diagText().toString();
            ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag-refresh").start();
    }

    /** 更新折叠标题的箭头 */'''
    src = src.replace("    /** 更新折叠标题的箭头 */", helper, 1)

    # buildDiagBox 里的填充改成调用 refreshDiagBox，避免两处逻辑
    old2 = '''        new Thread(() -> {
            final String d = diagText().toString();
            ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag").start();
    }

    /** 一行开关：左标签，右 Switch */'''
    new2 = '''        refreshDiagBox();
    }

    /** 一行开关：左标签，右 Switch */'''
    if old2 in src:
        src = src.replace(old2, new2, 1)
        print("  ✓ buildDiagBox 复用 refreshDiagBox")

    MAIN.write_text(src, encoding="utf-8")
    print(f"已写入 {MAIN}")


if __name__ == "__main__":
    main()
