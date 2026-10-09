#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""find_similar.py —— 找出"内容高度相似却没合并"的相邻段。

这是判断"该合未合"最直接的证据：
如果两段在同一个输入框、时间接近、文本有大量公共内容，
却没有合并成一段，那大概率是漏合了（工具的问题），
也可能是用户真的发了两条相似消息（那就没错）。

输出把两段并排显示，便于人眼判断。
"""
import json
import sys
from pathlib import Path
from datetime import datetime

MAX_GAP_MS = 60 * 60 * 1000      # 只看 1 小时内的相邻段
SIM_THRESHOLD = 0.5              # 相似度阈值


def ms_of(iso: str) -> int:
    try:
        return int(datetime.strptime(iso[:23], "%Y-%m-%dT%H:%M:%S.%f").timestamp() * 1000)
    except Exception:
        return 0


def similarity(a: str, b: str) -> float:
    """用最长公共子序列长度 / 较长串长度 作为相似度（够用且无需依赖库）。"""
    if not a or not b:
        return 0.0
    # 简化为字符集合的重合度 + 公共前缀，避免 O(n*m) 在长文本上过慢
    sa, sb = set(a), set(b)
    inter = len(sa & sb)
    union = len(sa | sb)
    jac = inter / union if union else 0.0

    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    prefix = i / max(len(a), len(b))
    return max(jac, prefix)


def main() -> int:
    root = Path(sys.argv[1])
    sub = sys.argv[2] if len(sys.argv) > 2 else ""

    # 直接读原始记录（不经过合并），相邻两条比较
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
        if a["app"] != b["app"] or a["field"] != b["field"]:
            continue
        ta, tb = a["text"], b["text"]
        if len(ta) < 3 or len(tb) < 3:
            continue
        if tb.startswith(ta) or ta.startswith(tb):
            continue      # 前缀关系：合并逻辑会处理，不用看
        gap = b["_ms"] - a["_ms"]
        if gap > MAX_GAP_MS:
            continue
        sim = similarity(ta, tb)
        if sim < SIM_THRESHOLD:
            continue
        n += 1
        print(f"\n[{n}] {a['app'].split('.')[-1]}  间隔 {gap / 1000:.1f}s  相似度 {sim:.2f}")
        print(f"    A {a['ts'][11:19]} ({len(ta)}字) <{ta[:90]}>")
        print(f"    B {b['ts'][11:19]} ({len(tb)}字) <{tb[:90]}>")
    print(f"\n共 {n} 处（相似度高但非前缀关系 —— 这些地方合并逻辑不会合并，需人眼判断是否合理）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
