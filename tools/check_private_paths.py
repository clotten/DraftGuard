#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_private_paths.py —— 防止把使用者的本机环境信息提交进公开仓库。

为什么需要它：这个仓库是公开的，而开发过程中很容易顺手把
`C:\\Users\\<名字>\\...`、局域网 IP、机器名写进文档或脚本里。
一旦提交，浏览仓库的人就能看到使用者的磁盘布局与身份线索。

本脚本用**通用模式**识别，自身不含任何个人字符串 ——
否则护栏自己就成了泄露源。

额外规则可以写在 tools/.private-tokens（该文件已 gitignore），每行一个。
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOKENS_FILE = ROOT / "tools" / ".private-tokens"

# 通用模式：不绑定任何具体人名/机器名
PATTERNS = [
    (r"C:\\Users\\[^\\\s\"']+", "Windows 用户目录（含用户名）"),
    (r"C:/Users/[^/\s\"']+", "Windows 用户目录（含用户名）"),
    (r"/home/[^/\s\"']+", "Linux 用户目录（含用户名）"),
    (r"/Users/[^/\s\"']+", "macOS 用户目录（含用户名）"),
    (r"\b192\.168\.\d{1,3}\.\d{1,3}\b", "局域网 IP"),
    (r"\b10\.\d{1,3}\.\d{1,3}\.\d{1,3}\b", "私有网段 IP"),
    (r"\b172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}\b", "私有网段 IP"),
    (r"\bLAPTOP-[A-Z0-9]{4,}\b", "Windows 机器名"),
    (r"\bDESKTOP-[A-Z0-9]{4,}\b", "Windows 机器名"),
]

# 这些是"示例/占位"写法，不算泄露
ALLOW = [
    "<repo>", "<Android SDK>", "<JDK>", "<Harness>", "<手机IP>", "<本机IP>",
    "<Python>", "<热点网段>", "<本机热点网段>", "<电脑热点IP>", "<项目路径>",
    "$env:LOCALAPPDATA", "example.com", "127.0.0.1", "0.0.0.0",
]

SKIP_EXT = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".apk", ".jar", ".keystore", ".jks"}


def tracked_files() -> list[Path]:
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        return []
    return [ROOT / line.strip() for line in out.stdout.splitlines() if line.strip()]


def extra_tokens() -> list[str]:
    if not TOKENS_FILE.exists():
        return []
    return [t.strip() for t in TOKENS_FILE.read_text(encoding="utf-8").splitlines()
            if t.strip() and not t.startswith("#")]


def main() -> int:
    patterns = [(re.compile(p), d) for p, d in PATTERNS]
    for tok in extra_tokens():
        patterns.append((re.compile(re.escape(tok)), "本机私有标识（tools/.private-tokens）"))

    findings = []
    for f in tracked_files():
        if f.suffix.lower() in SKIP_EXT or not f.is_file():
            continue
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if any(a in line for a in ALLOW):
                continue
            for rx, desc in patterns:
                m = rx.search(line)
                if m:
                    findings.append((f.relative_to(ROOT), lineno, desc, m.group(0)))

    if not findings:
        print(f"✅ 未发现本机环境信息（检查了 {len(tracked_files())} 个已跟踪文件）")
        return 0

    print("❌ 以下位置含有本机环境信息，公开仓库不应包含：\n")
    for path, lineno, desc, sample in findings:
        print(f"  {path}:{lineno}  [{desc}]  {sample}")
    print("\n处理办法：")
    print("  · 文档里改用占位符，例如 <repo> / <Android SDK> / <手机IP>")
    print("  · 脚本里改为自动探测（环境变量 → 常见安装位置），不要写死")
    print("  · 确实需要本机专用字符串时，写进 tools/.private-tokens（该文件不入库）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
