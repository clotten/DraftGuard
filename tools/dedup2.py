import re
from pathlib import Path

p = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
src = p.read_text(encoding="utf-8")

# 只保留每个"字段声明"的第一次出现（同一行内容完全相同才去重）
decls = [
    "    private static final int SVC_OFF = 0;",
    "    private static final int SVC_CONNECTING = 1;",
    "    private static final int SVC_STALLED = 2;",
    "    private static final int SVC_OK = 3;",
    "    private static final int SVC_MASTER_OFF = 4;",
    "    private long launchedAt;",
    "    private static final long CONNECT_GRACE_MS = 6000;",
    "    private volatile String lastStateProbe = \"\";",
    "    private long lastStalledWarnAt;",
]
removed = 0
for d in decls:
    while src.count(d) > 1:
        i = src.rfind(d)
        j = src.find("\n", i) + 1
        src = src[:i] + src[j:]
        removed += 1
print(f"  ✓ 删除重复声明 {removed} 处")

# 顺带把注释里重复的分节标题合并掉
for marker in ("    // ── 服务状态：四态",):
    while src.count(marker) > 0:
        i = src.find(marker)
        j = src.find("\n", i) + 1
        src = src[:i] + src[j:]
        removed += 1
print(f"  ✓ 删除重复注释标题")

p.write_text(src, encoding="utf-8")
print("已写入")
