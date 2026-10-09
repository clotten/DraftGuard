#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step2.py —— 修掉重构后的残留引用（颜色常量、页面切换、旧方法调用）。"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import delete_method, replace_method   # noqa: E402

ROOT = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

# 先删掉遗留的旧 doSearch（用 resultView 的那个），后面重新加新的
DO_SEARCH = r'''private void doSearch() {
        if (searchBox == null) {
            return;
        }
        query = searchBox.getText().toString().trim();
        if (query.isEmpty()) {
            toast("已取消筛选");
        }
        refreshList();
        if (!query.isEmpty()) {
            toast("已筛选包含「" + query + "」的记录");
        }
    }

'''

REPLACEMENTS: list[tuple[str, str]] = [
    # onCreate：不再有 root/scrollRoot，改为构建记录页
    ("""        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        scrollRoot = (android.widget.ScrollView) root.getParent();
        setupNav();""",
     """        setContentView(R.layout.activity_main);
        setupNav();"""),

    ("""        buildUi();
    }""",
     """        buildRecordsPage();
    }"""),

    # 广播接收器：只刷摘要与列表（列表会保留滚动位置）
    ("""            refreshLive();
            // 统计卡片原来只在 onResume 时刷新，结果记录在涨、卡片却停在启动那一刻，
            // 看起来像"什么都没记"。这里让它跟着记录走，但节流以免频繁读盘。
            long now = System.currentTimeMillis();
            if (now - lastStatsRefresh > STATS_REFRESH_MS) {
                lastStatsRefresh = now;
                refreshAll();
            }""",
     """            // 只刷新摘要与列表。列表内部会保留滚动位置，
            // 不再重写"滚动位置上方的卡片"（那会让界面看起来自己跳动）。
            long now = System.currentTimeMillis();
            if (now - lastStatsRefresh > STATS_REFRESH_MS) {
                lastStatsRefresh = now;
                refreshSummary();
                refreshList();
            }"""),

    # onResume
    ("""        lastStatsRefresh = System.currentTimeMillis();
        refreshAll();""",
     """        lastStatsRefresh = System.currentTimeMillis();
        refreshSummary();
        refreshList();"""),

    # setupNav：page1 现在是 LinearLayout（pageRoot）
    ("""        page1 = findViewById(R.id.page1);""",
     """        pageRoot = findViewById(R.id.page1);"""),

    ("""        page1.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);""",
     """        pageRoot.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);"""),

    # 导出结果改为 toast（原来的结果区已不存在）
    ("""                    toast("导出失败：无法写入下载目录");
                    setCard(resultView, "导出结果",
                            "导出失败。可以改用「分享 zip」发给微信/QQ。");""",
     """                    toast("导出失败：无法写入下载目录，可以改用「分享 zip」");"""),

    ("""                    String path = "下载/" + Exporter.PUBLIC_SUBDIR + "/DraftGuard-" + Exporter.stamp() + ".zip";
                    toast("已导出到 " + path);
                    setCard(resultView, "导出结果",
                            "已导出到公共下载目录：\\n" + path + "\\n\\n"
                            + "用文件管理器打开「下载 / DraftGuard」就能看到这个 zip。");""",
     """                    String path = "下载/" + Exporter.PUBLIC_SUBDIR + "/DraftGuard-" + Exporter.stamp() + ".zip";
                    toast("已导出到 " + path);"""),

    # 清除结果
    ("""                            ui.post(() -> {
                                setCard(resultView, "清除结果",
                                        "已清除全部记录（" + total + " 条）。\\n"
                                        + "之后新打的字会重新开始记录。");
                                refreshAll();
                                toast("已清除 " + total + " 条记录");
                            });""",
     """                            ui.post(() -> {
                                toast("已清除 " + total + " 条记录，之后新打的字会重新记录");
                                refreshSummary();
                                refreshList();
                            });"""),

    # 时间范围 / 应用筛选 后刷新列表
    ("""                    rangeMinutes = opts[which];
                    toast("已切换到「" + labels[which] + "」");
                    showToday();""",
     """                    rangeMinutes = opts[which];
                    if (btnRange != null) {
                        btnRange.setText(rangeLabel());
                    }
                    toast("已切换到「" + labels[which] + "」");
                    refreshList();"""),

    ("""                        .setPositiveButton("应用", (d, w) -> showToday())""",
     """                        .setPositiveButton("应用", (d, w) -> refreshList())"""),

    ("""                        .setNeutralButton("清空筛选", (d, w) -> {
                            appFilter.clear();
                            showToday();
                        })""",
     """                        .setNeutralButton("清空筛选", (d, w) -> {
                            appFilter.clear();
                            refreshList();
                        })"""),
]

COLORS = """
    // 配色：只用三种前景色，靠字号而不是靠颜色做层次
    private static final int COL_FG = 0xFFE8ECF3;      // 正文
    private static final int COL_DIM = 0xFF8B95A7;     // 次要信息
    private static final int COL_ACCENT = 0xFF5B9DFF;  // 可点/强调
    private static final int COL_CARD = 0xFF1A1D26;    // 卡片底色
"""


def main() -> None:
    src = ROOT.read_text(encoding="utf-8")

    # 1) 删掉遗留的旧 doSearch，再加新的
    if "setCard(resultView, \"搜索结果\"" in src:
        src = delete_method(src, "private void doSearch() {")
        print("  ✓ 删除旧的 doSearch（使用 resultView 的那个）")
        anchor = "private void refreshList() {"
        a, b = __import__("patch_java").find_method(src, anchor)
        src = src[:b] + "\n\n    " + DO_SEARCH.rstrip() + src[b:]
        print("  ✓ 插入新的 doSearch（过滤列表）")

    # 2) 颜色常量
    if "COL_ACCENT" not in src.split("private void buildRecordsPage")[0]:
        anchor = "    private LinearLayout pageRoot;"
        if anchor not in src:
            raise SystemExit("找不到字段区锚点")
        src = src.replace(anchor, COLORS.strip() + "\n\n" + anchor, 1)
        print("  ✓ 添加颜色常量")

    # 3) 逐条字符串替换
    for old, new in REPLACEMENTS:
        if old not in src:
            raise SystemExit(f"替换锚点未找到:\n{old[:120]}")
        src = src.replace(old, new, 1)
        print(f"  ✓ {old.strip().splitlines()[0][:60]}")

    # 4) 清理不再使用的方法
    for m in ("private static CharSequence mergeTitle(String title, CharSequence body) {",):
        if m in src:
            src = delete_method(src, m)
            print(f"  ✓ 删除不再使用的方法 {m.split('(')[0].split()[-1]}")

    ROOT.write_text(src, encoding="utf-8")
    print(f"已写入 {ROOT}")


if __name__ == "__main__":
    main()
