#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""scan.py —— 扫描导出日志里的可疑记录（噪音候选）。

用途：手机上只能一条条翻，在电脑上能一眼扫出"哪些不是用户输入"。
判定思路：
  · 极短记录（<=3 字）
  · 出现在"非输入场景"的应用（系统设置、桌面等）
  · 与同应用相邻记录毫无关系且长度异常
"""
import json
import sys
from pathlib import Path

SHORT_LEN = 3


def main() -> int:
    root = Path(sys.argv[1])
    days = sorted([d for d in root.iterdir() if d.is_dir()], reverse=True)
    for day in days:
        print(f"\n===== {day.name} =====")
        total = 0
        short: list[tuple[str, str, str]] = []
        for f in sorted(day.glob("*.jsonl")):
            app = f.stem
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    continue
                total += 1
                t = o.get("text", "")
                if 0 < len(t) <= SHORT_LEN:
                    short.append((o.get("ts", "")[11:19], app, t))

        print(f"总记录 {total} 条，其中 <= {SHORT_LEN} 字的有 {len(short)} 条：")
        by_app: dict[str, int] = {}
        for _, app, _ in short:
            by_app[app] = by_app.get(app, 0) + 1
        for app, n in sorted(by_app.items(), key=lambda kv: -kv[1]):
            print(f"    {app:<32} {n} 条")

        print("\n  明细（前 50 条）：")
        for ts, app, t in short[:50]:
            print(f"    {ts}  {app:<30} <{t}>")
    return 0


if __name__ == "__main__":
    sys.exit(main())
