# DraftGuard · 项目状态

> 最后更新：本轮开发结束时　当前版本：**v2.29.0（versionCode 22900）**
> 仓库：https://github.com/clotten/DraftGuard
> 开发复盘见 [`docs/RETRO.md`](docs/RETRO.md)；排障见 [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)
> （12 节）；无线调试与设备操作见 [`docs/REMOTE-DEBUG.md`](docs/REMOTE-DEBUG.md)。

---

## 一、这是什么

记录你在**任意应用**输入框里打出的字，按分钟存档在手机上，应用闪退也能找回。

- 采集途径：安卓**无障碍服务**（系统里唯一能读别的应用输入框的正规通道）
- 零权限申请、无联网代码
- 包名 `com.draftguard`，应用显示名 DraftGuard

## 二、界面（三个页面）

| 页面 | 内容 |
|---|---|
| **记录** | 顶部一行摘要（服务状态 + 今天条数）；搜索框；[时间范围][看逐条][更多]；下方是记录列表（卡片式，带应用图标） |
| **应用** | 每个记录过的应用一行：图标 + 名称 + 包名/天数/最近时间 + 条数；点进去看该应用全部历史（按天分组） |
| **工具** | 采集状态 / 实时预览 / 统计 / 今天记录过的 App 四张卡片；可折叠的**设置**（布尔项用拨动开关）；可折叠的**诊断**（41 个字段全铺开） |

设计取舍来自与用户逐项确认：首屏直接是记录、诊断信息收进工具页、
12 个按钮收成 3 个 + 「更多」、条目带图标、深色 + 圆角卡片。

## 三、当前能力（均经真机验证）

### 采集

| 能力 | 状态 |
|---|---|
| 记录任意应用输入框文字 | ✅ 10+ 个应用实测 |
| 逐字实时落盘（350ms 去抖 + fsync） | ✅ |
| 只记当前有焦点的输入框 | ✅ 可开关 |
| 排除输入法键盘事件 / 密码框 | ✅ |
| 输入框标识（field）随记录存盘 | ✅ 分段可复现 |
| 占位提示文字过滤 | ✅ 含小米笔记、B 站评论框 |

**实测通过**：微信、QQ、抖音、DeepSeek、小米笔记、Larus、B 站、
拼多多、淘宝、京东、贴吧、小黑盒、Chrome、Via。

### 展示

| 能力 | 状态 |
|---|---|
| **消息级分段**：点发送/搜索/发布即切段 | ✅ 微信/QQ/抖音/B 站 |
| 改错别字 / 续写草稿正确合并 | ✅ 含跨应用切换、跨天 |
| 搜索直接过滤列表 | ✅ |
| 时间范围（全部/1h/6h/24h）、按应用筛选 | ✅ |
| 非今天记录显示日期 | ✅ |

### 运维

| 能力 | 状态 |
|---|---|
| 后台保活（前台服务 + 常驻通知） | ✅ 可开关 |
| 清空前自动备份到 `Download/DraftGuard/backup/` | ✅ |
| 导出 zip 到公共下载目录 / 分享 | ✅ |
| 诊断探针（adb 远程读内部状态） | ✅ |
| **服务状态四态检测 + 弹窗引导** | ✅ |
| 诊断字段防丢失检查（CI） | ✅ 41 个字段 |

### 测试

| 文件 | 项数 | 覆盖 |
|---|---|---|
| `test/BurstTest.java` | 58 | 分段判据、占位识别、提交边界、草稿续写 |
| `test/StoreTest.java` | 22 | 写入/读回/搜索/索引/分钟字段/重启 |

合计 **80 项离线断言**，跑在普通 JVM 上，不需要设备。CI 另含全量源码编译
与诊断字段守护。

分段判据的完整参考见 [`docs/SEGMENTATION.md`](docs/SEGMENTATION.md)（阈值表、判定顺序、每条判据对应的真实反馈、已知取舍）。

速查版经验见 [`docs/LESSONS.md`](docs/LESSONS.md)；界面设计与实现坑见 [`docs/UI.md`](docs/UI.md)。

## 四、已知限制

| 限制 | 说明 |
|---|---|
| 密码框 | 系统屏蔽，无法读取，属设计 |
| 部分提交按钮检测不到 | Jetpack Compose 界面（如 B 站发布）不产生逐按钮点击事件，分段退回文本判据 |
| 键盘提交（回车/搜索键） | 未订阅按键事件（需 `flagRequestFilterKeyEvents` + 受权） |
| 国产 ROM | 需自启动 + 电池无限制；**MIUI 上 `force-stop` 会清掉无障碍设置** |
| 应用无法自助启用无障碍 | Android 安全设计，只能引导用户手动打开 |

## 五、设备操作的红线（血泪）

| 操作 | 后果 |
|---|---|
| `adb shell am force-stop com.draftguard` | **清空无障碍设置**（enabled=0、从启用列表删除）。恢复需手动重开或重装一次 |
| `adb install -r` | 安全。会重启进程加载新代码，**不需要也不能配合 force-stop** |
| 修改设置后 | 必须确认绑定：`adb shell dumpsys accessibility | grep 'Bound services'` |

详见 `docs/TROUBLESHOOTING.md` 第 12 节。

## 六、数据安全

| 操作 | 记录 |
|---|---|
| 装**同签名**的新版 APK | ✅ 保留 |
| 换签名密钥 / 卸载 / 清除应用数据 / 恢复出厂 | ❌ 丢失 |
| 应用内「清除全部记录」 | ⚠️ 先备份到下载目录再清 |

**密钥与数据同等重要**。本仓库不提交密钥（`.gitignore` 已排除）。

## 七、开发环境

| 项 | 值 |
|---|---|
| Android SDK | `D:\android-sdk_r24.4.1-windows\android-sdk-windows` |
| build-tools | `36.1.0`（注意：该版本 aapt2 **静默忽略** `--version-code/--version-name`） |
| 平台包 | `android-36` |
| JDK | 17 |
| 手机 | Redmi Note 10（M2103K19C）/ Android 11 / MIUI 12.5 |
| 连接 | 无线调试，手机连电脑热点 |

### 出包

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -OutName 'DraftGuard-2.29.0.apk' -VersionName '2.29.0'
```

版本号在构建时**写进清单**（因为 aapt2 忽略参数），并在构建后断言产物版本，不符即失败。

### 跑测试（不需要设备）

```powershell
$aj='<sdk>\platforms\android-36\android.jar'
$lib='<sdk>\build-tools\36.1.0\core-lambda-stubs.jar'
# 分段与占位判定（47 项）需要 Burst/PlainText/SendBoundary
# 存储层（22 项）只需 LogStore/Record/Json
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$aj;$lib" -classpath $aj -nowarn -d build/t <源码...> test/BurstTest.java
java -cp "build/t;$aj" com.draftguard.BurstTest
```

### 辅助工具（`tools/`）

| 用途 | 脚本 |
|---|---|
| 方法级精确改写 Java（带断言，避免静默失败） | `patch_java.py` |
| 诊断字段防丢失检查（CI 用） | `check_diag_fields.py` |
| 把导出数据合并成单个可读文件 | `merge_export.py` |
| 复现/排查分段 | `analyze.py` `show_day.py` `show_versions.py` `why_not_merged.py` |
| 找可疑记录 | `scan.py` `find_splits.py` `find_similar.py` `find_same_prefix.py` |
| 检查会破坏展示的内容 | `check_display.py` |
| 跨长时间续写检查 | `check_long_gap.py` |

## 八、下一步（按价值排序）

1. **界面细化**：设置项做成真开关（已做）、诊断默认折叠（已做）；可继续做
   应用详情页用同样的卡片样式
2. **健康自检**：定期（如每小时）检查服务是否在跑，掉线时发通知提醒
3. **README 补新坑**：包可见性、`getHintText` 陷阱、MediaStore 导出、force-stop 红线
4. **CI 增加 BurstTest**：目前 CI 只跑 StoreTest + 编译 + 诊断字段守护
5. **导出 zip 加字段说明文件**：让别人拿到数据能看懂
