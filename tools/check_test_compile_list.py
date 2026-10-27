#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_test_compile_list.py —— 防止 CI 的测试编译清单漏文件。

为什么需要它：v3.2 拆出 SqlSchema 之后，LogStore 开始引用它，
但 CI 里那份**手写的 javac 文件清单**没跟着加 —— 于是 CI 一直红，
而本地用的是另一份（更宽的）清单，所以完全看不出来。

这正是复盘里写过的那类错误：**验证走了自己方便的路径。**

做法：
  1. 从 .github/workflows/test.yml 里取出测试步骤的 javac 文件清单
  2. 遍历 android/java/com/draftguard/*.java
  3. 每个文件必须二选一：
       · 出现在清单里，或
       · 出现在下面的 NEEDS_R（它们依赖 aapt2 生成的 R，离线编不了）
  4. 都不满足就报错退出

用法：python tools/check_test_compile_list.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "android" / "java" / "com" / "draftguard"
WORKFLOW = ROOT / ".github" / "workflows" / "test.yml"

# 不在测试清单里的文件，**每个都要写明理由** —— 这份清单本身就是文档。
# 由 CI 的 "Compile all sources" 步骤覆盖它们的编译。
EXCLUDED = {
    # 依赖 aapt2 生成的 R，或传递依赖到它，普通 JVM 里编不了
    "MainActivity.java": "依赖 R",
    "RecordAdapter.java": "引用 MainActivity（传递依赖 R）",
    "AppsPage.java": "引用 MainActivity（传递依赖 R）",
    "ToolsPage.java": "引用 MainActivity（传递依赖 R）",
    "RecordsPage.java": "引用 MainActivity（传递依赖 R）",
    "KeepAliveService.java": "直接引用 R.mipmap",
    "LogFileProvider.java": "Android 组件，无测试引用",
    "ProbeReceiver.java": "Android 组件，无测试引用",
    "TypelogService.java": "Android 服务，依赖 R 链",
    "Exporter.java": "引用 Context/MediaStore，无测试引用",
    "Diagnostics.java": "读 TypelogService 静态计数，属 R 链",
    "ServiceState.java": "读 AccessibilityManager，无测试引用",
    "NodeScanner.java": "读 AccessibilityNodeInfo，无测试引用",
    # 不依赖 R，但当前测试集不直接引用它们
    "ImeFilter.java": "InputMethodManager 查询，无测试引用",
}


def ci_list() -> set[str]:
    """从工作流里抠出 javac 后面那一串 .java 文件名"""
    text = WORKFLOW.read_text(encoding="utf-8")
    names: set[str] = set()
    for line in text.splitlines():
        m = re.search(r"([\w/]+\.java)", line)
        if m and ("android/java/com/draftguard/" in line or "test/" in line):
            names.add(Path(m.group(1)).name)
    return names


def main() -> int:
    listed = ci_list()
    all_src = {p.name for p in SRC.glob("*.java")}

    missing = []
    for name in sorted(all_src):
        if name in EXCLUDED:
            continue
        if name not in listed:
            missing.append(name)

    print(f"CI 清单里有 {len(listed)} 个文件；源码 {len(all_src)} 个")
    print(f"明确排除并写明理由的：{len(EXCLUDED)} 个")

    if missing:
        print()
        print("❌ 这些源文件既不在 CI 的编译清单里，也不在 NEEDS_R 里：")
        for m in missing:
            print(f"   · android/java/com/draftguard/{m}")
        print()
        print("把它们加进 .github/workflows/test.yml 的 javac 清单，")
        print("或者加进本脚本的 EXCLUDED 并写明理由（每个都要有理由）。")
        print("否则 CI 会编不过，而本地可能因为用了别的清单而看不到。")
        return 1

    print("✅ 测试编译清单完整（没有漏文件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
