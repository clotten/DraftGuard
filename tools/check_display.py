#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_display.py —— 检查导出数据里哪些内容会破坏"整段展示"。

重点查：
  1) 文本里含换行符的记录（会把列表切成碎片，看起来"不是一整段"）
  2) 文本里含制表符/大量空格
  3) 超长文本（手机上一屏放不下）
  4) 同一段内文本反复来回（说明分段判据可能失效）
"""
import json
import sys
from pathlib import Path

LONG = 60


def main() -> int:
    root = Path(sys.argv[1])
    days = sorted([d for d in root.iterdir() if d.is_dir()], reverse=True)
    for day in days:
        print(f"\n===== {day.name} =====")
        with_nl: list[tuple[str, str, str]] = []
        with_ws: list[tuple[str, str, str]] = []
        total = 0
        for f in sorted(day.glob("*.jsonl")):
            app = f.stem
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                if not line.strip():
                    continue
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    continue
                total += 1
                t = o.get("text", "")
                if "\n" in t or "\r" in t:
                    with_nl.append((o.get("ts", "")[11:19], app, t))
                elif "\t" in t or "  " in t:
                    with_ws.append((o.get("ts", "")[11:19], app, t))

        print(f"总记录 {total} 条")
        print(f"含换行符：{len(with_nl)} 条   含制表符/连续空格：{len(with_ws)} 条")
        if with_nl:
            print("\n  含换行的记录（前 15 条）—— 这类会把列表切碎：")
            for ts, app, t in with_nl[:15]:
                print(f"    {ts}  {app:<28} <{t[:70].replace(chr(10), '⏎')}>")
        if with_ws:
            print("\n  含制表符/连续空格的记录（前 10 条）：")
            for ts, app, t in with_ws[:10]:
                print(f"    {ts}  {app:<28} <{t[:70]}>")

        # 超长
        long_rows: list[tuple[int, str, str, str]] = []
        for f in sorted(day.glob("*.jsonl")):
            app = f.stem
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                if not line.strip():
                    continue
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    continue
                t = o.get("text", "")
                if len(t) >= LONG:
                    long_rows.append((len(t), o.get("ts", "")[11:19], app, t))
        long_rows.sort(reverse=True)
        print(f"\n  超长记录（>= {LONG} 字）共 {len(long_rows)} 条，最长的 5 条：")
        for n, ts, app, t in long_rows[:5]:
            print(f"    {n} 字  {ts}  {app:<28} <{t[:60]}…>")
    return 0


if __name__ == "__main__":
    sys.exit(main())
