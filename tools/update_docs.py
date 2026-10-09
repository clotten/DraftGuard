#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""update_docs.py —— 把本轮经验补进 RETRO.md，并重写 PROJECT_STATE.md。"""
from pathlib import Path

ROOT = Path(r"E:\desktop\酒馆\tools\ziJi")

RETRO_ADD = """

---

## 十三、第三轮：最危险的一类 bug 是"假正常"

这一轮的反馈全部来自用户实际使用，而且呈现出同一个模式：
**界面显示一切正常，实际已经在丢数据。**

| 用户原话 | 实际情况 |
|---|---|
| "系统的无障碍总开关是关的，但最上面显示采集状态是开启的" | `isServiceEnabled()` 只看"是否在启用列表里"，MIUI 停用后条目仍在 → 永远显示"正在记录" |
| "我现在开启无障碍了还是会说记录停了" | 服务确实没连上，但提示让人去找一个**该机型根本不存在**的总开关 |
| "新装的话无法保持无障碍状态吗" | 实测：安装不影响；**`am force-stop` 才会清掉** |

### 1. "假正常"比"报错"危险得多

报错会让人去查；"显示正常"会让人继续用，直到需要找回内容时才发现什么都没有。
这一轮的三次反馈全是这一类。

> 教训：**凡是"我在正常工作"的界面声明，都必须能被证伪。**
> 本项目的做法是把判断依据也显示出来（诊断里的"状态探测依据"一行），
> 而不是只给一个结论。

### 2. 一个"保险起见"的动作，反复破坏用户数据

为了"确保新代码生效"，我每次都执行 `adb shell am force-stop`。
实测它的副作用：

```
accessibility_enabled    1 → 0
enabled_accessibility_services   有 → 被删除
Bound services           已绑定 → {}
```

而 `install -r` **本身就会重启进程加载新代码**，force-stop 毫无必要。
于是"每次装新版用户都要重新开无障碍"这个反复出现的现象，
根因是我的调试习惯，而用户和应用背了这个锅。

> 教训：**给调试动作列"副作用清单"，并定期质疑它是否仍然必要。**
> 一个习惯性附加动作，会在几十次迭代里持续造成损失，而且没人会怀疑它。

### 3. 给用户的指引必须在这台设备上真实存在

我根据 `accessibility_enabled=0` 判断"总开关关着"，
让用户"到无障碍页打开最上面那个总开关"——而该 MIUI 版本的**无障碍页根本没有总开关**
（那个值只是"有服务被启用"的结果标志，不是可操作的控件）。
用户回复："我的手机没有总开关呀"。

> 教训：**写给用户的每一步，都要能在他手上的设备上指出具体位置。**
> 依赖一个"应该存在"的系统控件之前，先确认它存在。

### 4. 被误报的失败（CI）

CI 连续失败，显示 `Storage tests failed`，但真正原因是：
`tools/check_diag_fields.py` 里写死了 Windows 路径 `E:\\desktop\\...`，
在 Linux runner 上直接报错退出。**与存储测试毫无关系。**

> 教训：失败信息里的"哪个步骤"不一定等于"哪里错了"。
> 排查被误报的失败，先看**日志原文**，而不是步骤名。
> 同类问题这一晚出现了四次（aapt2 静默忽略参数、install 静默失败、
> 服务跑旧 dex、这个 CI 误报），共同点都是"报告与实际不一致"。

### 5. 补丁脚本的自我伤害

用脚本按花括号配对替换方法时，我几次把**字段声明**一起塞进替换块，
造成重复定义、反复返工（`已在类中定义了变量 SVC_*`）。
还出现过"删除步骤把自己刚插入的新方法删掉"（删除找的是第一处匹配）。

> 教训：**工具脚本的替换粒度要匹配职责**——
> 替换方法就只替换方法体，字段单独加；删除要指定出现位置，不能默认第一处。
> 这些都写进了 `tools/patch_java.py` 的用法约定。

### 6. 诊断能力是"眼睛"，不能顺手删

重构记录页时把诊断内容搬进弹窗，漏掉了六段，其中
**"最近 25 条原始事件"是解决本项目大多数硬 bug 的工具**。
用户直接指出："你不要删除功能呀这样你之后也不好诊断了呢！"

现在有 `tools/check_diag_fields.py`：扫描两个类里全部 41 个可诊断字段，
要求都能在诊断面板看到，缺失即失败，并已接入 CI。

> 教训：重构时"删除旧实现"必须**逐段对照旧代码迁移**，不能凭记忆写。

---

## 十四、这一轮的数字

| | |
|---|---|
| 提交 | 51 个 |
| 版本 | 2.27.1（三个页面：记录 / 应用 / 工具） |
| 离线测试 | 69 项（Burst 47 + 存储 22） |
| 诊断字段 | 41 个，全部可见且有 CI 守护 |
| 修掉的 bug | 本轮 12 个，其中 3 个属于"假正常" |

**三天下来的总规律：**

1. 第一轮 —— 我**理解错**了（对 API 行为的假设不成立）
2. 第二轮 —— 工具**静默失效**（改了没生效、装了没生效、参数被忽略）
3. 第三轮 —— 界面**假正常**（显示在工作，实际在丢数据）

这三类的共同解法只有一条：**让系统自己说出真相**——
原始事件日志、状态探测依据、产物版本断言、离线可复现的分析脚本。
凡是"我以为"的地方，都要有一个能把它证伪的观测点。
"""

PROJECT_STATE = """# DraftGuard · 项目状态

> 最后更新：本轮开发结束时　当前版本：**v2.27.1（versionCode 22701）**
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
| `test/BurstTest.java` | 47 | 分段判据、占位识别、提交边界、草稿续写 |
| `test/StoreTest.java` | 22 | 写入/读回/搜索/索引/分钟字段/重启 |

合计 **69 项离线断言**，跑在普通 JVM 上，不需要设备。CI 另含全量源码编译
与诊断字段守护。

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
| Android SDK | `D:\\android-sdk_r24.4.1-windows\\android-sdk-windows` |
| build-tools | `36.1.0`（注意：该版本 aapt2 **静默忽略** `--version-code/--version-name`） |
| 平台包 | `android-36` |
| JDK | 17 |
| 手机 | Redmi Note 10（M2103K19C）/ Android 11 / MIUI 12.5 |
| 连接 | 无线调试，手机连电脑热点 |

### 出包

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -OutName 'DraftGuard-2.27.1.apk' -VersionName '2.27.1'
```

版本号在构建时**写进清单**（因为 aapt2 忽略参数），并在构建后断言产物版本，不符即失败。

### 跑测试（不需要设备）

```powershell
$aj='<sdk>\\platforms\\android-36\\android.jar'
$lib='<sdk>\\build-tools\\36.1.0\\core-lambda-stubs.jar'
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
"""


def main() -> None:
    retro = ROOT / "docs" / "RETRO.md"
    text = retro.read_text(encoding="utf-8")
    if "十三、第三轮" not in text:
        retro.write_text(text.rstrip() + RETRO_ADD, encoding="utf-8")
        print("  ✓ RETRO.md：新增第十三、十四节（第三轮经验与总规律）")
    else:
        print("  · RETRO.md 已包含第三轮")

    state = ROOT / "PROJECT_STATE.md"
    state.write_text(PROJECT_STATE, encoding="utf-8")
    print("  ✓ PROJECT_STATE.md：重写至 v2.27.1（三页结构 / 69 项测试 / 红线清单）")


if __name__ == "__main__":
    main()
