#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_diag_fields.py —— 防止"重构时丢掉诊断字段"。

为什么需要它：记录页重构时我把诊断内容搬进弹窗，漏掉了六段
（应用事件次数、微信专项、扫描情况、磁盘文件、最近 25 条原始事件、焦点过滤告警），
其中"最近 25 条原始事件"是我自己排查问题的主要手段 —— 删掉等于自断后路。
用户明确指出：不要删功能，否则以后不好诊断。

做法：扫描 TypelogService / LogStore 里所有"可诊断字段"（计数器与状态字符串），
再检查它们是否都出现在 MainActivity 的诊断弹窗里。缺失就报错退出。

用法：python check_diag_fields.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard")
MAIN = ROOT / "MainActivity.java"

# 这些是"给用户看的开关/结果"，不是诊断信号，不要求出现在诊断面板
EXCLUDE = {
    "running",          # 用 isServiceEnabled() 判断，语义更准
    "EXTRA_EVENT",      # 广播 action，不是计数
}

FIELD_RE = re.compile(
    r"^\s+(?:public\s+)?static\s+volatile\s+(?:long|String|boolean)\s+([A-Za-z_][A-Za-z0-9_]*)")
VOL_RE = re.compile(r"^\s+volatile\s+(?:long|String|boolean)\s+([A-Za-z_][A-Za-z0-9_]*)")


def collect(path: Path) -> list[str]:
    names: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        for rx in (FIELD_RE, VOL_RE):
            m = rx.match(line)
            if m:
                n = m.group(1)
                if n not in EXCLUDE and n not in names:
                    names.append(n)
    return names


def diag_region(src: str) -> str:
    """取出 showDiag() 方法体（诊断面板的内容就在这里）。"""
    i = src.find("private void showDiag() {")
    if i < 0:
        raise SystemExit("找不到 showDiag()")
    depth = 0
    j = src.find("{", i)
    k = j
    while k < len(src):
        if src[k] == '{':
            depth += 1
        elif src[k] == '}':
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
        k += 1
    raise SystemExit("showDiag 花括号不配对")


def main() -> int:
    svc = collect(ROOT / "TypelogService.java")
    store = collect(ROOT / "LogStore.java")
    region = diag_region(MAIN.read_text(encoding="utf-8"))

    def shown(name: str) -> bool:
        """字段本身出现，或其静态诊断镜像 diagXxx 出现，都算"能看到"。

        为什么允许镜像：真正在写入的是采集服务持有的那个 LogStore 实例，
        界面另建实例读不到它的实例字段（读出来全是 0）。
        所以约定：写入时同步一份 `diag<字段名>` 的静态快照，界面读镜像。
        """
        if name in region:
            return True
        mirror = "diag" + name[0].upper() + name[1:]
        return mirror in region

    missing: list[tuple[str, str]] = []
    for src_name, names in (("TypelogService", svc), ("LogStore", store)):
        for n in names:
            if not shown(n):
                missing.append((src_name, n))

    total = len(svc) + len(store)
    print(f"可诊断字段共 {total} 个（TypelogService {len(svc)} / LogStore {len(store)}）")
    if missing:
        print(f"\n❌ 有 {len(missing)} 个字段没有出现在「诊断」面板里：")
        for src_name, n in missing:
            print(f"   · {src_name}.{n}（或镜像 {src_name}.diag{n[0].upper()}{n[1:]}）")
        print("\n诊断面板是排查问题的唯一入口，缺字段就等于瞎了一半。")
        print("请把它们加进 MainActivity.showDiag()，或明确写进 EXCLUDE 并说明理由。")
        return 1
    print("✅ 全部字段都能在「诊断」面板里看到")
    return 0


if __name__ == "__main__":
    sys.exit(main())
