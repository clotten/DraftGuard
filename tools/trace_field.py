#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""trace_field.py —— 追踪某个应用在某个时间窗内的原始记录序列。

用途：分段结果不对时，看"系统当时到底给了什么文本"，判断该怎么合并。
"""
import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 4:
        print("用法: trace_field.py <解压目录> <应用子串> [开始时刻 HH:MM] [结束时刻 HH:MM]")
        return 1
    root = Path(sys.argv[1])
    app_sub = sys.argv[2]
    t0 = sys.argv[3] if len(sys.argv) > 3 else "00:00"
    t1 = sys.argv[4] if len(sys.argv) > 4 else "23:59"

    for day in sorted([d for d in root.iterdir() if d.is_dir()]):
        for f in sorted(day.glob("*.jsonl")):
            if app_sub not in f.stem:
                continue
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line:
                    continue
                o = json.loads(line)
                ts = o.get("ts", "")
                hhmm = ts[11:16]
                if not (t0 <= hhmm <= t1):
                    continue
                text = o.get("text", "")
                print(f"  {ts[11:23]}  len={len(text):<4} field={o.get('field','')[-28:]:<28} "
                      f"<{text}>")
    return 0


if __name__ == "__main__":
    sys.exit(main())
