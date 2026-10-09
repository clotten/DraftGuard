#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""merge_export.py —— 把导出的一堆 jsonl 合并成**单个可读文件**（Markdown / 纯文本）。

为什么需要它：
  · 导出数据是「按天 + 按应用」拆成很多 jsonl 的，人读和网页版 AI 都不方便；
  · jsonl 里每条只存"当前完整文本"，读起来是一堆半截句子；
  · 网页版通常不能上传文件/不支持 json，需要一个能直接复制粘贴的整块文本。

本脚本做的事：
  1) 合并所有天的所有应用；
  2) 复现应用内的分段逻辑（Burst），把每个输入框的连续输入合成"一段完整的话"；
  3) 按 日期 → 时间倒序 输出，每段带「时间范围 · 应用名 · 字数 · 合并版本数」；
  4) 可选过滤：--skip-tests 去掉明显的测试噪音，--app 只看某应用，--since 只看某时刻后。

用法：
  python merge_export.py <解压目录> -o 输出.md
  python merge_export.py <解压目录> -o 输出.txt --plain --skip-tests
  python merge_export.py <解压目录> -o out.md --app larus --since 12:00
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

# ── 与 Burst.java 保持一致的分段参数 ─────────────────────────────
GAP_MS = 5 * 60 * 1000
LONG_GAP_MS = 6 * 60 * 60 * 1000
KEEP_RATIO = 0.5
MIN_LEN_FOR_REWRITE = 4
MIN_COMMON_PREFIX = 2
RAPID_EDIT_MS = 10_000
RAPID_EDIT_MAX = 8
LOCAL_EDIT_MS = 5 * 60 * 1000
LOCAL_EDIT_MIN_PREFIX = 3
LOCAL_EDIT_MAX_TAIL = 4

COMMON_CHARS = set("的了吗呢啊吧呀哦嗯是你我在有和就不人都一上了也还很 ，。！")

# 明显的测试噪音（用户自己测试应用时打的字，与真实内容混在一起会干扰阅读）
TEST_MARKERS = ("测试", "test", "TEST", "嘿嘿", "戳戳", "啊啊", "asdf", "你好呀")


# ── 工具函数 ─────────────────────────────────────────────────────
def ms_of(iso: str) -> int:
    try:
        return int(datetime.strptime(iso[:23], "%Y-%m-%dT%H:%M:%S.%f").timestamp() * 1000)
    except Exception:
        return 0


def common_prefix(a: str, b: str) -> int:
    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    return i


def is_full_rewrite(a: str, b: str) -> bool:
    if not a or not b:
        return False
    shorter = min(len(a), len(b))
    if shorter < MIN_LEN_FOR_REWRITE:
        return False
    # 共同前缀占比过低
    return common_prefix(a, b) / shorter < KEEP_RATIO


def share_content_char(a: str, b: str) -> bool:
    for ch in a:
        if ch in COMMON_CHARS:
            continue
        if ch in b:
            return True
    return False


def is_localized_edit(a: str, b: str) -> bool:
    cp = common_prefix(a, b)
    if cp < LOCAL_EDIT_MIN_PREFIX:
        return False
    ra, rb = a[cp:], b[cp:]
    if len(ra) <= LOCAL_EDIT_MAX_TAIL and len(rb) <= LOCAL_EDIT_MAX_TAIL:
        return True
    return bool(ra) and bool(rb) and (ra.startswith(rb) or rb.startswith(ra))


def mergeable(cur: dict, nxt: dict, sends: list | None = None) -> bool:
    if cur["app"] != nxt["app"]:
        return False
    # 两条文本之间夹着一次"提交"（发送/搜索/发布）⇒ 必定分段
    if sends:
        frm, to = ms_of(cur["ts"]), ms_of(nxt["ts"])
        if any(frm < t <= to for t in sends):
            return False
    # 空 field = 改造前的旧记录，视为与任何标识同框
    if cur["field"] and nxt["field"] and cur["field"] != nxt["field"]:
        return False

    gap = ms_of(nxt["ts"]) - ms_of(cur["ts"])
    a, b = cur["text"], nxt["text"]
    continuing = bool(a) and b.startswith(a)
    # 旧记录没有 field，分不清"是不是同一个输入框"，判据取宽的一档：
    # 若按 5 分钟切，会把"同一次输入里的长停顿"（实测 5.7 分钟）当成两段，
    # 用户看到的就是"打成两段"。宁可把不同输入框合并（内容不丢），也不拆散一段话。
    legacy = not cur["field"] and not nxt["field"]
    limit = LONG_GAP_MS if (continuing or legacy) else GAP_MS
    if gap > limit:
        return False

    if not a and not b:
        return True
    if not a or not b:
        return False
    if b.startswith(a) or a.startswith(b):
        return True
    if is_full_rewrite(a, b):
        return False
    if is_localized_edit(a, b) and gap <= LOCAL_EDIT_MS:
        return True
    if common_prefix(a, b) >= MIN_COMMON_PREFIX:
        return True
    if gap <= RAPID_EDIT_MS and len(a) <= RAPID_EDIT_MAX and len(b) <= RAPID_EDIT_MAX:
        return share_content_char(a, b)
    return False


def load(root: Path) -> tuple[list[dict], dict]:
    """读入全部记录，返回 (记录列表, 应用名映射)。"""
    rows: list[dict] = []
    labels: dict[str, str] = {}
    for day_dir in sorted([d for d in root.iterdir() if d.is_dir()]):
        idx = day_dir / "index.json"
        if idx.exists():
            try:
                obj = json.loads(idx.read_text(encoding="utf-8"))
                apps = obj.get("apps") or obj
                if isinstance(apps, dict):
                    for k, v in apps.items():
                        if isinstance(v, str):
                            labels[k] = v
                        elif isinstance(v, dict) and v.get("label"):
                            labels[k] = v["label"]
            except Exception:
                pass
        for f in sorted(day_dir.glob("*.jsonl")):
            app = f.stem
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    continue
                o["app"] = app
                o["field"] = o.get("field", "") or ""
                rows.append(o)
    rows.sort(key=lambda r: r.get("ms") or ms_of(r.get("ts", "")))
    return rows, labels


def group(rows: list[dict]) -> list[dict]:
    # 排序键必须是「应用 + 输入框 + 时间」：否则中途切到别的应用，
    # 那条记录会插进同一段话的两条之间，把一段话拆成两段。
    rows = sorted(rows, key=lambda r: (r["app"], r["field"], r.get("ms") or ms_of(r.get("ts", ""))))
    out: list[dict] = []
    sends = sorted(ms_of(r["ts"]) for r in rows if r.get("ev") == "send")
    for r in rows:
        if r.get("ev") == "send":
            continue                      # 发送标记只作边界，不是内容
        if out and mergeable(out[-1], r, sends):
            out[-1]["text"] = r["text"]
            out[-1]["ts_end"] = r["ts"]
            out[-1]["versions"] += 1
        else:
            out.append({"app": r["app"], "field": r["field"], "text": r["text"],
                        "ts": r["ts"], "ts_end": r["ts"], "versions": 1})
    content = [b for b in out if b["text"]]
    content.sort(key=lambda b: ms_of(b["ts"]))   # 分组排序后按时间还原
    return content


def looks_like_test(text: str) -> bool:
    t = text.strip()
    if len(t) <= 4:
        return True
    for m in TEST_MARKERS:
        if m in t and len(t) <= 12:
            return True
    if t.isascii() and len(t) <= 12:      # 纯英文短串基本是测试
        return True
    return False


def fmt_span(b: dict) -> str:
    a = b["ts"][11:16]
    z = b["ts_end"][11:16]
    return a if a == z else f"{a}–{z}"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", help="解压后的日志目录（含 2026-10-09 这样的日期目录）")
    ap.add_argument("-o", "--out", required=True, help="输出文件路径")
    ap.add_argument("--plain", action="store_true", help="输出纯文本而不是 Markdown")
    ap.add_argument("--skip-tests", action="store_true", help="过滤明显的测试噪音")
    ap.add_argument("--app", default=None, help="只看匹配该子串的应用")
    ap.add_argument("--since", default=None, help="只看该时刻之后（HH:MM）")
    ap.add_argument("--min-chars", type=int, default=1, help="小于该字数的段不输出")
    args = ap.parse_args()

    root = Path(args.dir)
    rows, labels = load(root)
    if not rows:
        print("没有读到任何记录", file=sys.stderr)
        return 1

    if args.app:
        rows = [r for r in rows if args.app in r["app"]]
    if args.since:
        rows = [r for r in rows if r["ts"][11:16] >= args.since]
    if not rows:
        print("过滤后没有记录", file=sys.stderr)
        return 1

    bursts = group(rows)
    if args.skip_tests:
        bursts = [b for b in bursts if not looks_like_test(b["text"])]
    if args.min_chars > 1:
        bursts = [b for b in bursts if len(b["text"]) >= args.min_chars]

    def label(app: str) -> str:
        return labels.get(app) or app

    total_chars = sum(len(b["text"]) for b in bursts)
    by_day: dict[str, list[dict]] = {}
    for b in bursts:
        by_day.setdefault(b["ts"][:10], []).append(b)

    lines: list[str] = []
    if args.plain:
        lines.append("DraftGuard 记录汇总")
        lines.append("=" * 60)
        lines.append(f"生成时间：{datetime.now():%Y-%m-%d %H:%M}")
        lines.append(f"共 {len(bursts)} 段（原始 {len(rows)} 个版本），合计 {total_chars} 字")
        lines.append("")
        lines.append("说明：每条是「一次连续输入最后成型的完整内容」。")
        lines.append("同一次输入过程中的中间版本不在下方显示（仍存于原始数据中）。")
        lines.append("")
        for day in sorted(by_day, reverse=True):
            lines.append("")
            lines.append(f"──── {day} ────")
            for b in sorted(by_day[day], key=lambda x: x["ts"], reverse=True):
                extra = f"  合并{b['versions']}版" if b["versions"] > 1 else ""
                lines.append("")
                lines.append(f"[{fmt_span(b)}] {label(b['app'])}  {len(b['text'])}字{extra}")
                lines.append(b["text"])
        # 应用统计
        lines.append("")
        lines.append("──── 各应用统计 ────")
        stat: dict[str, list[int]] = {}
        for b in bursts:
            s = stat.setdefault(b["app"], [0, 0])
            s[0] += 1
            s[1] += len(b["text"])
        for app, (n, ch) in sorted(stat.items(), key=lambda kv: -kv[1][1]):
            lines.append(f"  {label(app):<16} {n:>4} 段  {ch:>6} 字")
    else:
        lines.append("# DraftGuard 记录汇总")
        lines.append("")
        lines.append(f"- 生成时间：{datetime.now():%Y-%m-%d %H:%M}")
        lines.append(f"- 共 **{len(bursts)} 段**（原始 {len(rows)} 个版本），合计 **{total_chars} 字**")
        lines.append("")
        lines.append("> 每条是「一次连续输入最后成型的完整内容」。同一次输入过程中的中间版本"
                     "不在下方显示（仍完整存于原始数据）。")
        lines.append("")
        lines.append("## 各应用统计")
        lines.append("")
        lines.append("| 应用 | 段数 | 字数 |")
        lines.append("|---|---:|---:|")
        stat: dict[str, list[int]] = {}
        for b in bursts:
            s = stat.setdefault(b["app"], [0, 0])
            s[0] += 1
            s[1] += len(b["text"])
        for app, (n, ch) in sorted(stat.items(), key=lambda kv: -kv[1][1]):
            lines.append(f"| {label(app)} | {n} | {ch} |")
        for day in sorted(by_day, reverse=True):
            lines.append("")
            lines.append(f"## {day}")
            for b in sorted(by_day[day], key=lambda x: x["ts"], reverse=True):
                extra = f" · 合并 {b['versions']} 版" if b["versions"] > 1 else ""
                lines.append("")
                lines.append(f"### [{fmt_span(b)}] {label(b['app'])} — {len(b['text'])} 字{extra}")
                lines.append("")
                lines.append(b["text"])

    text = "\n".join(lines) + "\n"
    out = Path(args.out)
    out.write_text(text, encoding="utf-8")
    print(f"已写出 {out}")
    print(f"  段数 {len(bursts)}（原始 {len(rows)} 版本），合计 {total_chars} 字")
    print(f"  文件大小 {out.stat().st_size / 1024:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
