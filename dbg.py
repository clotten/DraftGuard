import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")
import merge_export as M

root = Path(r"E:\desktop\酒馆\tools\ziJi\logs-20261009f\extracted")
rows, labels = M.load(root)
rows = [r for r in rows if "larus" in r["app"]]
bursts = M.group(rows)
print("总段数:", len(bursts))
for b in bursts:
    if "我想放的久一点" in b["text"]:
        print(f"  段: {b['ts'][11:19]} ~ {b['ts_end'][11:19]}  v{b['versions']}  <{b['text']}>")
# 找 12:51:35 与 12:53:06 那两条，直接问 mergeable
a = [r for r in rows if r["ts"][11:19] == "12:51:35"]
b = [r for r in rows if r["ts"][11:19] == "12:53:06"]
if a and b:
    a, b = a[0], b[0]
    print("\nA:", repr(a["text"]))
    print("B:", repr(b["text"]))
    print("field A:", repr(a["field"]), " field B:", repr(b["field"]))
    print("mergeable:", M.mergeable(a, b))
    print("gap ms:", M.ms_of(b["ts"]) - M.ms_of(a["ts"]))
    print("startsWith:", b["text"].startswith(a["text"]))
