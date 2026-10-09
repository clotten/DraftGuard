#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fix_docs_forcestop.py —— 纠正文档里"装更新会掉无障碍"的错误结论。

实测（MIUI 12.5 / Android 11）：

  | 操作                        | accessibility_enabled | 列表条目 | 绑定 |
  | 正常状态                    | 1                     | 有       | 是   |
  | adb install -r              | 1                     | 有       | 是   |
  | adb shell am force-stop     | 0                     | 被删除   | 否   |

真凶是 force-stop（我自己调试时的习惯），不是安装。
"""
from pathlib import Path

ROOT = Path(r"E:\desktop\酒馆\tools\ziJi")

REMOTE_OLD = """**Why it happens:** installing an update or force-stopping the app kills the process, and MIUI does
**not** rebind an accessibility service afterwards — even though the list entry survives and
still reads "on". So every install leaves the app unable to record until the service is toggled
again. Expect this after **every** build you install."""

REMOTE_NEW = """**What actually causes it — measured, correcting an earlier claim in this file:**

| action | `accessibility_enabled` | entry in enabled list | service bound |
|---|---|---|---|
| baseline (healthy) | 1 | present | yes |
| `adb install -r` (same or new build) | 1 | present | **yes — stays bound** |
| `adb shell am force-stop <pkg>` | **0** | **removed** | no |

A plain **update does not disturb accessibility** — and the package-update event actually makes the
system re-bind the service. It is **`am force-stop` that wipes it**: Android's force-stop disables
the package's accessibility services and *deletes the entry from the enabled list*. Launching the
app afterwards does not restore it; the service must be re-enabled by hand.

**Practical rule: never `force-stop` this app.** `adb install -r` already kills and restarts the
process, so new code is in effect anyway — force-stop adds nothing and destroys the user's setting."""

TROUBLE_ADD = """

---

## 12. Never `force-stop` this app

Reported repeatedly by the user as: *"I turned accessibility on and it still says recording has
stopped."*

Measured behaviour of `adb shell am force-stop com.draftguard`:

- sets `accessibility_enabled` to `0`
- **deletes our service from `enabled_accessibility_services`**
- leaves `Bound services:{}`
- launching the app afterwards does **not** bring it back

Meanwhile `adb install -r` — the operation actually needed to load new code — leaves accessibility
untouched, and the package-update event causes the system to re-bind the service.

So the habit of "force-stop first, to be sure the new code runs" was **destroying the user's
accessibility setting on every build**, and the app got blamed for it. The install alone restarts
the process.

**Rule:** when testing on a device, never `force-stop` an app whose accessibility service matters.
If it has already happened, re-enable by hand, or:

```bash
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services null
sleep 2
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services com.draftguard/com.draftguard.TypelogService
# then run `adb install -r` once — the package-update event re-binds it
```
"""


def main() -> None:
    remote = ROOT / "docs" / "REMOTE-DEBUG.md"
    text = remote.read_text(encoding="utf-8")
    if REMOTE_OLD in text:
        text = text.replace(REMOTE_OLD, REMOTE_NEW)
        remote.write_text(text, encoding="utf-8")
        print("  ✓ REMOTE-DEBUG.md：用实测表格替换错误结论")
    elif "What actually causes it" not in text:
        text += "\n\n---\n\n" + REMOTE_NEW + "\n"
        remote.write_text(text, encoding="utf-8")
        print("  ✓ REMOTE-DEBUG.md：追加更正说明")
    else:
        print("  · REMOTE-DEBUG.md 已是更正后的内容")

    trouble = ROOT / "docs" / "TROUBLESHOOTING.md"
    text = trouble.read_text(encoding="utf-8")
    if "never `force-stop` this app" not in text:
        trouble.write_text(text.rstrip() + TROUBLE_ADD, encoding="utf-8")
        print("  ✓ TROUBLESHOOTING.md：新增第 12 节")
    else:
        print("  · TROUBLESHOOTING.md 已包含该节")


if __name__ == "__main__":
    main()
