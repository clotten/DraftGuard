#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step6.py —— 「工具」页的设置区改成：可折叠 + 开关直观显示开关状态。

用户要求：
  · 设置项会误触，希望能收起来
  · 点开后给一个设置列表
  · 用拨动开关，一眼看出开/关

实现：
  · 「设置」标题行可点，点击展开/收起（默认收起，避免误触）
  · 布尔项用 android.widget.Switch（框架自带，不需要 androidx）
  · 需要选值的项（最少字数 / 保留天数 / 忽略的 App / 保存位置）保持"点击弹出"样式
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import replace_method   # noqa: E402

MAIN = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

FIELDS = """    /** 设置区是否展开（默认收起，避免误触） */
    private boolean settingsExpanded = false;
    private LinearLayout settingsBox;
"""

NEW_METHODS = r'''/** 可点击的分节标题（带展开/收起指示） */
    private TextView sectionHeader(String title, boolean expanded, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText((expanded ? "▾ " : "▸ ") + title);
        t.setTextColor(COL_FG);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(4), dp(18), dp(4), dp(8));
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    /**
     * 设置区：默认收起，点标题展开。
     *
     * 用户反馈"设置项会误触" —— 页面上平铺 11 行、每行都能点，
     * 滚动时很容易碰到。收起后只有标题一行可点，误触面小得多。
     */
    private void buildSettingsBox() {
        if (settingsBox == null) {
            return;
        }
        settingsBox.removeAllViews();
        if (!settingsExpanded) {
            TextView hint = new TextView(this);
            hint.setText("已收起（共 11 项，点上面的「设置」展开）");
            hint.setTextColor(COL_DIM);
            hint.setTextSize(12);
            hint.setPadding(dp(4), 0, dp(4), dp(4));
            settingsBox.addView(hint);
            return;
        }
        // 布尔项：拨动开关，开/关一眼可见
        settingsBox.addView(switchRow("后台保活（通知栏常驻）", Prefs.keepAlive(this), 0));
        settingsBox.addView(switchRow("只记录有焦点的输入框", Prefs.focusOnly(this), 1));
        settingsBox.addView(switchRow("忽略删除操作", Prefs.ignoreDeletions(this), 2));
        settingsBox.addView(switchRow("排除输入法键盘事件", Prefs.skipIme(this), 3));
        settingsBox.addView(switchRow("记录文本被清空", Prefs.keepEmpty(this), 4));
        settingsBox.addView(switchRow("轮询兜底", Prefs.polling(this), 5));
        settingsBox.addView(switchRow("诊断日志", Prefs.debug(this), 6));
        // 需要选值的项：保持"点击弹出"
        settingsBox.addView(chooserRow("最少记录字数", Prefs.minChars(this) + " 字", 7));
        settingsBox.addView(chooserRow("保留天数", Prefs.retentionDays(this) + " 天", 8));
        settingsBox.addView(chooserRow("不记录的 App", "", 9));
        settingsBox.addView(chooserRow("保存位置", "", 10));
    }

    /** 一行开关：左标签，右 Switch */
    private View switchRow(String label, boolean value, final int kind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(COL_CARD, 12));
        row.setPadding(dp(14), dp(8), dp(8), dp(8));
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
            TextView v = new TextView(this);
            v.setText(value);
            v.setTextColor(COL_ACCENT);
            v.setTextSize(13);
            v.setPadding(0, 0, dp(6), 0);
            row.addView(v);
        }
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(COL_DIM);
        arrow.setTextSize(16);
        row.addView(arrow);
        return row;
    }

    /** 开关被拨动（kind 与 buildSettingsBox 里的顺序对应） */
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

    # 字段
    if "settingsExpanded" not in src:
        anchor = "    private TextView navToolsText;"
        if anchor not in src:
            raise SystemExit("字段锚点未找到")
        src = src.replace(anchor, FIELDS + anchor, 1)
        print("  ✓ 字段：settingsExpanded / settingsBox")

    # 设置区改为可折叠 + 开关
    old = """        toolsRoot.addView(sectionTitle("设置"));
        String[] items = settingItems();
        for (int i = 0; i < items.length; i++) {
            toolsRoot.addView(settingRow(items[i], i));
        }"""
    new = """        // 设置：默认收起（页面上平铺 11 行很容易误触），点标题展开；
        // 布尔项用拨动开关，开/关一眼可见。
        toolsRoot.addView(sectionHeader("设置", settingsExpanded,
                v -> {
                    settingsExpanded = !settingsExpanded;
                    buildToolsPage();
                }));
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        toolsRoot.addView(settingsBox);
        buildSettingsBox();"""
    if old not in src:
        raise SystemExit("设置区锚点未找到")
    src = src.replace(old, new, 1)
    print("  ✓ 设置区改为可折叠")

    # 用新的行构建方法替换旧的 settingRow
    src = replace_method(src, "private View settingRow(String label, final int which) {",
                         NEW_METHODS)
    print("  ✓ 替换为 switchRow / chooserRow / applySwitch / sectionHeader")

    MAIN.write_text(src, encoding="utf-8")
    print(f"已写入 {MAIN}")


if __name__ == "__main__":
    main()
