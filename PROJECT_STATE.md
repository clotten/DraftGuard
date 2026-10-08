# DraftGuard · 项目状态存档

> 存档时间：2026-10-08　当前版本：**v2.0.0（versionCode 1，新包名）**
> 项目英文名：**DraftGuard**（GitHub 用），应用显示名同步改为 DraftGuard
> 用途：记录"安卓任意 App 输入框里打出的字，按分钟存档，闪退可找回"这个 App 做过的所有事、
> 哪些已验证、哪些还没解决。之后正式开发 / 建 GitHub 仓库时，这份文档就是起点。

## 命名决策

| 项 | 值 | 说明 |
|---|---|---|
| 项目名 | DraftGuard | 用户选定："我就是用来保存草稿的" |
| 应用显示名 | DraftGuard | `strings.xml` 的 `app_name` |
| 包名 | `com.draftguard` | 已从 `com.typelog.recorder` 改过来。**升级要点**：包名变了，Android 视为另一个应用，装新包前必须先卸载旧包 |
| 无障碍服务名 | DraftGuard · 文本采集 | 显示在系统无障碍列表里，保持中文 |

排除了 `keylog` / `keylogger` 类名字：技术上准确，但该词专指恶意键盘记录器，开源项目用它会招误解。


---

## 一、现状一句话

**采集链路已打通并验证**：无障碍服务能收到微信（`com.tencent.mm`）等第三方应用的输入框事件，
逐字符文本已能落盘、能读回、能搜索。剩下的都是"打磨 + 少数待确认项"，不是"能不能用"的问题。

截止存档时的真机数据：当天 **246 条**记录，磁盘占用 **41 KB**，微信与微信读书（`com.tencent.mtt`）
均出现过文本变化事件。

---

## 二、已完成的四轮修复（每一轮都由真机现象驱动）

| 版本 | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1.0 → 1.1 | 微信里打字，应用里**一个字都没有** | 只在 `event.getSource()` 非空且可编辑时处理；安卓 11+ 该调用常返回 null | 加"遍历活动窗口找回输入框"+ 900ms 轮询兜底 + 输入框文本向下读子节点 |
| 1.1 → 1.2 | 分不清事件有没有到、断在哪 | 诊断信息太少 | 按包名统计事件、单独判断微信、显示扫描详情 |
| 1.2 → 1.3 | 删除也被记录；应用自己搜索框的字没记 | 每次变化都落盘；自己的包名在跳过名单里 | 文本变短即视为删除不落盘；本应用不再跳过；排除输入法键盘自身事件 |
| 1.3 → 1.4 | 有记录但"看不到"、中文名填不上、重启后索引丢失 | `putAppLabel` 没更新内存缓存；空值占位挡住真值；`renameTo` 失败被静默吞掉 | 修缓存、不写空值、索引改直写 + fsync + 错误上屏 |
| 1.4 → 1.5 | 微信记录**搜不到、列表里也没有** | `search()` 的 `limit` 被当成"每个文件"的上限用，第一个文件（191 条噪音）读满就 `return`，后续文件永不读取 | `limit` 改为跨文件增量上限；顺带过滤占位提示文字与单字符碎片 |

---

## 三、已验证的部分（不是"应该能用"，是测过）

### 存储层离线测试：22 项全绿

`test/StoreTest.java` —— 用普通 JVM 直接跑真实的 `LogStore`，不依赖手机。覆盖：

- 写入 / 读回 / 重新打开（模拟重启）
- 跨 App 记录（微信与自身互不干扰）
- 换行、引号、反斜杠的转义与反转义
- 搜索命中、搜不到时返回 0、结果条数不超过上限
- **回归用例**：A 文件 150 条噪音 + B 文件微信记录，用 60 的上限搜索，必须能搜到 B
- 中文名索引、分钟字段格式、原始文件格式人工核对

跑法见第六节。

### 真机已验证

- 微信输入框逐字符捕获：日志见到 `[这是]` → `[这是微]` → `[这是微信]` → `[这是微信上的]`
- 无障碍服务能收到第三方应用事件（此前完全收不到，是 MIUI 权限未放行的表现）
- 实时预览、落盘、磁盘读回三者一致

---

## 四、当前功能清单

| 能力 | 状态 |
|---|---|
| 记录任意 App 输入框的文字 | ✅ |
| 按分钟分类（`minute` / `bucket` 字段） | ✅ |
| 按 App 分文件（`<日期>/<包名>.jsonl`） | ✅ |
| 存完整快照而非按键增量（丢一次不影响文字完整性） | ✅ |
| 逐字实时落盘（350ms 去抖 + fsync） | ✅ |
| 删除操作不记录（文字变短即跳过） | ✅ 可开关 |
| 密码框由系统屏蔽（`isPassword()` 主动跳过） | ✅ |
| 排除输入法键盘自身事件 | ✅ 可开关 |
| 过滤占位提示文字 / 单字符碎片 | ✅ 门槛可调 |
| 搜不到 → 修好（跨文件搜索截断） | ✅ |
| 全文搜索、看今天全部记录、导出 zip | ✅ |
| 诊断面板（事件计数、按包名统计、磁盘原始文件清单） | ✅ |
| 保留天数 / 忽略指定 App / 最小字数 设置 | ✅ |
| 自己处理输入（IME 方案，彻底绕开厂商拦截） | ❌ 未做 |

---

## 五、已知问题与待确认项（正式开发时先看这里）

1. **【最高优先】单字符碎片仍在落盘**
   截图里存在 `15:36:22 [15:36] 你` 这样每分钟一条的单字记录。
   1.5 已加"新建输入框少于 2 字不记"的门槛，但需在真机确认是否生效
   （`skippedNoise` 计数应增长）。若仍出现，说明轮询路径的判定顺序需要调整。

2. **占位提示文字仍出现旧记录**
   `搜索记录过的文字…` 是 1.5 之前写下的历史脏数据，新版本应不再新增。**建议加一个"清理噪音"功能**。

3. **`lastApp` 标签曾写错**
   实时预览里出现过"应用：字迹留存"但内容是微信文字的情况（事件包名与节点包名不一致）。
   数据本身没错（落在 `com.tencent.mm` 文件里），但显示会误导。修法：`handleText` 里以 `node.getPackageName()` 为准。

4. **微信"输入框节点读不到"**
   多张诊断截图里 `根节点 null；输入焦点=null`。目前靠事件里的 `EditText` 文本兜底，
   能记到但拿不到焦点信息。属于 MIUI/微信的限制，暂不深究。

5. **`PKG_SELF` 常量已无用**，`LogStore` 里还留着声明，清理掉。

6. **`skippedOther` 计数**在 `handleText` 里已不再使用（被 `skippedSelf`/`skippedIgnored` 取代），可删。

7. **前台服务与保活未做**
   当前仅靠无障碍服务自身优先级。若发现长时间不用后被系统清理，需补 `ForegroundService`
   或 `BOOT_COMPLETED` 重启逻辑。国产 ROM 还需引导用户设"自启动 + 电池无限制"。

8. **导出功能未在真机验证过**（`LogFileProvider` + ACTION_SEND 那条路径）。

---

## 六、开发与构建

### 环境（本机已验证可用）

| 项 | 路径 |
|---|---|
| Android SDK | `D:\android-sdk_r24.4.1-windows\android-sdk-windows` |
| build-tools | `36.1.0`（aapt2 / d8 / zipalign / apksigner 齐全） |
| 平台包 | `android-36`（提供 `android.jar`） |
| JDK | `C:\Users\clotten\AppData\Local\Programs\FlyEnv-Data\env\java`（17） |
| 签名密钥 | `debug.keystore`（在 `.gitignore` 里，未上传），别名 `typelog`，口令 `typelog123`（仅本机调试用） |

### 出包（不需要 Gradle、不需要联网）

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -OutName 'DraftGuard-2.0.0.apk'
```

流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 塞 dex → `zipalign` → `apksigner` → 校验。

**改版本号要同时改两处**：`build-apk.ps1` 里的 `--version-code/--version-name`，
以及 `app/AndroidManifest.xml` 的 `versionCode/versionName`。

### 跑存储层测试

```powershell
$aj='D:\android-sdk_r24.4.1-windows\android-sdk-windows\platforms\android-36\android.jar'
$lib='D:\android-sdk_r24.4.1-windows\android-sdk-windows\build-tools\36.1.0\core-lambda-stubs.jar'
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$aj;$lib" -classpath $aj -nowarn `
  -d build\test android\java\com\draftguard\LogStore.java `
  android\java\com\draftguard\Record.java android\java\com\draftguard\Json.java `
  test\StoreTest.java
java -cp "build\test;$aj" com.draftguard.StoreTest
```

### 构建时踩过的坑（别再踩一遍）

1. **`sources.txt` 不能带 BOM**：PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写 BOM，
   javac 报"无效文件名"。用 `[IO.File]::WriteAllLines(..., UTF8Encoding($false))`。
2. **lambda 需要 `core-lambda-stubs.jar` 进 bootclasspath**，否则报
   `找不到符号: 方法 metafactory`。
3. **`d8` 要求输出目录预先存在**，写成 `.zip/.jar` 或已有目录，否则 `Invalid output`。
4. **不能用 `$ErrorActionPreference='Stop'`**：PS 5.1 会把 keytool/aapt2 的正常 stderr 当异常抛出。
5. **Java 注释里不能出现 `\u`**（javac 会在注释阶段解析 Unicode 转义，报"非法的 Unicode 转义"）。
6. **`org.json` 在 `android.jar` 里是空实现**（`RuntimeException: Stub!`），
   用了就没法离线测试 —— 已换成自研 `Json.java`。
7. **多行锚点替换要注意 CRLF**：工程文件是 CRLF，PowerShell here-string 默认 LF，匹配不上。

---

## 七、设计决策与理由（GitHub README 可直接用）

- **采集途径选无障碍服务**：安卓上唯一能读取其他应用输入框的正规通道。密码框由系统自动屏蔽，
  正好对上"除了密码键盘"的边界。
- **存完整快照而非增量**：任何一次写入丢失只是少一版记录，绝不会把文字拼错。去重靠内容哈希。
- **按 App 分文件 + `minute` 字段**：`<日期>/<包名>.jsonl`，一行一条带 `ts/ms/minute/bucket/chars/delta/comp/text`。
  既满足"按分钟分类"，又不会碎成一堆小文件。
- **`comp` 字段**：标记输入法未上屏状态（拼音/候选阶段），这类状态也存，所以"打着打着闪退"能找回。
- **350ms 去抖 + 句柄常开 + fsync**：连打时只落最新一版；`fsync` 保证真落盘而非只进页缓存。
- **900ms 轮询兜底**：应对不发文本变化事件的应用，靠内容去重不会产生重复记录。
- **不依赖 androidx、纯 Java + 代码搭布局**：单文件工程就能用 SDK 自带工具构建出 APK，
  也避免了 Gradle 与网络依赖。

---

## 八、目录结构

```
DraftGuard/                    ← 仓库根目录
├─ DraftGuard-2.0.0.apk        已签名成品（versionCode 7）
├─ README.md                 英文版说明（面向 GitHub）
├─ PROJECT_STATE.md          本文件：开发存档（中文）
├─ LICENSE                   MIT
├─ .gitignore                排除 build/、*.keystore、data/ 等
├─ build-apk.ps1             无 Gradle 构建脚本
├─ docs/
│  ├─ TROUBLESHOOTING.md     六轮真机 bug 的"现象→根因→修法" + 安卓/构建避坑
│  └─ ARCHITECTURE.md        设计决策：为什么无障碍、为什么快照、为什么按分钟
├─ debug.keystore            调试签名（已在 .gitignore 里，别提交）
├─ android/
│  ├─ AndroidManifest.xml
│  ├─ java/com/draftguard/
│  │  ├─ TypelogService.java 采集主体（事件、去抖、兜底、轮询）
│  │  ├─ LogStore.java       存储层（分文件、分钟字段、索引、搜索、清单）
│  │  ├─ Json.java           自研 JSON 拼/解（替代 org.json 以便离线测试）
│  │  ├─ MainActivity.java   界面（状态、预览、统计、搜索、诊断、设置）
│  │  ├─ Prefs.java          设置项
│  │  ├─ ImeFilter.java      输入法包名识别
│  │  ├─ LogFileProvider.java 导出用文件 Provider
│  │  └─ Record.java
│  └─ res/ (xml 无障碍配置 / layout / values / 图标)
└─ test/StoreTest.java       存储层离线测试（22 项）
```

---

## 九、下一步建议（正式开发时的顺序）

1. **先做"清理噪音"**：把历史脏数据（占位提示、单字符碎片）一键清掉，让记录干净可读。
2. **确认单字符门槛生效**，否则调 `handleText` 里判定与轮询的先后顺序。
3. **修 `lastApp` 标签**，以节点包名为准。
4. **加前台服务 / 开机自启**，提高长时间存活率。
5. **真机验证导出 zip**。
6. 若某台机器上无障碍被彻底封死，再考虑 **IME 方案**（自己处理输入，不读别的应用，
   厂商拦不到，还能记录拼音未上屏状态），这是"终极兜底"。

### GitHub 仓库建议

- 仓库地址：https://github.com/clotten/DraftGuard（已上传）
- 分支：`main`（可用版本）+ `dev`（IME 实验）
- 必备文件：`README.md`（把第七节的设计决策搬过去）、`LICENSE`、
  `.gitignore`（排除 `build/`、`*.keystore`、`data/`）、
  `docs/故障排查.md`（把第二节的四轮修复写成"现象 → 根因 → 修法"，
  这类真实排查记录比功能列表有价值得多）
- 别忘了在 README 里写明**隐私边界**：无联网代码、不申请任何权限（连 INTERNET 都没有）、
  密码框由系统屏蔽、数据只在应用私有目录。
```
