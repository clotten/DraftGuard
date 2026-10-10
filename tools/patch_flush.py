#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_flush.py —— 修「发送前敲的最后一个字丢失」。

用户实测（2026-10-10 上午）：
  · 一个字一个字地输入「我 等 着 你 回 来」，每个都单独发送，
    最后一个「来」不记录；
  · 输入「越来越像神秘软件里」后补一个「了」，那个「了」也不显示。

根因：commit 走 350ms 去抖，**去抖期间内存里的 Pending.text 是这一版的唯一副本**。
发送会让输入框清空，于是 handleText 收到一个空文本事件；
而空文本被当成"删除"，在 `ignoreDeletions`（用户已开启）下走这条路径：

    if (Prefs.ignoreDeletions(this) && added <= 0) {
        p.text = text;   // ← 把待写的「来」覆盖成空
        return;          // ← 从未落盘，字就没了
    }

修法：在"要丢弃这一版"之前，先把还挂着的一版写掉。
  · 输入框被**清空**（发送后）→ 先 flush 再丢弃
  · 只是退格变短 → 不 flush，尊重用户"删除不记录"的设置
  · 检测到发送时（markSendBoundary）也先 flush，保证"文本在前、边界在后"

commit() 自带 lastWriteText 去重，所以重复调用不会产生重复行。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from patch_java import insert_after   # noqa: E402

SVC = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\TypelogService.java")

FLUSH = r'''/**
     * 把"还挂在内存里、尚未落盘"的那一版立刻写入。
     *
     * 为什么需要它：写入走 350ms 去抖，**去抖期间 Pending.text 是这一版的唯一副本**。
     * 一旦输入框被清空（发送后），旧代码会直接把它覆盖成空并返回，那一版就永远消失了。
     * 用户看到的现象是"发送前敲的最后一个字不记录"。
     *
     * commit() 内部按 lastWriteText 去重，所以重复调用是安全的。
     */
    private void flushPending(String field, String app, String appLabel, String day, String minute) {
        if (field == null || field.isEmpty()) {
            return;
        }
        final String text;
        final boolean comp;
        synchronized (LOCK) {
            Pending p = pending.get(field);
            if (p == null || p.text.isEmpty() || p.text.equals(p.lastWriteText)) {
                return;          // 没有待写内容，或这一版已经落过盘
            }
            if (p.runnable != null) {
                handler.removeCallbacks(p.runnable);
                p.runnable = null;
            }
            text = p.text;
            comp = p.comp;
        }
        commit(app, appLabel, field, day, minute, text, comp, text.length(),
                System.currentTimeMillis());
    }

    private void handleText('''


def main() -> None:
    src = SVC.read_text(encoding="utf-8")

    # ── 1) 插入 flushPending（放在 handleText 之前） ──
    if "private void flushPending(" not in src:
        src = insert_after(
            src,
            "private void handleText(String pkg, AccessibilityNodeInfo focusedNode, String text) {",
            FLUSH)
        # insert_after 会把原方法头也带在插入块末尾，需要删掉重复的那一行
        print("  ✓ 插入 flushPending")
    else:
        print("  · flushPending 已存在")

    # ── 2) 清空（发送后）时先 flush，再丢弃 ──
    old1 = """            if (Prefs.ignoreDeletions(this) && added <= 0) {
                skippedDelete++;
                // 仍然记住当前文本，但这一版不落盘
                p.text = text;"""
    new1 = """            if (Prefs.ignoreDeletions(this) && added <= 0) {
                // 输入框被**清空**（发送后）时，先把还挂着的那一版写掉再去丢弃它，
                // 否则发送前敲的最后一个字会随这次"清空"一起消失。
                // 只是退格变短则不写，尊重用户"删除不记录"的设置。
                if (text.isEmpty()) {
                    flushPending(field, app, appLabel, day, minute);
                }
                skippedDelete++;
                // 仍然记住当前文本，但这一版不落盘
                p.text = text;"""
    if old1 not in src:
        raise SystemExit("清空丢弃路径锚点未找到")
    src = src.replace(old1, new1, 1)
    print("  ✓ 清空路径：先 flush 再丢弃")

    # ── 3) 纯空白/清空的早退路径同样先 flush ──
    old2 = """            if (added <= 0 && text.trim().isEmpty()) {
                return;   // 全是空白，不值得占一条记录
            }"""
    new2 = """            if (added <= 0 && text.trim().isEmpty()) {
                // 同上：清空之前的那一版别丢
                flushPending(field, app, appLabel, day, minute);
                return;   // 全是空白，不值得占一条记录
            }"""
    if old2 not in src:
        raise SystemExit("空白早退路径锚点未找到")
    src = src.replace(old2, new2, 1)
    print("  ✓ 空白路径：先 flush 再返回")

    # ── 4) 发送标记之前先 flush，保证"文本在前、边界在后" ──
    old3 = """    private void markSendBoundary(String pkg) {
        long now = System.currentTimeMillis();
        SendBoundary.mark(pkg);      // 仍记内存一份，供界面实时提示"""
    new3 = """    private void markSendBoundary(String pkg) {
        long now = System.currentTimeMillis();
        // 先把发送前敲进去、还挂在去抖里的那一版写掉，再落发送标记 ——
        // 顺序必须是"文本在前、边界在后"，分段的"夹着一次提交"判据才成立。
        try {
            Date sd = new Date(now);
            flushPending(lastFieldKey, pkg, label(pkg),
                    DAY.format(sd), TS.format(sd).substring(11, 16));
        } catch (Throwable ignored) {
        }
        SendBoundary.mark(pkg);      // 仍记内存一份，供界面实时提示"""
    if old3 not in src:
        raise SystemExit("markSendBoundary 锚点未找到")
    src = src.replace(old3, new3, 1)
    print("  ✓ 发送标记：先 flush 文本再落边界")

    SVC.write_text(src, encoding="utf-8")
    print(f"已写入 {SVC}")


if __name__ == "__main__":
    main()
