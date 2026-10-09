#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""find_splits.py —— 找出"可能被错误拆开"的地方。

判据：同一输入框里，前一条的文本**不是**后一条的前缀（说明发生了替换/重打），
且两条时间接近 —— 这种地方究竟是"改字"还是"新消息"，是最容易判错的。

输出偏向"人眼复核"：把上下文三条一起打出来。
"""
import json
import sys
from pathlib import Path
from datetime import datetime

SPLIT_WINDOW_MS = 30 * 60 * 1000     # 只看半小时内相邻的
MIN_LEN = 2


def ms_of(iso: str) -> int:
    try:
        return int(datetime.strptime(iso[:23], "%Y-%m-%dT%H:%M:%S.%f").timestamp() * 1000)
    except Exception:
        return 0


def main() -> int:
    root = Path(sys.argv[1])
    sub = sys.argv[2] if len(sys.argv) > 2 else ""
    rows = []
    for day in sorted([d for d in root.iterdir() if d.is_dir()]):
        for f in sorted(day.glob("*.jsonl")):
            if sub and sub not in f.stem:
                continue
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
                o["_ms"] = ms_of(o.get("ts", ""))
                rows.append(o)
    rows.sort(key=lambda r: r["_ms"])

    n = 0
    for i in range(1, len(rows)):
        a, b = rows[i - 1], rows[i]
        if a["app"] != b["app"]:
            continue
        ta, tb = a["text"], b["text"]
        if len(ta) < MIN_LEN or len(tb) < MIN_LEN:
            continue
        gap = b["_ms"] - a["_ms"]
        if gap > SPLIT_WINDOW_MS:
            continue
        # 后一条不是以前一条开头 ⇒ 发生了替换
        if tb.startswith(ta):
            continue
        n += 1
        # 上下文两行
        prev = rows[i - 2] if i >= 2 else None
        print(f"\n[{n}] {a['app'].split('.')[-1]}  间隔 {gap / 1000:.1f}s")
        if prev and prev["app"] == a["app"]:
            print(f"    前2: {prev['ts'][11:19]} <{prev['text'][:70]}>")
        print(f"    前1: {a['ts'][11:19]} <{ta[:70]}>")
        print(f"    后1: {b['ts'][11:19]} <{tb[:70]}>")
        nxt = rows[i + 1] if i + 1 < len(rows) else None
        if nxt and nxt["app"] == b["app"]:
            print(f"    后2: {nxt['ts'][11:19]} <{nxt['text'][:70]}>")
    print(f"\n共 {n} 处替换点（这些地方的分段最可能判错）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
