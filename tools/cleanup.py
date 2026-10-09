import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")
from patch_java import delete_method

p = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
src = p.read_text(encoding="utf-8")
# 这些方法在重构后已无人调用，删掉免得后人困惑
for m in ("private static String context(String text, String q) {",
          "private void setCard(TextView v, String title, String body) {",
          "private TextView card(String title, String body) {"):
    if m in src:
        src = delete_method(src, m)
        print(f"  ✓ 删除无用方法 {m.split('(')[0].split()[-1]}")
# page2 的应用列表行还用到 card? 检查
print("剩余 card 引用:", src.count("card("))
p.write_text(src, encoding="utf-8")
