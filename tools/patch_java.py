#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""patch_java.py —— 按方法名精确替换 Java 方法体。

为什么需要它：直接用字符串替换反复出现"锚点没匹配上、静默没改"的问题
（本项目里已经栽过好几次）。这里用花括号配对定位整个方法，
并且**每处替换都断言发生了**，没改到就直接报错退出。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path


def find_method(text: str, header: str) -> tuple[int, int]:
    """返回 (方法起始下标, 方法结束下标+1)，按花括号配对。"""
    i = text.find(header)
    if i < 0:
        raise SystemExit(f"找不到方法头: {header}")
    j = text.find("{", i)
    if j < 0:
        raise SystemExit(f"方法头后没有花括号: {header}")
    depth = 0
    k = j
    while k < len(text):
        c = text[k]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i, k + 1
        k += 1
    raise SystemExit(f"花括号不配对: {header}")


def replace_method(text: str, header: str, new_code: str) -> str:
    a, b = find_method(text, header)
    out = text[:a] + new_code + text[b:]
    if out == text:
        raise SystemExit(f"替换后内容没有变化: {header}")
    return out


def delete_method(text: str, header: str) -> str:
    a, b = find_method(text, header)
    # 连带删掉后面的空行
    while b < len(text) and text[b] in "\r\n":
        b += 1
    return text[:a] + text[b:]


def patch(path: Path, ops) -> None:
    src = path.read_text(encoding="utf-8")
    for kind, header, new_code in ops:
        before = src
        if kind == "replace":
            src = replace_method(src, header, new_code)
        elif kind == "delete":
            src = delete_method(src, header)
        else:
            raise SystemExit(f"未知操作: {kind}")
        if src == before:
            raise SystemExit(f"操作未生效: {kind} {header}")
        print(f"  ✓ {kind}: {header.strip()[:64]}")
    path.write_text(src, encoding="utf-8")
    print(f"已写入 {path}")


if __name__ == "__main__":
    print("这是被其它脚本导入的工具，不直接运行")
