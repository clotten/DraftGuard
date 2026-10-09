#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""find_same_prefix.py —— 找出"前缀相同（其中一个以另一个开头）却分属两段"的记录对。

这是用户反馈"有开头都是一样的不知道为什么（没合并）"的直接验证工具。
如果某对记录满足"后一条以前一条开头"，理论上应被合并（除非被某条规则拦下），
若它们仍分属两段，说明有一条规则在拦截 —— 那就把它找出来。
"""
import json
import sys
from pathlib import Path
from datetime import datetime

sys.path.insert(0, str(Path(__file__).parent))
from analyze import load, group, ms_of, mergeable, GAP_MS, LONG_GAP_MS   # noqa: E402


def why_not(a: dict, b: dict) -> str:
    """说明这两条为什么没被合并（复现 mergeable 的各道闸门）。"""
    if a["app"] != b["app"]:
        return "不同应用"
    if a["field"] != b["field"]:
        return "不同输入框"
    gap = ms_of(b["ts"]) - ms_of(a["ts"])
    ta, tb = a["text"], b["text"]
    continuing = bool(ta) and tb.startswith(ta)
    limit = LONG_GAP_MS if continuing else GAP_MS
    if gap > limit:
        return (f"间隔 {gap / 60000:.1f} 分钟 超过{'续写' if continuing else '常规'}上限 "
                f"{limit / 60000:.0f} 分钟")
    return "mergeable() 仍返回 False（需看更细的判据）"


def main() -> int:
    root = Path(sys.argv[1])
    sub = sys.argv[2] if len(sys.argv) > 2 else ""

    for day in sorted([d for d in root.iterdir() if d.is_dir()]):
        rows = [r for r in load(day) if (not sub or sub in r["app"])]
        if not rows:
            continue
        # 标记每条属于哪一段
        bursts = group(rows)
        seg_of = {}
        for idx, b in enumerate(bursts):
            for r in rows:
                if r["text"] == b["text"] and r["ts"] == b["ts_end"]:
                    seg_of[id(r)] = idx
        # 逐对检查
        n = 0
        for i in range(1, len(rows)):
            a, b = rows[i - 1], rows[i]
            if a["app"] != b["app"] or a["field"] != b["field"]:
                continue
            ta, tb = a["text"], b["text"]
            if not ta or not tb:
                continue
            # 关心"前缀相同"的两条
            if not (tb.startswith(ta) or ta.startswith(tb)):
                continue
            # 若 mergeable 为真，说明它们最终应在同一段；检查是否真的同段
            if not mergeable(a, b):
                continue
            # 找出它们各自所在的段（用最后版本匹配）
            def seg_index(row):
                for idx, bst in enumerate(bursts):
                    if bst["app"] == row["app"] and bst["ts"] <= row["ts"] <= bst["ts_end"]:
                        return idx
                return -1
            sa, sb = seg_index(a), seg_index(b)
            if sa != sb and sa >= 0 and sb >= 0:
                n += 1
                print(f"\n[{n}] {a['app'].split('.')[-1]}  前缀相同却分属两段（段 {sa} / {sb}）")
                print(f"    A {a['ts'][11:19]} ({len(ta)}字) <{ta[:80]}>")
                print(f"    B {b['ts'][11:19]} ({len(tb)}字) <{tb[:80]}>")
                print(f"    间隔 {(ms_of(b['ts']) - ms_of(a['ts'])) / 1000:.1f}s")
        print(f"\n{day.name}: 共 {n} 处异常（mergeable 为真但分属两段）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
