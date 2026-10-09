#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""why_not_merged.py —— 打印相邻两条记录在分段判据上的每一步结果。

分段结果不符合预期时，用这个看清是"哪一道闸门"拦住了。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from merge_export import (load, ms_of, mergeable, common_prefix, is_full_rewrite,   # noqa: E402
                          is_localized_edit, share_content_char,
                          GAP_MS, LONG_GAP_MS, RAPID_EDIT_MS, RAPID_EDIT_MAX)


def main() -> int:
    root = Path(sys.argv[1])
    sub = sys.argv[2]
    t0, t1 = sys.argv[3], sys.argv[4]
    rows, _ = load(root)
    rows = [r for r in rows if sub in r["app"] and t0 <= r["ts"][11:16] <= t1]

    print(f"共 {len(rows)} 条")
    for i in range(len(rows)):
        r = rows[i]
        print(f"\n[{i}] {r['ts'][11:23]} field='{r['field'][-24:]}' len={len(r['text'])}")
        print(f"     <{r['text'][:70]}>")
        if i == 0:
            continue
        a, b = rows[i - 1], r
        gap = ms_of(b["ts"]) - ms_of(a["ts"])
        ta, tb = a["text"], b["text"]
        same_field = (not a["field"] and not b["field"]) or a["field"] == b["field"]
        continuing = bool(ta) and tb.startswith(ta)
        legacy = not a["field"] and not b["field"]
        limit = LONG_GAP_MS if (continuing or legacy) else GAP_MS
        print(f"    与上一条：gap={gap/1000:.1f}s  同框={same_field}  前缀={continuing} "
              f"旧记录={legacy}  上限={limit/60000:.0f}min")
        print(f"      commonPrefix={common_prefix(ta, tb)}  fullRewrite={is_full_rewrite(ta, tb)} "
              f"localized={is_localized_edit(ta, tb)}  共用实义字={share_content_char(ta, tb)}")
        print(f"      → mergeable = {mergeable(a, b)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
