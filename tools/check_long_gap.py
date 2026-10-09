#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_long_gap.py —— 找出"跨很长时间仍是接着写"的记录对。

用途：验证"草稿续写允许长间隔"这条规则是否会在真实数据里生效，
以及会不会把两条不同的消息误并。
"""
import json
import sys
from pathlib import Path
from datetime import datetime

GAP_MS = 5 * 60 * 1000
LONG_GAP_MS = 6 * 60 * 60 * 1000


def ms_of(iso: str) -> int:
    try:
        return int(datetime.strptime(iso[:23], "%Y-%m-%dT%H:%M:%S.%f").timestamp() * 1000)
    except Exception:
        return 0


def load_all(root: Path):
    rows = []
    for day in sorted([d for d in root.iterdir() if d.is_dir()]):
        for f in sorted(day.glob("*.jsonl")):
            app = f.stem
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                if not line.strip():
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


def main() -> int:
    root = Path(sys.argv[1])
    rows = load_all(root)

    found = 0
    print('=== 跨 >5 分钟、且后一条以前一条开头的记录对（草稿续写候选）===')
    for i in range(1, len(rows)):
        a, b = rows[i - 1], rows[i]
        if a["app"] != b["app"] or a["field"] != b["field"]:
            continue
        ta, tb = a["text"], b["text"]
        if not ta or not tb or not tb.startswith(ta):
            continue
        gap = ms_of(b["ts"]) - ms_of(a["ts"])
        if gap <= GAP_MS:
            continue
        mark = "续写(≤6h, 会合并)" if gap <= LONG_GAP_MS else "超6h(仍分段)"
        found += 1
        print(f"  [{mark}] 间隔 {gap / 60000:.1f} 分钟  {a['app']}")
        print(f"      旧: {a['ts'][11:19]} <{ta[:60]}>")
        print(f"      新: {b['ts'][11:19]} <{tb[:60]}>")

    if found == 0:
        print('  （这份日志里没有这种模式 —— 说明还没遇到「切出去很久再接着写」的情况，')
        print('    或遇到了但两次内容不是前缀关系）')
    return 0


if __name__ == "__main__":
    sys.exit(main())
