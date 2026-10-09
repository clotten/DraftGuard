#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""list_versions.py —— 列出某应用某时段每个原始版本的首尾片段，用于找"重复开头"。

用法: list_versions.py <目录> <应用子串> <起> <止> [首字符数]
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from analyze import load   # noqa: E402


def main() -> int:
    root = Path(sys.argv[1])
    sub = sys.argv[2]
    t0, t1 = sys.argv[3], sys.argv[4]
    head = int(sys.argv[5]) if len(sys.argv) > 5 else 45

    for day in sorted([d for d in root.iterdir() if d.is_dir()]):
        rows = [r for r in load(day)
                if sub in r["app"] and t0 <= r["ts"][11:16] <= t1]
        if not rows:
            continue
        print(f"{day.name}  {sub}  {t0}-{t1}  共 {len(rows)} 条")
        for r in rows:
            t = r["text"]
            tail = ("  …尾:" + t[-22:]) if len(t) > head + 25 else ""
            print(f"  {r['ts'][11:19]} {len(t):>4}字  {t[:head]}{tail}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
