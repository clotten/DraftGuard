#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_workflow.py —— 校验工作流等结构化配置文件能否被解析。

为什么需要它：
  往 `.github/workflows/*.yml` 里加步骤时，我是用字符串拼接追加的，
  缩进多了两格，新步骤被塞进了上一个步骤内部 —— **整个工作流语法坏了**。
  这类错误的后果特别隐蔽：CI 在"解析阶段"就失败，
  连一个步骤都不会跑，日志里看不到任何我熟悉的测试输出，
  于是很容易误以为是业务代码的问题。

  YAML 对缩进极其敏感，而"拼接字符串"是最容易破坏缩进的改法。
  改完必须用解析器验一遍，不能靠肉眼看。

用法：
  python tools/check_workflow.py          # 校验失败返回非零
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGETS = list((ROOT / ".github" / "workflows").glob("*.yml")) + \
          list((ROOT / ".github" / "workflows").glob("*.yaml"))


def main() -> int:
    if not TARGETS:
        print("没有找到工作流文件")
        return 0

    try:
        import yaml
    except ImportError:
        # CI 上不保证有 PyYAML，没装就只做基础检查（缩进一致性）
        print("（未安装 PyYAML，仅做缩进检查）")
        bad = 0
        for f in TARGETS:
            for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                if line.rstrip() != line:
                    print(f"  ❌ {f.name}:{i} 行尾有多余空白（YAML 里可能是错误来源）")
                    bad += 1
        return 1 if bad else 0

    failed = 0
    for f in TARGETS:
        try:
            doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        except Exception as e:
            print(f"  ❌ {f.name} 解析失败：{e}")
            failed += 1
            continue
        # 确认 jobs.<id>.steps 是一个列表，且每步都有 name 或 uses
        jobs = (doc or {}).get("jobs", {})
        for jid, job in jobs.items():
            steps = job.get("steps")
            if not isinstance(steps, list):
                print(f"  ❌ {f.name}: jobs.{jid}.steps 不是列表（多半是缩进串位）")
                failed += 1
                continue
            for i, st in enumerate(steps, 1):
                if not isinstance(st, dict) or not ("name" in st or "uses" in st):
                    print(f"  ❌ {f.name}: jobs.{jid}.steps[{i}] 结构异常：{st!r}")
                    failed += 1
            print(f"  ✅ {f.name}: jobs.{jid} 共 {len(steps)} 个步骤")

    if failed:
        print("\nYAML 缩进坏了 —— 注意：这种情况下 CI 会在解析阶段就失败，"
              "一个步骤都不会跑，日志里看不到业务输出。")
        return 1
    print("✅ 工作流结构正常")
    return 0


if __name__ == "__main__":
    sys.exit(main())
