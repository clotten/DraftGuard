# DraftGuard · 项目状态

> 最后更新：本轮开发结束时　当前版本：**v2.12.2（versionCode 21202）**
> 仓库：https://github.com/clotten/DraftGuard
> 开发过程复盘见 [`docs/RETRO.md`](docs/RETRO.md)；单个 bug 的修法见 [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)。

---

## 一、这是什么

记录你在**任意应用**输入框里打出的字，按分钟存档在手机上，应用闪退也能找回。

- 采集途径：安卓**无障碍服务**（系统里唯一能读别的应用输入框的正规通道）
- 零权限申请、无联网代码
- 包名 `com.draftguard`，应用显示名 DraftGuard

**命名决策**：项目名 DraftGuard（用户选定："我就是用来保存草稿的"）。
包名从早期的 `com.typelog.recorder` 改过来 —— 包名变了等于换应用，装新版前需先卸载旧包。
刻意避开 `keylog`/`keylogger` 类名字：技术准确但专指恶意键盘记录器，开源项目用它会招误解。

## 二、当前能力（均经真机验证）

### 采集

| 能力 | 状态 |
|---|---|
| 记录任意应用输入框文字 | ✅ 7 个应用实测通过 |
| 逐字实时落盘（350ms 去抖 + fsync） | ✅ |
| 输入法未上屏状态（`comp` 字段） | ✅ |
| 只记当前有焦点的输入框 | ✅ 可开关 |
| 排除输入法键盘自身事件 | ✅ 可开关 |
| 密码框跳过（系统屏蔽 + `isPassword()`） | ✅ |

**实测通过的应用**：微信、QQ、抖音、DeepSeek、小米笔记、Larus、Bilibili。

### 展示

| 能力 | 状态 |
|---|---|
| 按分钟分桶（`minute` / `bucket` 字段） | ✅ |
| 按应用分文件（`<日期>/<包名>.jsonl`） | ✅ |
| **消息级分段**：点发送/搜索/发布即切段 | ✅ 微信/QQ/抖音/Larus |
| 合并视图（只看整段）/ 逐条视图（看增量） | ✅ 可切换 |
| 改错别字只显示改后结果 | ✅ |
| 全文搜索、按应用统计、磁盘原始文件清单 | ✅ |

### 运维

| 能力 | 状态 |
|---|---|
| 后台保活（前台服务 + 常驻通知） | ✅ 可关闭 |
| 清空记录前自动备份到 `Download/DraftGuard/backup/` | ✅ |
| 导出 zip 到公共下载目录 / 分享面板 | ✅ |
| 诊断探针（adb 远程读取内部状态） | ✅ |
| 保留天数 / 忽略指定应用 / 最小字数等设置 | ✅ |

### 测试

| 文件 | 项数 | 覆盖 |
|---|---|---|
| `test/StoreTest.java` | 22 | 写入/读回/搜索/索引/分钟字段/重启 |
| `test/BurstTest.java` | 36 | 分段判据、占位识别、发送边界 |

合计 **58 项离线断言**，全部可在普通 JVM 上跑（不需要设备）。

---

## 三、关键设计决策

| 决策 | 理由 |
|---|---|
| 存**完整快照**而非按键增量 | 丢一次写入只是少一版，绝不会把文字拼错 |
| 按应用分文件 + 分钟字段 | 避免某个应用的行数淹没其他应用（踩过的坑） |
| 分段用**点击事件**的真信号 | 靠文本形态倒推"是否发送"连续错两轮，见 RETRO §10 |
| 轮询用严格判定、事件路径放宽 | 轮询是主动扫界面，放宽会读到非输入框文字（B站搜索页实测） |
| 纯逻辑抽成 `Json`/`PlainText`/`SendBoundary`/`Burst` | Android 服务类一加载就 `Stub!`，不抽出来无法离线测试 |
| 不用 Gradle、不用 androidx | 只用 SDK 自带工具构建，APK 61KB，存储层可在普通 JVM 上测 |

---

## 四、已知限制（都写进了文档）

| 限制 | 说明 |
|---|---|
| 密码框 | 系统屏蔽，无法读取，属设计 |
| 部分提交按钮检测不到 | Jetpack Compose 界面（如 B 站发布）不产生逐按钮点击事件，分段退回文本判据 |
| 键盘提交（回车/搜索键） | 未订阅按键事件（需 `flagRequestFilterKeyEvents` + 受权） |
| "续打同一句" vs "发了一条相似消息" | 文本层面同形，无提交信号时无法区分 |
| 部分国产 ROM | 需自启动 + 电池无限制，否则无障碍被系统限制 |

---

## 五、数据安全（重要）

| 操作 | 记录 |
|---|---|
| 装**同签名**的新版 APK | ✅ 保留（正常升级路径） |
| 换签名密钥 | ❌ 丢失 |
| 卸载 / 清除应用数据 / 恢复出厂 | ❌ 丢失 |
| 应用内「清除全部记录」 | ⚠️ 先备份再清 |

**密钥与数据同等重要**：密钥丢了就再也发不出能覆盖安装的包，只能重装，记录跟着没。
本仓库不提交密钥（`.gitignore` 已排除），本地备份在 `draftguard-release.keystore` 与 `debug.keystore`。

---

## 六、开发环境与构建

| 项 | 值 |
|---|---|
| Android SDK | `D:\android-sdk_r24.4.1-windows\android-sdk-windows` |
| build-tools | `36.1.0` |
| 平台包 | `android-36` |
| JDK | 17 |
| 手机 | Redmi Note 10（M2103K19C）/ Android 11 / MIUI 12.5 |
| ADB 连接 | 无线调试；手机连电脑热点，`192.168.137.173:5555` |

### 出包

```powershell
# 调试签名（默认）
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -OutName 'DraftGuard-2.12.2.apk'

# 正式签名 + 从版本号自动推算 versionCode
.\release.ps1 -Version 2.13.0
```

**版本号约定**：`versionCode = major*10000 + minor*100 + patch`（2.12.2 → 21202）。
两处（`build-apk.ps1` 与 `AndroidManifest.xml`）必须一致，否则新包会被系统当成降级而装不上。

### 跑测试（不需要设备）

```powershell
$aj='D:\android-sdk_r24.4.1-windows\android-sdk-windows\platforms\android-36\android.jar'
$lib='D:\android-sdk_r24.4.1-windows\android-sdk-windows\build-tools\36.1.0\core-lambda-stubs.jar'
New-Item -ItemType Directory -Force build\t | Out-Null

# 分段与占位判定（36 项）
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$aj;$lib" -classpath $aj -nowarn -d build\t `
  android\java\com\draftguard\LogStore.java android\java\com\draftguard\Record.java `
  android\java\com\draftguard\Json.java android\java\com\draftguard\Burst.java `
  android\java\com\draftguard\PlainText.java android\java\com\draftguard\SendBoundary.java `
  test\BurstTest.java
java -cp "build\t;$aj" com.draftguard.BurstTest

# 存储层（22 项）
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$aj;$lib" -classpath $aj -nowarn -d build\t `
  android\java\com\draftguard\LogStore.java android\java\com\draftguard\Record.java `
  android\java\com\draftguard\Json.java test\StoreTest.java
java -cp "build\t;$aj" com.draftguard.StoreTest
```

### 远程调试

见 [`REMOTE-DEBUG.md`](docs/REMOTE-DEBUG.md)。应用私有数据用**诊断探针**读取
（Android 11+ 禁止 `adb pull` 私有目录）：

```bash
adb shell am broadcast -n com.draftguard/.ProbeReceiver -a com.draftguard.PROBE --es cmd dump
adb logcat -d -s DraftGuardProbe
```

探针需在应用内开启「诊断日志」；`clear` 需额外 `--ez confirm true`。

---

## 七、目录结构

```
DraftGuard/
├─ README.md                 英文说明（GitHub 首页）
├─ PROJECT_STATE.md          本文件：当前状态与交接
├─ LICENSE / .gitignore / .gitattributes
├─ build-apk.ps1             无 Gradle 构建
├─ release.ps1               正式签名出包
├─ android/
│  ├─ AndroidManifest.xml    零权限申请；声明无障碍服务、保活服务、诊断探针
│  ├─ java/com/draftguard/
│  │  ├─ TypelogService.java    采集主体（事件、去抖、兜底、轮询、提交检测）
│  │  ├─ LogStore.java          存储（分文件、分钟字段、索引、搜索、备份）
│  │  ├─ Burst.java             消息分段（纯逻辑，可离线测）
│  │  ├─ PlainText.java         占位文字判定（纯逻辑）
│  │  ├─ SendBoundary.java      提交信号持有（纯类，避免依赖 Android 服务）
│  │  ├─ Json.java              自研 JSON 拼解（替代 org.json，可离线测）
│  │  ├─ Exporter.java          导出/备份到公共下载目录
│  │  ├─ KeepAliveService.java  前台服务保活
│  │  ├─ ProbeReceiver.java     诊断探针
│  │  ├─ MainActivity.java      界面
│  │  ├─ Prefs.java             设置
│  │  ├─ ImeFilter.java         输入法包名识别
│  │  ├─ LogFileProvider.java   分享用 Provider
│  │  └─ Record.java
│  └─ res/                  无障碍配置、布局、图标
├─ test/
│  ├─ StoreTest.java        存储层 22 项
│  └─ BurstTest.java        分段与占位判定 36 项
├─ docs/
│  ├─ ARCHITECTURE.md       设计决策
│  ├─ TROUBLESHOOTING.md    8 节排障 + 安卓/构建避坑
│  ├─ REMOTE-DEBUG.md       无线调试与探针
│  └─ RETRO.md              开发复盘（含方法论）
└─ tools/
   ├─ pair.ps1              adb 配对（解决 PowerShell stdin 问题）
   ├─ connect-phone.ps1     一条命令配对+连接
   └─ make_pair_qr.py       二维码生成尝试（adb 无此能力，留作记录）
```

---

## 八、下一步（按价值排序）

1. **界面美化**（用户提过）——目前是代码搭布局，朴素但能用
2. **试"输入框失焦"检测**——覆盖 Compose 类应用的分段；成本低，是三种候选方案里最划算的
3. **README 补新踩的坑**——包可见性限制、`getHintText` 陷阱、MediaStore 导出
4. **导出的 zip 里加字段说明文件**——让别人拿到数据能看懂
5. **CI 增加 BurstTest**——`.github/workflows/test.yml` 目前只跑 StoreTest
