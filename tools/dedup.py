import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")
from patch_java import find_method, delete_method

p = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
src = p.read_text(encoding="utf-8")

# 1) 删掉 step10 遗留的字段块（从"四态"注释到 step11 的"服务状态"注释之间）
a = src.find("    // ── 服务状态：四态")
b = src.find("    // ── 服务状态 ──")
if a >= 0 and b > a:
    src = src[:a] + src[b:]
    print("  ✓ 删除重复的字段声明块")

# 2) 每组成对的方法只保留第一份
for header in ("private boolean isServiceEnabled() {",
               "private String serviceStateText() {",
               "private void openAccessibilitySettings() {",
               "private void warnIfServiceStalled() {"):
    while src.count(header) > 1:
        # 删掉最后一份
        last = src.rfind(header)
        # 用括号配对找出该方法的结束
        depth = 0
        k = src.find("{", last)
        while k < len(src):
            if src[k] == '{':
                depth += 1
            elif src[k] == '}':
                depth -= 1
                if depth == 0:
                    break
            k += 1
        end = k + 1
        while end < len(src) and src[end] in "\r\n":
            end += 1
        src = src[:last] + src[end:]
        print(f"  ✓ 删除重复方法 {header.split()[-2]}")

# 3) lastStalledWarnAt 字段只保留一份
decl = "    private long lastStalledWarnAt;"
while src.count(decl) > 1:
    i = src.rfind(decl)
    j = src.find("\n", i) + 1
    src = src[:i] + src[j:]
    print("  ✓ 删除重复字段 lastStalledWarnAt")

p.write_text(src, encoding="utf-8")
print("已写入")
