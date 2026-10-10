# 远程调试（ADB 无线调试）

让 AI 助手/开发机直接连上手机排查问题，替代"截图 — 描述 — 猜测"的低效循环。

连上之后可以：**看屏幕截图、读 logcat、点屏幕、拉文件、执行诊断探针**。

---

## 一、手机端：开启无线调试

### Android 11 及以上（推荐，不需要数据线）

1. `设置 → 关于手机 → 连点「版本号」7 次` 打开开发者选项
2. `设置 → 系统 → 开发者选项 → 无线调试`：打开
3. 点 **「使用配对码配对设备」**，会显示：
   - 一个 **6 位配对码**
   - 一个 **配对用 IP:端口**（形如 `<本机IP>:37129`）
4. **保持这个弹窗不要关**（关掉会失效，要重新点）
5. 回到上一级「无线调试」页面，记下 **「IP 地址和端口」**（形如 `<本机IP>:39145`）

> 注意：**配对端口**和**连接端口是两个不同的端口**，每次开关无线调试都会变。

### Android 10 及以下（需要一次数据线）

```bash
# 数据线连上，确认设备已授权
adb devices
# 打开 TCP 模式（之后可以拔线）
adb tcpip 5555
adb connect <手机IP>:5555
```

### 前提

- 手机与电脑在**同一个局域网**（本项目实测：都在 `10.16.x.x/16`，通）
- 有些路由器开了「AP 隔离」会挡住互访；这种情况下改用数据线 + `adb reverse`

---

## 二、开发机端：配对与连接

```bash
# 1. 配对（用第 3 步弹窗里的配对端口，只需一次）
adb pair <本机IP>:37129
#    回车后输入 6 位配对码
#    成功会显示 Successfully paired to ...

# 2. 连接（用「IP 地址和端口」那个端口）
adb connect <本机IP>:39145

# 3. 确认
adb devices -l
#    应看到 <IP>:<端口>  device
```

### 连不上怎么办

| 现象 | 处理 |
|---|---|
| `failed to connect` / 超时 | 确认在同一 WiFi；关掉手机的 VPN/代理；路由器若开了 AP 隔离就改用数据线 |
| 端口连不上 | 配对端口与连接端口搞混了；回无线调试页面重新确认 |
| 连上但 `unauthorized` | 手机上会弹「允许 USB 调试吗」，勾选「始终允许」后确定 |
| 频繁掉线 | 手机省电策略会掐掉 adb；`开发者选项 → 保持唤醒` 打开 |

---

## 三、能做什么（实测命令）

```bash
# 看屏幕（截图存到电脑，直接看图就能判断界面状态）
adb exec-out screencap -p > screen.png

# 读日志
adb logcat -d -s DraftGuardProbe     # 只看诊断探针的输出
adb logcat -d | findstr draftguard   # 找应用的异常

# 点屏幕、滑动、返回（坐标从截图里量）
adb shell input tap 540 1200
adb shell input swipe 540 1600 540 900 300
adb shell input keyevent 4           # 返回键

# 启动应用 / 查看运行状态
adb shell am start -n com.draftguard/.MainActivity
adb shell dumpsys activity services com.draftguard
adb shell dumpsys accessibility | findstr draftguard

# 安装新包
adb install -r DraftGuard-2.12.2.apk
```

---

## 四、诊断探针（关键：读应用私有数据）

**为什么需要它**：Android 11+ 禁止 `adb pull /data/data/com.draftguard/...`，
而排查问题要看的就是那个目录里的日志文件。探针让应用"自己"把状态打到 logcat。

**前提**：应用内 `设置 → 诊断日志` 必须**打开**（默认关闭）。没打开时探针会打印一行
"探针已忽略"，不做任何事。

```bash
# 完整状态：计数器、事件来源统计、磁盘文件清单、最近 15 条记录、当前设置
adb shell am broadcast -n com.draftguard/.ProbeReceiver -a com.draftguard.PROBE --es cmd dump
adb logcat -d -s DraftGuardProbe

# 只重置计数器（清空后重新观察）
adb shell am broadcast -n com.draftguard/.ProbeReceiver -a com.draftguard.PROBE --es cmd reset

# 清空全部记录（等价于界面上的「清除全部记录」）
adb shell am broadcast -n com.draftguard/.ProbeReceiver -a com.draftguard.PROBE --es cmd clear
```

`dump` 输出什么：

```
===== DraftGuard probe dump 2026-10-08 22:41:03.512 =====
service.running=true  written=42  errors=0
events: all=118 text=37 captured=37 sourceNull=6 ...
skipped: noFocus=0 delete=11 noise=3 ime=52 password=0 ...
-- packages that sent events --
  (all) com.tencent.mm = 21
  (all) com.iflytek.inputmethod.miui = 61
-- files on disk (2026-10-08) --
com.tencent.mm.jsonl | 行数 12 | 1840 字节 | 最后一条：你做饭吗
-- last 15 records --
2026-10-08T22:40:12.331 [22:40] com.tencent.mm chars=5 text=<你做饭吗>
-- prefs --
 focusOnly=true ignoreDeletions=true skipIme=true polling=true minChars=2 ...
```

这张输出基本能替代"截图 + 口述现象"。

---

## 五、安全边界（务必了解）

**ADB 等于对手机的完全控制权。** 连接期间，持有该连接的一方可以：

- 读取截图与日志（包含屏幕上显示的一切）
- 安装/卸载应用、清除应用数据、修改系统设置
- 模拟点按与滑动

因此：

- **只在你需要我排查时连接**，事情做完就关掉「无线调试」（或 `adb disconnect`），权限立刻失效
- 配对码只在弹窗打开期间有效，关掉弹窗即作废
- 我读不到的东西：微信/QQ 等应用内部的消息数据库、密码框内容（系统屏蔽）、
  以及没有探针时的应用私有目录
- **我没有能力在你不操作的情况下"自己"连上你的手机** —— 必须由你在手机上点开无线调试并给出配对信息

### 我不能做的：输入中文

`adb shell input text` **只能输入 ASCII**，输不了中文。所以"在微信里打一段中文来测试"
这一步必须你自己在手机上敲（或复制粘贴）。其余环节我都能代劳：

- 我负责开应用、点按钮、截图确认、发探针、读日志、装新包
- 你只负责"敲那一段中文"

---

## 六、完整的一次排查长什么样

```bash
# 我在电脑端执行
adb connect <本机IP>:39145
adb install -r DraftGuard-2.12.2.apk
adb shell am start -n com.draftguard/.MainActivity
adb exec-out screencap -p > step1.png          # 看：无障碍服务开了没

# 你：在微信里打「测试一下」

# 我：
adb shell am broadcast -n com.draftguard/.ProbeReceiver -a com.draftguard.PROBE --es cmd dump
adb logcat -d -s DraftGuardProbe                # 看：微信有没有送事件、有没有落盘
```

发现问题后我改代码、重新构建、`adb install -r` 覆盖安装，立刻再验一遍 ——
整个循环不用你截图，也不用你描述现象。

---

## Re-binding the accessibility service (MIUI)

**Symptom:** the app says recording has stopped, while the system's accessibility list still
shows DraftGuard as **开启 (on)**. `dumpsys accessibility` reports `Bound services:{}`, and the
in-app probe reports `service.running=false`.

**Why it happens:** installing an update or force-stopping the app kills the process, and MIUI
does **not** rebind an accessibility service afterwards — even though the list entry survives and
still reads "on". So every install leaves the app unable to record until the service is toggled
again. Expect this after **every** build you install.

**Stable recovery sequence** (verified on MIUI 12.5 / Android 11). Order matters — writing the
service list alone does nothing:

```bash
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services null
sleep 2
adb shell settings put secure accessibility_enabled 1          # master flag first
sleep 1
adb shell settings put secure enabled_accessibility_services com.draftguard/com.draftguard.TypelogService
# verify:
adb shell dumpsys accessibility | grep 'Bound services'
```

**What does *not* work:** writing `enabled_accessibility_services` on its own; writing
`accessibility_enabled 1` on its own; assuming the flag reflects a user-visible master switch.
On this MIUI build the accessibility page has **no master switch at all** —
`accessibility_enabled` is a *consequence* of having an enabled service, not a control the user
can find. An earlier in-app message that told the user to "turn on the master switch" sent them
looking for something that does not exist; the guidance is now "open the list, turn DraftGuard
off and on again".

**In-app reporting:** the app distinguishes "not enabled", "still connecting" (10 s grace after
launch), and "enabled but not connected", and only raises the blocking dialog 22 s after launch —
so a service that is merely slow to rebind does not trigger a false alarm, while a genuinely dead
one is reported on the status card immediately.

---

**What actually causes it — measured, correcting an earlier claim in this file:**

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
process, so new code is in effect anyway — force-stop adds nothing and destroys the user's setting.
