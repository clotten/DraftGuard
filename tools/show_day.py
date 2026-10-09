#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""show_day.py —— 按时间顺序打印某应用某时段的分段结果（含首尾时间与版本数）。

与 analyze.py 的区别：本脚本**按时间正序**输出，便于顺着读一段对话，
看清"哪句话被拆成了两段"。
"""
import json
import sys
from pathlib import Path
from datetime import datetime

sys.path.insert(0, str(Path(__file__).parent))
from analyze import load, group   # noqa: E402


def main() -> int:
    root = Path(sys.argv[1])
    sub = sys.argv[2]
    t0 = sys.argv[3] if len(sys.argv) > 3 else "00:00"
    t1 = sys.argv[4] if len(sys.argv) > 4 else "23:59"

    days = sorted([d for d in root.iterdir() if d.is_dir()])
    for day in days:
        rows = [r for r in load(day) if sub in r["app"]]
        rows = [r for r in rows if t0 <= r["ts"][11:16] <= t1]
        if not rows:
            continue
        bursts = group(rows)
        print(f"===== {day.name}  {sub}  {t0}-{t1}：{len(rows)} 版本 → {len(bursts)} 段 =====")
        for b in bursts:
            span = b["ts"][11:16]
            if b["ts_end"][11:16] != span:
                span += "–" + b["ts_end"][11:16]
            head = f"  {span}  v{b['versions']:<3} {len(b['text']):>4}字"
            print(head)
            print(f"      {b['text']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
