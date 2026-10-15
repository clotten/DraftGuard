# DraftGuard

[![test](https://github.com/clotten/DraftGuard/actions/workflows/test.yml/badge.svg)](https://github.com/clotten/DraftGuard/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-26-green.svg)](#环境要求)

[English](README.md) · **简体中文**

**文字应该在打字时就落盘，而不是等按下发送。**

DraftGuard 是一个安卓应用：它持续记录你在**任意应用**输入框里敲的字，
按分钟存成快照存在手机本地 —— 应用闪退、划掉后台、网页重载，草稿都还在。

- **不联网、不要账号、没有云端。** 清单里**没有 `INTERNET` 权限**，也就是说它
  **物理上无法**把文字发到任何地方。（[为什么这点很重要](#隐私)）
- **不申请任何权限。** 无障碍服务是安卓唯一允许读取别的应用输入框的通道，
  而且是你在系统设置里**手动开启**的，不是应用申请的。
- **只用 SDK 自带工具构建。** 不需要 Gradle、不引入 AndroidX、不下载任何依赖。

```
微信  ─┐
QQ    ─┤                                 files/draftguard/2026-10-08/
酒馆  ─┼──►  无障碍服务        ──►         ├── com.tencent.mm.jsonl
浏览器─┤     (350ms 去抖 + fsync)           ├── com.draftguard.jsonl
笔记  ─┘                                   └── index.json
```

---

## 为什么要做这个

在手机上写一段长文字很脆弱。应用被后台杀掉、WebView 重载、浏览器丢弃标签页 ——
你花了十分钟写的那段话就没了，因为它**从来只存在于输入框的内存里**。

DraftGuard 的答案：**文字应该在打字时就落盘，而不是等按下发送。**

## 它是怎么做的

- **每一次输入都被捕获。** 本地优先，350ms 去抖，每次写入都 `fsync`。
- **按分钟归档。** 每个应用每天一个文件，每条版本都带 `minute` 字段，
  于是能精确还原"什么时候写了什么"。
- **存完整快照，不存按键增量。** 每条记录保存的是当时输入框的**完整文本**。
  一次写入丢失只是少一个版本 —— **永远不会把你的文字拼错**。
- **含输入法未上屏状态。** 还在拼音候选阶段的文字也会被记录，标 `comp: true`。
- **密码框不可触碰。** 系统会屏蔽它；应用另外还会检查 `isPassword()`，
  在把任何内容读进内存**之前**就跳过。
- **分段靠真信号，不靠猜。** 点击「发送 / 搜索 / 发布」会从无障碍点击事件里检测到，
  于是每条已提交的消息各自成段，而不是连续几条塌缩成最后一条。
  若输入框被清空但没有可检测的提交动作（Jetpack Compose 常见），
  就用"输入框变空"这件事本身当边界。
- **只记你正在输入的那个框**（输入焦点）—— 后台的框、重绘时补发的旧内容、
  只显示占位文字的空框，全部忽略。
- **占位提示绝不入库。** 同时处理 `getHintText()` 和"把提示语直接当 `getText()` 返回"
  的机型（小米笔记、B 站），另有一条"输入框首条文本疑似提示"的兜底。
- **删除不产生记录**，包括"逐个删除到空"时留下的残渣：打错、删掉、重打，
  看到的只是最终文本，原始版本仍留在磁盘上。
- **保活**：前台服务 + 常驻通知（可关闭）。
- **清空记录前先备份**到 `Download/DraftGuard/backup/`。
- **诊断探针**（`ProbeReceiver`）让你通过 `adb` 读取应用内部状态 ——
  **45 个计数**覆盖了每一条可能丢记录的路径。

### 三个页面

- **记录** —— 顶部一行状态、一个搜索框、三个按钮，然后就是记录本身。每条是一张带应用图标的卡片。
- **应用** —— 所有被记录过的应用（图标 + 名称 + 条数），点进去看该应用按天分组的全部历史。
- **工具** —— 采集状态、实时预览、今日统计、应用清单，
  外加减去可折叠的**设置**（布尔项是真开关，默认收起，避免误触）和
  **诊断**（全部 45 个内部计数，含最近 25 条原始无障碍事件）。

## 隐私

| | |
|---|---|
| 申请的权限 | **无** |
| 网络访问 | **无** —— 清单里没有 `INTERNET` 权限 |
| 数据位置 | 应用私有目录，其它应用读不到 |
| 密码框 | 系统屏蔽，应用另外主动跳过 |
| 卸载应用 | 所有记录一并删除 |

唯一会离开应用的东西，是你自己通过系统分享导出的 ZIP。

一个能读别的应用输入框的程序，**看起来和键盘记录器一模一样**，
杀毒软件这么判断是合理的。这里能区分的是两件**可以从 APK 直接验证**（不用信任作者）的事：

```bash
aapt2 dump permissions DraftGuard.apk     # 确认没有 INTERNET 权限
apksigner verify --print-certs DraftGuard.apk
```

**数据能跨版本升级保留**，前提是同一个签名密钥 —— 记录在应用私有目录，不在 APK 里。
卸载、清除应用数据、更换签名密钥、恢复出厂设置会丢。详见
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) 的数据安全一节。

## 环境要求

- Android 8.0（API 26）及以上
- Android 13+：侧载安装需要先打开**「允许受限设置」**
  （设置 → 应用 → DraftGuard → ⋮ → 允许受限设置），否则无障碍开关是灰的。

## 安装

```bash
adb install -r DraftGuard-2.35.1.apk
```

或者把 APK 拷到手机上点击安装。然后：

1. 打开 **DraftGuard**
2. 点 **打开系统无障碍设置**
3. 在无障碍列表里找到 **DraftGuard · 文本采集** 并打开
4. 去任意应用打个字，回到应用 —— 实时预览应该立刻显示出来

> 用 `adb` 安装可以避开 Android 13+ 侧载带来的"受限设置"问题。
>
> 在 MIUI 上，安装更新后无障碍条目可能短暂显示为"故障"。
> 那是进程被重启，不是崩溃 —— 见 [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) 第 15 节。

## 验证过的行为

两套测试**跑在普通 JVM 上**，不需要模拟器、不需要设备：

| 测试 | 项数 | 覆盖 |
|---|---|---|
| `test/BurstTest.java` | 62 | 分段判据、占位识别、提交边界、草稿续写 |
| `test/StoreTest.java` | 22 | 写入 → 读回 → 重开、转义、搜索、索引、分钟归档 |

```bash
javac -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR;$LAMBDA_STUBS" -classpath "$ANDROID_JAR" -nowarn \
  -d build/test android/java/com/draftguard/LogStore.java \
    android/java/com/draftguard/Record.java \
    android/java/com/draftguard/Json.java test/StoreTest.java

java -cp "build/test;$ANDROID_JAR" com.draftguard.StoreTest
```

CI 另外会全量编译整个应用，并跑三个守护脚本：诊断字段覆盖、私有路径扫描、工作流结构校验。

## 构建

> 本仓库**只提供源码** —— 不提交 APK，也不在 Release 里附带产物。

不用 Gradle、不联网、不依赖 AndroidX，只用 SDK 自带工具：

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -OutName 'DraftGuard-2.35.1.apk'
```

`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 重打包 → `zipalign` → `apksigner` → 校验。

SDK 路径会自动探测（先看 `ANDROID_HOME`，再找常见安装位置）；不一致时手动指定：

```powershell
.\build-apk.ps1 -Sdk "C:\Android\Sdk" -Bt "35.0.0" -Plat "android-36"
```

密钥库口令依次从 `DRAFTGUARD_STOREPASS` 环境变量、`keystore.properties`（已 gitignore）、
交互输入读取 —— **不写默认值**，因为这个文件是公开的。

## 数据安全

记录在应用私有目录，与 APK 分离。**用同一密钥签名的新版本可以覆盖升级并保留数据**；
卸载、清除应用数据、更换签名密钥则不行。应用内"清除全部记录"会先备份到
`Download/DraftGuard/backup/`。

## 数据格式

```
files/draftguard/
└── 2026-10-08/
    ├── com.tencent.mm.jsonl        每个应用每天一个文件
    ├── com.draftguard.jsonl
    └── index.json                  包名 → 人类可读的名称
```

一行一个 JSON 对象，一行一个版本：

```json
{"ts":"2026-10-08T15:36:34.418","ms":1791444994418,"day":"2026-10-08",
 "minute":"15:36",
 "bucket":"2026-10-08 15:36",
 "app":"com.tencent.mm","ev":"text","chars":9,"delta":1,
 "comp":false,"text":"这是一段示例文本"}
```

| 字段 | 含义 |
|---|---|
| `ts` / `ms` | 毫秒级时间戳 |
| `minute` / `bucket` | 这一版属于哪一分钟 |
| `chars` / `delta` | 长度，以及与上一版的变化量 |
| `comp` | `true` = 输入法还没上屏（拼音候选阶段） |
| `ev` | `text` = 一个版本；`send` = 提交边界（正文为空） |
| `field` | 来自哪个输入框，用于把版本重新归组 |
| `text` | **当时输入框的完整内容** |

## 文档

- [`docs/SEGMENTATION.md`](docs/SEGMENTATION.md) —— 完整的分段判据：判定顺序、阈值、
  每条判据背后的真实反馈，以及"分层原则"
- [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) —— 开发中真实踩过的坑，
  每条按"现象 → 根因 → 修法"写，附安卓/MIUI 的底层机制
- [`docs/LESSONS.md`](docs/LESSONS.md) —— 一页速查：最值得记住的经验
- [`docs/UI.md`](docs/UI.md) —— 界面设计与实现中踩过的界面坑
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) —— 为什么用无障碍、为什么存快照、为什么按分钟
- [`docs/RETRO.md`](docs/RETRO.md) —— 开发复盘：什么做对了、什么反复出错、时间花在哪
- [`docs/REMOTE-DEBUG.md`](docs/REMOTE-DEBUG.md) —— ADB 无线调试连接，以及让远程排查成为可能的诊断探针
- [`docs/KASPERSKY-EXCLUSIONS.md`](docs/KASPERSKY-EXCLUSIONS.md) —— 杀软为什么会拦这个项目的工具，以及该加哪些排除项
- [`PROJECT_STATE.md`](PROJECT_STATE.md) —— 当前状态、已验证清单、已知问题、路线图

## 已知限制

- **密码框 / 安全键盘** —— 系统屏蔽，任何程序都读不到，这是设计如此。
- **输入法自己的候选栏** —— 那是键盘的文字，不是你输入框的内容，会被跳过。
- **部分国产 ROM**（尤其 MIUI / HyperOS）会阻止第三方无障碍服务读取其它应用，
  需要你额外授予自启动、电池无限制、「允许受限设置」。见排障文档。
- **部分提交按钮不产生点击事件**（Jetpack Compose，例如 B 站的发布按钮），
  那里分段退回"输入框被清空"这个信号。
- **应用自己的搜索框会被记录**，这是故意的，方便你不离开应用就能自测。

## 路线图

- [ ] 在更多机型上调焦点过滤（已有一条兜底路径，应对 `isFocused()` 不可靠的 ROM）
- [ ] 开机广播接收器，让重启后不必手动打开应用就能恢复采集
- [ ] 可选的输入法采集模式，应对完全锁死无障碍的设备
- [ ] 应用内按条删除记录，便于清理垃圾版本而不用清空全部

## 许可

[MIT](LICENSE)
