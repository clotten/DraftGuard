#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step7.py —— 「工具」页两处改进（按用户要求）：

  1) 设置：可折叠（防误触）+ 布尔项改成拨动开关，开/关一眼可见
  2) 诊断：默认折叠（内容确实长），点标题展开

要点：折叠只重建**本区块**（settingsBox / diagBox），
不重建整页 —— 否则一展开就跳回页顶。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import replace_method   # noqa: E402

MAIN = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

FIELDS = """    /** 设置区是否展开（默认收起：页面上平铺十来行很容易误触） */
    private boolean settingsExpanded = false;
    private LinearLayout settingsBox;
    private TextView settingsHeader;

    /** 诊断区是否展开（默认收起：内容很长，压住页面） */
    private boolean diagExpanded = false;
    private LinearLayout diagBox;
    private TextView diagHeader;
"""

# 设置区 + 诊断区的构建（替换 buildToolsPage 里的这两段）
NEW_SECTIONS = r'''        // ── 设置：默认收起，点标题展开；布尔项用拨动开关，开/关一眼可见 ──
        settingsHeader = sectionHeader("设置", settingsExpanded, v -> {
            settingsExpanded = !settingsExpanded;
            updateHeader(settingsHeader, "设置", settingsExpanded);
            buildSettingsBox();
        });
        toolsRoot.addView(settingsHeader);
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        toolsRoot.addView(settingsBox);
        buildSettingsBox();

        // ── 诊断：默认收起（内容很长），点标题展开 ──
        diagHeader = sectionHeader("诊断", diagExpanded, v -> {
            diagExpanded = !diagExpanded;
            updateHeader(diagHeader, "诊断", diagExpanded);
            buildDiagBox();
        });
        toolsRoot.addView(diagHeader);
        diagBox = new LinearLayout(this);
        diagBox.setOrientation(LinearLayout.VERTICAL);
        toolsRoot.addView(diagBox);
        buildDiagBox();
'''

NEW_HELPERS = r'''/** 更新折叠标题的箭头 */
    private void updateHeader(TextView t, String title, boolean expanded) {
        if (t != null) {
            t.setText((expanded ? "▾ " : "▸ ") + title);
        }
    }

    /** 一行提示（收起状态下用） */
    private TextView collapsedHint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(COL_DIM);
        t.setTextSize(12);
        t.setPadding(dp(4), 0, dp(4), dp(4));
        return t;
    }

    /**
     * 只重建设置区，不重建整页 ——
     * 否则一展开就跳回页顶，反而更难用。
     */
    private void buildSettingsBox() {
        if (settingsBox == null) {
            return;
        }
        settingsBox.removeAllViews();
        if (!settingsExpanded) {
            settingsBox.addView(collapsedHint("已收起（共 11 项，点上面的「设置」展开）"));
            return;
        }
        // 布尔项：拨动开关
        settingsBox.addView(switchRow("后台保活（通知栏常驻）", Prefs.keepAlive(this), 0));
        settingsBox.addView(switchRow("只记录有焦点的输入框", Prefs.focusOnly(this), 1));
        settingsBox.addView(switchRow("忽略删除操作", Prefs.ignoreDeletions(this), 2));
        settingsBox.addView(switchRow("排除输入法键盘事件", Prefs.skipIme(this), 3));
        settingsBox.addView(switchRow("记录文本被清空", Prefs.keepEmpty(this), 4));
        settingsBox.addView(switchRow("轮询兜底", Prefs.polling(this), 5));
        settingsBox.addView(switchRow("诊断日志", Prefs.debug(this), 6));
        // 需要选值的项：保持"点开选择"
        settingsBox.addView(chooserRow("最少记录字数", Prefs.minChars(this) + " 字", 4));
        settingsBox.addView(chooserRow("保留天数", Prefs.retentionDays(this) + " 天", 5));
        settingsBox.addView(chooserRow("不记录的 App", "", 9));
        settingsBox.addView(chooserRow("保存位置", "", 10));
    }

    /** 只重建诊断区 */
    private void buildDiagBox() {
        if (diagBox == null) {
            return;
        }
        diagBox.removeAllViews();
        if (!diagExpanded) {
            diagView = null;
            diagBox.addView(collapsedHint("已收起（内容较长，含最近 25 条原始事件，点上面展开）"));
            return;
        }
        diagBox.addView(collapsedHint("排查问题用。内容较长，往下滚即可。"));
        diagView = cardView("事件与存储", "读取中…");
        diagBox.addView(diagView);
        new Thread(() -> {
            final String d = diagText().toString();
            ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag").start();
    }

    /** 一行开关：左标签，右 Switch */
    private View switchRow(String label, boolean value, final int kind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(COL_CARD, 12));
        row.setPadding(dp(14), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COL_FG);
        t.setTextSize(13);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(value);
        sw.setShowText(false);
        sw.setOnCheckedChangeListener((btn, checked) -> applySwitch(kind, checked));
        row.addView(sw);
        return row;
    }

    /** 一行"点开选择"：左标签，右当前值 + 箭头 */
    private View chooserRow(String label, String value, final int which) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(COL_CARD, 12));
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> {
            applySetting(which);
            buildSettingsBox();      // 值可能变了，重建这一块
        });

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COL_FG);
        t.setTextSize(13);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        if (!value.isEmpty()) {
            TextView val = new TextView(this);
            val.setText(value);
            val.setTextColor(COL_ACCENT);
            val.setTextSize(13);
            val.setPadding(0, 0, dp(6), 0);
            row.addView(val);
        }
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(COL_DIM);
        arrow.setTextSize(16);
        row.addView(arrow);
        return row;
    }

    /** 开关被拨动（kind 与 buildSettingsBox 的顺序对应） */
    private void applySwitch(int kind, boolean on) {
        switch (kind) {
            case 0:
                Prefs.setKeepAlive(this, on);
                if (on) {
                    KeepAliveService.start(this);
                } else {
                    KeepAliveService.stop(this);
                }
                toast(on ? "保活已开启：通知栏会出现常驻通知"
                         : "保活已关闭：内存紧张时系统可能中断记录");
                break;
            case 1:
                Prefs.setFocusOnly(this, on);
                toast(on ? "只记正在输入的框（推荐）" : "所有可编辑框都会被记录");
                break;
            case 2:
                Prefs.setIgnoreDeletions(this, on);
                toast(on ? "删字不再新建记录" : "删除也会被记录");
                break;
            case 3:
                Prefs.setSkipIme(this, on);
                toast(on ? "已排除输入法键盘事件" : "已包含输入法键盘事件");
                break;
            case 4:
                Prefs.setKeepEmpty(this, on);
                toast(on ? "文本被清空也会记录一条" : "文本清空不记录");
                break;
            case 5:
                Prefs.setPolling(this, on);
                toast("轮询兜底已" + (on ? "开启" : "关闭") + "（下次生效）");
                break;
            default:
                Prefs.setDebug(this, on);
                if (on) {
                    TypelogService.DIAG.clear();
                }
                toast("诊断日志已" + (on ? "开启" : "关闭"));
                break;
        }
    }
'''


def main() -> None:
    src = MAIN.read_text(encoding="utf-8")

    if "settingsExpanded" not in src:
        anchor = "    private TextView navToolsText;"
        if anchor not in src:
            raise SystemExit("字段锚点未找到")
        src = src.replace(anchor, FIELDS + anchor, 1)
        print("  ✓ 字段：折叠状态与容器")

    # 替换 buildToolsPage 里的设置区 + 诊断区
    start = src.find('        toolsRoot.addView(sectionTitle("设置"));')
    end = src.find('        diagView = cardView("事件与存储", "读取中…");')
    if start < 0 or end < 0:
        raise SystemExit("设置/诊断区锚点未找到")
    end = src.find("\n", end) + 1
    src = src[:start] + NEW_SECTIONS + src[end:]
    print("  ✓ 设置区与诊断区改为可折叠")

    # 用新方法替换旧的 settingRow
    src = replace_method(src, "private View settingRow(String text, final int which) {",
                         NEW_HELPERS)
    print("  ✓ 替换为 switchRow / chooserRow / applySwitch / buildXxxBox")

    # refreshTools 里原来无条件刷新诊断，现在只在展开时刷（buildDiagBox 里做）
    old_diag_refresh = """        new Thread(() -> {
            final String d = diagText().toString();
            ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag").start();
    }"""
    if old_diag_refresh in src:
        src = src.replace(old_diag_refresh, "    }", 1)
        print("  ✓ refreshTools 不再重复刷诊断（改由 buildDiagBox 负责）")

    MAIN.write_text(src, encoding="utf-8")
    print(f"已写入 {MAIN}")


if __name__ == "__main__":
    main()
