import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")
import merge_export as M

root = Path(r"E:\desktop\酒馆\tools\ziJi\logs-20261009f\extracted")
rows, labels = M.load(root)          # 全部应用、原始顺序
print("总记录:", len(rows))

# 找 12:51:35 那条在 rows 里的位置，看它后面紧跟的是谁
for i, r in enumerate(rows):
    if r["ts"][11:19] == "12:51:35" and "larus" in r["app"]:
        print(f"\n找到 12:51:35 在索引 {i}")
        for j in range(max(0,i-1), min(len(rows), i+4)):
            x = rows[j]
            print(f"  [{j}] {x['ts'][11:23]} {x['app'].split('.')[-1]:<12} "
                  f"field='{x['field'][-22:]}' len={len(x['text'])} <{x['text'][:36]}>")
        a, b = rows[i], rows[i+1]
        print(f"\n相邻对 mergeable = {M.mergeable(a,b)}")
        break
