# DraftGuard · 项目状态

> 最后更新：2026-10-24（深夜重构结束）　当前版本：**v3.5.3（versionCode 30503）**
> 仓库：https://github.com/clotten/DraftGuard　CI：passing
> 复盘见 [`docs/RETRO.md`](docs/RETRO.md)｜排障见 [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)
> 分段判据见 [`docs/SEGMENTATION.md`](docs/SEGMENTATION.md)｜结构决策见 [`docs/CODE-STRUCTURE.md`](docs/CODE-STRUCTURE.md)
> 存储层决策见 [`docs/SQLITE-ONLY.md`](docs/SQLITE-ONLY.md)｜无线调试见 [`docs/REMOTE-DEBUG.md`](docs/REMOTE-DEBUG.md)

---

## 一、这是什么

记录你在**任意应用**输入框里打出的字，按分钟存成快照，应用闪退也能找回。

- 采集途径：安卓**无障碍服务**（系统里唯一能读别的应用输入框的正规通道）
- 零权限申请、**无联网代码**
- 包名 `com.draftguard`，显示名 DraftGuard

## 二、界面（三个页面）

| 页面 | 实现类 | 内容 |
|---|---|---|
| **记录** | `RecordsPage` (303) | 顶部一行摘要（服务状态 + 今天条数）；搜索框；[时间范围][看逐条][更多]；下方记录列表（卡片 + 应用图标，**长按删除**） |
| **应用** | `AppsPage` (316) | 每个记录过的应用一行（图标 + 名称 + 包名/天数/最近 + 条数）；点进去是**按天翻页**的历史（左「← 更晚」/ 右「更早 →」） |
| **工具** | `ToolsPage` (508) | 采集状态 / 实时预览 / 统计 / 今天记录过的 App 四张卡片；可折叠的**设置**（布尔项用拨动开关）；可折叠的**诊断**（43 个字段全铺开） |

时间范围只有 **最近 24 小时 / 6 小时 / 1 小时** —— 「全部时间」已按用户要求移除
（它要么很慢、要么只是"看起来全"的假象；要翻更早的记录走「应用」页按天翻）。

## 三、代码结构（v3.5 重构后）

```
MainActivity      871   只做 onCreate / 导航 / 页面切换 / 共用对话框 / 导出
RecordsPage       303   记录页
AppsPage          316   应用页（含按天翻页）
ToolsPage         508   工具页（卡片 + 设置 + 诊断）
ToolsText         133   工具页纯文案（可单测）
TypelogService   1359   无障碍回调 + 事件分发 + 落盘（状态机已抽走）
CaptureState      ~250  采集状态机：去抖 / 挂起 / 清空抢救（**可单测**）
CaptureDecisions   96   纯决策函数（碎片 / 去重 / 变短 / 趋势）
LogStore          679   数据访问（SQLite）
SqlSchema          80   表结构 + 全部 SQL 常量
Diagnostics       182   诊断正文组装
ServiceState      162   无障碍服务状态判定（四态）
NodeScanner        74   窗口结构扫描（诊断用）
RecordAdapter     182   记录卡片渲染
其余小类            —   Prefs/Json/Burst/PlainText/ImeFilter/SendBoundary/
                         Exporter/TextUtil/BurstRenderer/LogFileProvider/…
```

**共 27 个类，最大的 `TypelogService` 1359 行**（重构前 `MainActivity` 2294 行是最大的）。

## 四、当前能力（均经真机验证）

### 采集

| 能力 | 状态 |
|---|---|
| 记录任意应用输入框文字 | ✅ 14+ 个应用实测 |
| 去抖 350ms + 每次提交 fsync 等价持久性 | ✅ |
| 只记当前有焦点的输入框 | ✅ 可开关 |
| 排除输入法键盘事件 / 密码框 | ✅ |
| 输入框标识（field）随记录存盘 | ✅ 分段可复现 |
| 占位提示文字过滤 | ✅ 含小米笔记、B 站评论框 |
| **删除不产生记录**（含删到空的残渣） | ✅ 判据见 `CaptureDecisions` |
| **清空时抢救挂起版本** | ✅ 发送前最后一个字不丢 |

实测通过：微信、QQ、抖音、DeepSeek、小米笔记、Larus、B 站、拼多多、淘宝、
京东、贴吧、小黑盒、Chrome、Via、支付宝、Telegram。

### 展示

| 能力 | 状态 |
|---|---|
| **消息级分段**：点发送/搜索/发布即切段 | ✅ 微信/QQ/抖音/B 站 |
| 改错别字 / 续写草稿正确合并 | ✅ 含跨应用切换、跨天 |
| 搜索直接过滤列表 | ✅ |
| 时间范围（24h/6h/1h）、按应用筛选 | ✅ |
| **长按删除**（合并视图删整段，逐条视图删单版） | ✅ **逻辑删除**：打标记，数据仍在库里 |
| 非今天记录显示日期 | ✅ |
| 界面实时刷新（尾随节流，最后一次必被看到） | ✅ |

### 运维

| 能力 | 状态 |
|---|---|
| 后台保活（前台服务 + 常驻通知） | ✅ 可开关 |
| 清空前自动备份到 `Download/DraftGuard/backup/` | ✅ |
| 导出 zip 到公共下载目录 / 分享 | ✅ **从数据库现场生成 JSONL** |
| 诊断探针（adb 远程读内部状态） | ✅ `dump\|bursts\|export\|flush\|reset\|clear` |
| 服务状态四态检测 + 弹窗引导 | ✅ `ServiceState` |

## 五、存储层（v3.0 断舍离之后）

**只有一个真相：`files/draftguard.db`（SQLite）**，JSONL 只作为导出格式。

| 项 | 值 |
|---|---|
| 表 | `rows(id, ts, ms, day, minute, app, app_label, field, ev, chars, delta, comp, text, detail, deleted)` |
| 索引 | `ms` / `(day,app)` / `(app,ms)` / `(field,ms)` |
| 事务 | WAL + `synchronous=FULL` |
| 迁移 | 老版本 JSONL 目录：导入 → **行数一致才删**；不一致则不导入不删除 |
"
              "| 补列 | 老库启动时 `ALTER TABLE ADD COLUMN deleted`，重复执行靠 try/catch 忽略 |
| 导出 | `<day>/<app>.jsonl` 结构不变，分析脚本零改动；**只导出未逻辑删除的记录** |
| 删除 | 逻辑删除（`deleted=1`）：所有读查询都带 `deleted=0`，界面/搜索/导出自动过滤 |

为什么不再双路径：见 `docs/SQLITE-ONLY.md`（三个"看起来正常"的 bug）。

## 六、测试与守护

### 142 项离线断言（普通 JVM，不需要设备）

| 文件 | 项数 | 覆盖 |
|---|---|---|
| `test/BurstTest.java` | 62 | 分段判据、占位识别、提交边界、草稿续写 |
| `test/CaptureStateTest.java` | 34 | **会不会丢字**：去抖/清空抢救/删除残渣/趋势/定时器契约 |
| `test/DecisionsTest.java` | 19 | 碎片判定、同分钟去重、变短与趋势 |
| `test/SqlTest.java` | 15 | **真 SQLite**（sqlite-jdbc）跑同一批 SQL 常量 |
| `test/ToolsTextTest.java` | 12 | 工具页文案（统计/应用清单） |

### 四个守护脚本（CI 自动跑）

| 脚本 | 作用 |
|---|---|
| `check_diag_fields.py` | 诊断字段不能丢（38 个字段 + 判定依据） |
| `check_private_paths.py` | 环境信息 / 使用者内容不能进仓库 |
| `check_workflow.py` | 工作流 YAML 结构（缩进错位会让 CI 直接不跑） |
| `check_test_compile_list.py` | CI 的测试编译清单不能漏文件（v3.2 红过一次） |

### CI 步骤（10 个）

Checkout → JDK 17 → Android SDK → Fetch test jars → Run tests（五套 142 项）
→ Compile all sources → Guard workflow structure → Guard test compile list
→ Guard diagnostic fields → Guard private paths

## 七、已知限制

| 限制 | 说明 |
|---|---|
| 密码框 | 系统屏蔽，无法读取，属设计 |
| 部分提交按钮检测不到 | Jetpack Compose 界面（如 B 站发布）不产生点击事件，分段退回"输入框被清空" |
| 键盘提交（回车/搜索键） | 未订阅按键事件（需 `flagRequestFilterKeyEvents` + 受权） |
| 国产 ROM | 需自启动 + 电池无限制；**MIUI 上 `force-stop` 会清掉无障碍设置** |
| 应用无法自助启用无障碍 | Android 安全设计，只能引导用户手动打开 |
| 存储层无单元测试（Android 侧） | `android.database.sqlite` 在 JVM 只有空桩；用 sqlite-jdbc 覆盖 SQL，驱动层靠真机验证 |

## 八、设备操作的红线（血泪）

| 操作 | 后果 |
|---|---|
| `adb shell am force-stop com.draftguard` | **清空无障碍设置**（enabled=0、从启用列表删除）。恢复需手动重开或重装一次 |
| `adb install -r` | 安全。会重启进程加载新代码，**不需要也不能配合 force-stop** |
| 修改设置后 | 必须确认绑定：`adb shell dumpsys accessibility | grep 'Bound services'` |

详见 `docs/TROUBLESHOOTING.md` 第 12 节。

## 九、绝不入库的东西（公开仓库）

| 文件 | 为什么 |
|---|---|
| `debug.keystore` / `*.keystore` / `*.jks` | 签名密钥 |
| `keystore.properties` | 密钥库口令 |
| `*.bak` / `*.bak-*` | 密钥库备份（可能仍是旧口令） |
| `tools/.private-tokens` | 本机私有标识（护栏用） |
| `记录汇总*.md` / `logs-*/` | 使用者真实记录内容 |
| `__pycache__/` / `*.pyc` | 字节码里会残留源码的绝对路径 |
| `build/` / `.cache/` / `*.apk` | 构建产物与测试依赖 |

**提交前的三条自查**：

```
① 看完整输出，不看最后一行
② 验证"东西没坏"，而不只是"命令跑了"
③ 沿用户实际看到的那条路径验证
```

**凭据泄露的处置**：先判断泄露的是**口令**还是**密钥本身**。
口令 → 改口令（证书指纹不变，覆盖升级不受影响）；密钥 → 只能换密钥，代价是卸载重装。

## 十、数据安全

| 操作 | 记录 |
|---|---|
| 装**同签名**的新版 APK | ✅ 保留 |
| 换签名密钥 / 卸载 / 清除应用数据 / 恢复出厂 | ❌ 丢失 |
| 应用内「清除全部记录」 | ⚠️ 先备份到下载目录再清 |
| 长按删除单条/单段 | ✅ **逻辑删除**：界面与导出都看不到，但数据仍在库里（删错了可救）；只有「清空全部记录」才真正删除 |

## 十一、开发环境

| 项 | 值 |
|---|---|
| Android SDK | `<Android SDK>` |
| build-tools | `36.1.0`（该版本 aapt2 **静默忽略** `--version-code/--version-name`） |
| 平台包 | `android-36` |
| JDK | 17 |
| 手机 | Redmi Note 10 / Android 11 / MIUI 12.5 |
| 连接 | 无线调试，手机连电脑热点（`adb tcpip 5555` 固定端口） |

### 出包

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -OutName 'DraftGuard-3.5.3.apk' -VersionName '3.5.3'
```

版本号写进清单并在构建后断言产物（因为 aapt2 忽略参数）。

### 跑测试

```powershell
powershell -File tools\fetch-test-jars.ps1     # 下 sqlite-jdbc + slf4j 到 .cache/jars
# 然后按 CI 的清单编译并跑五套测试（清单见 .github/workflows/test.yml）
python tools\check_test_compile_list.py        # 先确认清单没漏文件
```

### 辅助工具（`tools/`）

| 用途 | 脚本 |
|---|---|
| 隐私/环境信息扫描（CI） | `check_private_paths.py` |
| 诊断字段防丢失（CI） | `check_diag_fields.py` |
| 工作流结构校验（CI） | `check_workflow.py` |
| 测试编译清单校验（CI） | `check_test_compile_list.py` |
| 下载测试依赖 | `fetch-test-jars.ps1` |
| 把导出数据合并成单个可读文件 | `merge_export.py` |
| 复现/排查分段 | `analyze.py` `show_day.py` `show_versions.py` `why_not_merged.py` |
| 找可疑记录 | `scan.py` `find_splits.py` `find_similar.py` `find_same_prefix.py` |
| 检查会破坏展示的内容 | `check_display.py` |
| 跨长时间续写检查 | `check_long_gap.py` |
| 方法级精确改写 Java | `patch_java.py` |

## 十二、下一步（按价值排序）

1. **文档全面校对**：README / ARCHITECTURE 的存储描述已按 v3 更新，但
   `docs/UI.md`、`docs/SEGMENTATION.md` 里仍有个别旧路径与旧版本号，值得再过一遍
2. **`TypelogService` 继续拆**：核心状态机已抽走，剩下的事件分发（`onAccessibilityEvent`
   约 300 行）与轮询可以再拆出 `WindowPoller`
3. **健康自检**：开机广播 + 定期检查服务是否在跑，掉线时发通知提醒
   （用户当前设备上无障碍能自启，暂不紧急）
4. **导出 zip 加字段说明文件**：让别人拿到数据能看懂
5. **`docs/LESSONS.md` 持续更新**：新增经验优先补进这一页（一页速查）
