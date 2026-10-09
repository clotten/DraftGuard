#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""show_versions.py —— 打印某应用某时段每个原始版本的"开头部分"。

用于排查"两段开头一样却没合并"：把每条的公共前缀长度算出来，
直接标出每组之间是从第几个字开始分叉的。
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from analyze import load, group, common_prefix   # noqa: E402


def main() -> int:
    root = Path(sys.argv[1])
    sub = sys.argv[2]
    t0 = sys.argv[3] if len(sys.argv) > 3 else "00:00"
    t1 = sys.argv[4] if len(sys.argv) > 4 else "23:59"
    head = int(sys.argv[5]) if len(sys.argv) > 5 else 40

    for day in sorted([d for d in root.iterdir() if d.is_dir()]):
        rows = [r for r in load(day) if sub in r["app"]]
        rows = [r for r in rows if t0 <= r["ts"][11:16] <= t1]
        if not rows:
            continue
        print(f"===== {day.name} {sub} {t0}-{t1}：{len(rows)} 个原始版本 =====")
        prev = None
        for r in rows:
            t = r["text"]
            mark = ""
            if prev is not None:
                cp = common_prefix(prev, t)
                same_start = t.startswith(prev) or prev.startswith(t)
                mark = f"  (与上条共同开头 {cp} 字{', 前缀关系' if same_start else ''})"
            print(f"  {r['ts'][11:19]} len={len(t):<4}{mark}")
            print(f"      {t[:head]}")
            prev = t

        print(f"\n  该时段合并为 {len(group(rows))} 段")
    return 0


if __name__ == "__main__":
    sys.exit(main())
