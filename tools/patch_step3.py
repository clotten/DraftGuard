#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_step3.py ——
  1) 「全部时间」真的读所有日期（原来只读今天）
  2) 「诊断」补回重构时丢掉的六段内容（含"最近 25 条原始事件"）
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import replace_method   # noqa: E402

ROOT = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")

# ────────────────────────────────── 刷新列表：跨天读取
REFRESH_LIST = r'''private void refreshList() {
        if (adapter == null) {
            return;
        }
        final int first = listRecords.getFirstVisiblePosition();
        View v0 = listRecords.getChildAt(0);
        final int top = v0 == null ? 0 : v0.getTop();
        final boolean keep = listRecords.getCount() > 0;

        new Thread(() -> {
            LogStore store = new LogStore(getFilesDir(), 0);

            // 读**所有日期**，而不是只读今天 ——
            // 按钮写着「全部时间」，只给今天的数据是名不副实的。
            // days() 已按新→旧排序；跨天需要按时间重排后再分段。
            java.util.List<LogStore.Row> rows = new java.util.ArrayList<>();
            for (String day : store.days()) {
                rows.addAll(store.readDay(day, 0));
                if (rows.size() > 60000) {
                    break;      // 安全阀：数据异常大时不至于卡死
                }
            }
            java.util.Collections.sort(rows, (a, b) -> a.ts.compareTo(b.ts));

            long from = rangeMinutes <= 0 ? 0
                    : System.currentTimeMillis() - rangeMinutes * 60_000L;
            java.util.List<LogStore.Row> kept = new java.util.ArrayList<>();
            for (LogStore.Row r : rows) {
                if ("send".equals(r.ev) || r.text.isEmpty()) {
                    continue;
                }
                if (from > 0 && Burst.msOf(r.ts) < from) {
                    continue;
                }
                if (!appFilter.isEmpty() && !appFilter.contains(r.app)) {
                    continue;
                }
                if (!query.isEmpty() && !r.text.contains(query)) {
                    continue;
                }
                kept.add(r);
            }

            final java.util.List<Object> out = new java.util.ArrayList<>();
            if (showRawRows) {
                for (int i = kept.size() - 1; i >= 0 && out.size() < 400; i--) {
                    out.add(kept.get(i));
                }
            } else {
                java.util.List<Burst> bursts = Burst.groupNewestFirst(kept);
                for (int i = 0; i < bursts.size() && out.size() < 400; i++) {
                    out.add(bursts.get(i));
                }
            }

            ui.post(() -> {
                items.clear();
                items.addAll(out);
                adapter.notifyDataSetChanged();
                if (keep) {
                    listRecords.setSelectionFromTop(first, top);
                }
                if (items.isEmpty()) {
                    summaryText.setText(query.isEmpty()
                            ? "这个范围内还没有记录。去任意 App 打几个字就会出现在这里。"
                            : "没有找到包含「" + query + "」的记录。");
                }
            });
        }, "typelog-list").start();
    }

'''

# ────────────────────────────────── 诊断：搬回旧版的六段内容
SHOW_DIAG = r'''/** 诊断：重构时把原来页面上的内容都收到这里，注意别丢段落 */
    private void showDiag() {
        final android.widget.ScrollView sc = new android.widget.ScrollView(this);
        final TextView tv = new TextView(this);
        tv.setTextColor(COL_FG);
        tv.setTextSize(12);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextIsSelectable(true);
        tv.setText("读取中…");
        sc.addView(tv);

        new Thread(() -> {
            final LogStore store = new LogStore(getFilesDir(), 0);
            final String today = DAY.format(new Date());
            final Map<String, String> labels = store.labels(today);
            final java.util.List<LogStore.Row> rows = store.readDay(today, 0);
            final long bytes = store.totalBytes();

            int compCount = 0;
            int total = 0;
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, Integer> chars = new LinkedHashMap<>();
            for (LogStore.Row r : rows) {
                if (r.text.isEmpty()) {
                    continue;
                }
                total++;
                if (r.comp) {
                    compCount++;
                }
                Integer c = counts.get(r.app);
                counts.put(r.app, c == null ? 1 : c + 1);
                Integer mx = chars.get(r.app);
                chars.put(r.app, mx == null ? r.chars : Math.max(mx, r.chars));
            }

            StringBuilder sb = new StringBuilder();

            sb.append("── 事件计数（本次会话，服务重启会归零）──\n");
            sb.append("收到的无障碍事件总数：").append(TypelogService.evAll).append("\n");
            sb.append("其中文本变化事件：").append(TypelogService.evText).append("\n");
            sb.append("取到文本并进入记录：").append(TypelogService.evCaptured).append("\n");
            sb.append("事件里节点为空(靠兜底找回)：").append(TypelogService.evSourceNull)
              .append(" / 兜底成功 ").append(TypelogService.evTraverseHit).append("\n");
            sb.append("节点不是输入框：").append(TypelogService.evNotEditable).append("\n");
            sb.append("跳过系统UI/不可记录包：").append(TypelogService.skippedSelf).append("\n");
            sb.append("跳过输入法键盘自身事件：").append(TypelogService.skippedIme).append("\n");
            sb.append("跳过删除操作(按设置)：").append(TypelogService.skippedDelete).append("\n");
            sb.append("检测到点击「发送/搜索/发布」：").append(TypelogService.sendBoundaries)
              .append(" 次（消息分段依据）\n");
            sb.append("跳过未聚焦的框：").append(TypelogService.skippedNoFocus).append("\n");
            sb.append("跳过占位提示/单字碎片：").append(TypelogService.skippedNoise).append("\n");
            sb.append("跳过(设置里排除的)：").append(TypelogService.skippedIgnored).append("\n");
            sb.append("跳过密码框：").append(TypelogService.skippedPassword).append("\n");
            sb.append("写入失败：").append(TypelogService.errors).append("\n");
            sb.append("本次会话落盘：").append(TypelogService.written).append(" 条\n\n");

            sb.append("── 哪些应用发过事件（次数）──\n");
            java.util.List<Map.Entry<String, Integer>> es =
                    new ArrayList<>(TypelogService.ALL_EVENT_PKGS.entrySet());
            java.util.Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
            if (es.isEmpty()) {
                sb.append("（一次都没收到）\n");
            }
            for (int i = 0; i < es.size() && i < 12; i++) {
                Map.Entry<String, Integer> e = es.get(i);
                Integer t = TypelogService.TEXT_EVENT_PKGS.get(e.getKey());
                sb.append("· ").append(name(labels, e.getKey()))
                  .append("  [").append(e.getKey()).append("]  总 ").append(e.getValue())
                  .append(" 次，其中文本变化 ").append(t == null ? 0 : t).append(" 次\n");
            }

            Integer wx = TypelogService.ALL_EVENT_PKGS.get("com.tencent.mm");
            sb.append("\n微信(com.tencent.mm)：");
            if (wx == null) {
                sb.append("一次事件都没收到 ← 系统层面没放行");
            } else {
                Integer wxt = TypelogService.TEXT_EVENT_PKGS.get("com.tencent.mm");
                sb.append("收到 ").append(wx).append(" 次事件，其中文本变化 ")
                  .append(wxt == null ? 0 : wxt).append(" 次");
                if (wxt == null) {
                    sb.append(" ← 微信不发文本变化事件，应走轮询兜底（确认轮询是开的）");
                }
            }

            sb.append("\n\n── 最近一次扫描情况 ──\n")
              .append(TextUtils.isEmpty(TypelogService.lastScanInfo)
                      ? "（还没有扫描失败过）" : TypelogService.lastScanInfo).append("\n");

            sb.append("\n── 磁盘上的原始文件（绕开所有缓存，最硬的证据）──\n");
            for (String line : store.fileInventory(today, 60)) {
                sb.append("· ").append(line).append("\n");
            }

            sb.append("\n── 最近 25 条原始事件（需在设置里开诊断日志）──\n");
            java.util.List<String> diag = TypelogService.DIAG;
            synchronized (diag) {
                if (diag.isEmpty()) {
                    sb.append("（诊断日志未开启）\n");
                } else {
                    int from = Math.max(0, diag.size() - 25);
                    for (int i = from; i < diag.size(); i++) {
                        sb.append("· ").append(diag.get(i)).append("\n");
                    }
                }
            }

            if (TypelogService.skippedNoFocus > 0 && TypelogService.evCaptured == 0) {
                sb.append("\n⚠ 已跳过 ").append(TypelogService.skippedNoFocus)
                  .append(" 个未聚焦的框，且一条都没记到。\n")
                  .append("   焦点过滤可能对本机过严，到「设置」里关掉「只记当前焦点框」试试。\n");
            }

            sb.append("\n── 实时预览（最近一次输入）──\n");
            if (TextUtils.isEmpty(TypelogService.lastTs)) {
                sb.append("还没有捕获到输入。\n");
            } else {
                String app = TextUtils.isEmpty(TypelogService.lastAppLabel)
                        ? TypelogService.lastApp : TypelogService.lastAppLabel;
                sb.append("时间：").append(TypelogService.lastTs).append("\n");
                sb.append("应用：").append(TextUtils.isEmpty(app) ? "（未知）" : app).append("\n");
                sb.append("输入框：").append(TypelogService.lastField).append("\n");
                sb.append("当前字数：").append(TypelogService.lastText.length()).append("\n");
                sb.append("—— 最近一次内容 ——\n")
                  .append(tail(TypelogService.lastText, 300)).append("\n");
            }

            sb.append("\n── 统计（今天）──\n");
            sb.append("落盘版本数：").append(total).append("\n");
            sb.append("其中未上屏状态：").append(compCount).append(" 条\n");
            sb.append("占用空间：").append(bytes < 1024 ? bytes + " 字节" : (bytes / 1024) + " KB")
              .append("\n");
            if (!rows.isEmpty()) {
                LogStore.Row last = rows.get(rows.size() - 1);
                sb.append("最后一条：").append(last.ts.replace("T", " ").substring(0, 19))
                  .append("　").append(last.app).append("　").append(last.chars).append(" 字\n")
                  .append(tail(last.text, 120)).append("\n");
            }
            sb.append("保存位置：应用私有目录（其他 App 读不到）\n");

            sb.append("\n── 今天记录过的 App ──\n");
            if (counts.isEmpty()) {
                sb.append("还没有记录。\n");
            } else {
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    sb.append("· ").append(name(labels, e.getKey()))
                      .append("（").append(e.getKey()).append("）  ")
                      .append(e.getValue()).append(" 条，最长 ")
                      .append(chars.get(e.getKey())).append(" 字\n");
                }
            }

            final String text = sb.toString().trim();
            ui.post(() -> {
                tv.setText(text);
                new android.app.AlertDialog.Builder(this)
                        .setTitle("诊断")
                        .setView(sc)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }, "typelog-diag").start();
    }

'''

# 适配器：跨天时把日期也显示出来
OLD_META = '''                StringBuilder h = new StringBuilder();
                h.append(b.firstTs, 11, 16);
                if (!b.firstTs.substring(11, 16).equals(b.lastTs.substring(11, 16))) {
                    h.append("–").append(b.lastTs, 11, 16);
                }'''
NEW_META = '''                StringBuilder h = new StringBuilder();
                // 跨天查看时把日期带上，否则昨天的记录只显示时间会让人误会
                String today = DAY.format(new Date());
                if (!b.firstTs.startsWith(today)) {
                    h.append(b.firstTs, 5, 10).append(" ");
                }
                h.append(b.firstTs, 11, 16);
                if (!b.firstTs.substring(11, 16).equals(b.lastTs.substring(11, 16))) {
                    h.append("–").append(b.lastTs, 11, 16);
                }'''


def main() -> None:
    src = ROOT.read_text(encoding="utf-8")
    src = replace_method(src, "private void refreshList() {", REFRESH_LIST)
    print("  ✓ refreshList：改为读取所有日期")
    src = replace_method(src, "private void showDiag() {", SHOW_DIAG)
    print("  ✓ showDiag：补回应用事件次数/微信/扫描/磁盘文件/原始事件/告警")
    if OLD_META not in src:
        raise SystemExit("适配器 meta 锚点未找到")
    src = src.replace(OLD_META, NEW_META, 1)
    print("  ✓ 适配器：跨天时显示日期")
    ROOT.write_text(src, encoding="utf-8")
    print(f"已写入 {ROOT}")


if __name__ == "__main__":
    main()
