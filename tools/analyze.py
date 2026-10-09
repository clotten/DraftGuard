#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
analyze.py —— 在电脑上离线分析导出的日志，复现应用内的分段逻辑（Burst）。

目的：手机上只能看到一个片段，在电脑上能整体看清"分段是否符合预期"，
并定位"无法整段展示"具体发生在哪些记录上。

用法：
    python analyze.py <解压目录>              # 概览 + 分段统计
    python analyze.py <解压目录> --app larus  # 只看某个应用
    python analyze.py <解压目录> --show        # 打印每段内容
    python analyze.py <解压目录> --raw        # 打印原始记录
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

GAP_MS = 5 * 60 * 1000
KEEP_RATIO = 0.5
MIN_LEN_FOR_REWRITE = 4
MIN_COMMON_PREFIX = 2


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
    return common_prefix(a, b) / shorter < KEEP_RATIO


def mergeable(cur: dict, nxt: dict) -> bool:
    if cur["app"] != nxt["app"] or cur["field"] != nxt["field"]:
        return False
    if ms_of(nxt["ts"]) - ms_of(cur["ts"]) > GAP_MS:
        return False
    a, b = cur["text"], nxt["text"]
    if not a and not b:
        return True
    if not a or not b:
        return False
    if b.startswith(a) or a.startswith(b):
        return True
    if is_full_rewrite(a, b):
        return False
    if common_prefix(a, b) >= MIN_COMMON_PREFIX:
        return True
    # 短间隔 + 短内容 + 共用实义字 ⇒ 整词打错重打（实测 来发展 → 开发者）
    gap = ms_of(nxt["ts"]) - ms_of(cur["ts"])
    if gap <= 10_000 and len(a) <= 8 and len(b) <= 8:
        return share_content_char(a, b)
    return False


COMMON = set("的了吗呢啊吧呀哦嗯是你我在有和就不人都一上了也还很 ，。！")


def share_content_char(a: str, b: str) -> bool:
    for ch in a:
        if ch in COMMON:
            continue
        if ch in b:
            return True
    return False


def load(day_dir: Path) -> list[dict]:
    rows: list[dict] = []
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
    rows.sort(key=lambda r: r.get("ms", 0))
    return rows


def group(rows: list[dict]) -> list[dict]:
    out: list[dict] = []
    for r in rows:
        if out and mergeable(out[-1], r):
            out[-1]["text"] = r["text"]
            out[-1]["ts_end"] = r["ts"]
            out[-1]["versions"] += 1
        else:
            out.append({"app": r["app"], "field": r["field"], "text": r["text"],
                        "ts": r["ts"], "ts_end": r["ts"], "versions": 1})
    return [b for b in out if b["text"]]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", help="解压后的日志目录（含 2026-10-09 这样的日期目录）")
    ap.add_argument("--app", default=None, help="只看匹配该子串的应用")
    ap.add_argument("--show", action="store_true", help="打印每段内容")
    ap.add_argument("--raw", action="store_true", help="打印原始记录")
    args = ap.parse_args()

    root = Path(args.dir)
    days = sorted([d for d in root.iterdir() if d.is_dir()], reverse=True)
    if not days:
        print("没有找到日期目录", file=sys.stderr)
        return 1

    for day_dir in days:
        rows = load(day_dir)
        if args.app:
            rows = [r for r in rows if args.app in r["app"]]
        if not rows:
            continue

        bursts = group(rows)
        apps: dict[str, int] = {}
        for r in rows:
            apps[r["app"]] = apps.get(r["app"], 0) + 1

        print(f"\n{'=' * 72}")
        print(f"日期 {day_dir.name}：原始 {len(rows)} 个版本 → 合并 {len(bursts)} 段")
        print(f"{'=' * 72}")

        if args.raw:
            for r in rows:
                t = r["text"].replace("\n", "\\n")
                print(f"  {r['ts'][11:23]} [{r['app'].split('.')[-1]:<12}] "
                      f"chars={len(r['text']):<4} <{t[:90]}>")
            continue

        # 按应用统计
        print(f"\n各应用：")
        for app, n in sorted(apps.items(), key=lambda kv: -kv[1]):
            bn = len([b for b in bursts if b["app"] == app])
            print(f"  {app:<32} {n:>4} 版本 → {bn:>3} 段")

        # 找出"可疑段"：段内含多次大幅修改 / 文本很短 / 明显不是用户输入
        print(f"\n分段详情（每段一行）：")
        for b in reversed(bursts):
            t = b["text"].replace("\n", "\\n")
            mark = ""
            if b["versions"] > 1:
                mark = f"  [合并 {b['versions']} 版]"
            short = b["app"].split(".")[-1][:12]
            print(f"  {b['ts'][11:16]} {short:<13} {len(b['text']):>4}字{mark}")
            if args.show:
                print(f"      {t[:200]}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
