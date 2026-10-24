# 断舍离：把 JSONL 存储层彻底去掉，只留 SQLite

> 状态：**方案（尚未实施）**
> 背景：v2.37–2.39 期间引入 SQLite 索引，采用"JSONL 权威 + SQLite 索引"双路径。
> 双路径连续咬了三次，决定按用户要求断舍离。

---

## 一、为什么必须去掉（都是实际发生的 bug）

| # | bug | 后果 |
|---|---|---|
| 1 | `SqlIndex` 每个 `LogStore` 实例都新开一个连接 | `database is locked` → 构造异常 → **静默降级回 JSONL**，优化全部失效（「全部时间」还是 2.6 秒），而且界面上完全看不出来 |
| 2 | 迁移时 `Row` 没有应用显示名 | `app_label` 全空 → 应用页**退回显示包名**（`com.tencent.mobileqq` 而不是「QQ」），要再加一次 `backfillLabels()` 才修回来 |
| 3 | `appsSummary` 的 SQL 少一个空格 | `where` 为空时拼成 `rowsGROUP` → 语法错误 → 被 catch → **静默回退到慢的全扫**，而且统计数字是错的（QQ 4688 条/11 天，实际 5979/17 天） |

三次的共同点：**同一份数据有两个真相（文件 / 数据库），任何一处不同步都表现为"看起来正常"。**

---

## 二、两条硬约束（决定方案形态）

### 约束 1：SQLite 在普通 JVM 上跑不起来

`android.database.sqlite` 在 `android.jar` 里只有空桩。因此：

- **CI 与离线测试无法再覆盖存储层**（现有 `test/StoreTest.java` 的 22 项断言测的是文件读写，会全部失效）
- 这不是"可以忽略"的成本：本项目最有价值的工程习惯就是"84 项断言跑在普通 JVM 上"

**对策（已实测可行，见下方"补充：CI 其实能测数据库"）**：
1. 把 `StoreTest` 换成 `SqlTest` —— 用 **sqlite-jdbc** 在普通 JVM 上跑**同一份 SQL**（建表、读一天、
   按应用统计、搜索、删除、以及"SQL 拼串"这类断言），保留离线可测性
2. 保留 `JsonTest`（序列化层的转义/反转义）
3. 新增探针命令 `verify`，在真机上做**完整性校验**（见第五节），补上"驱动层差异"抓不到的那部分
4. 在 CI 里保留 `BurstTest`（62 项，纯逻辑）

### 补充：CI 其实能测数据库（2026-10 实测）

我一开始断言"CI 无法覆盖存储层"是**错的**。实测结论：

| 方案 | 依赖 | CI 耗时 | 说明 |
|---|---|---|---|
| **sqlite-jdbc（推荐）** | `sqlite-jdbc` + `slf4j-api` + `slf4j-nop`（13MB + 67KB + 5KB） | 几秒 | 同一个 SQLite 引擎，纯 `javac`/`java`，不需要 Gradle |
| Robolectric | 约 100MB 传递依赖 | 30s+ | Android 框架级，类路径繁琐 |
| CI 跑模拟器 | 无 | 5–15 分钟 | 最真实，但慢 |
| 不测 + 只上真机 verify | 无 | 0 | 没有 CI 覆盖 |

实测脚本（已在普通 JVM 上跑通，9 项断言全过）验证了：

```
建表 + 4 个索引        OK
插入（中文/换行/引号）  OK
读某一天               OK
按应用统计             OK
搜索（含换行引号）      OK
"SQL 少一个空格" → 报错 OK   ← 今天那个 bug 会被测试抓住
删除单条               OK
```

> **代价**：生产用 `android.database.sqlite`，测试用 JDBC —— 引擎相同、**驱动层不同**。
> 所以 `ContentValues` 用法、`Cursor` 边界这类"驱动特有"的问题测不到，
> 仍需真机 `verify` 兜底。

**对代码结构的要求**：把所有 SQL 字符串集中成常量（`SqlIndex` 里的 `static final String`），
测试直接引用同一份常量，避免"测试里的 SQL 和生产里的 SQL 不是同一句"。


### 约束 2：导出格式不能变

`tools/merge_export.py`、`tools/analyze.py`、`提取整段.py` 等分析脚本都读 **JSONL zip**。
所以 **JSONL 作为"导出格式"必须保留**，只是不再作为"存储格式"。

---

## 三、工作量清单（6 个文件）

### 1. `LogStore.java` —— 改为纯 SQLite（重写，约 350 行）

保留全部公开方法（调用方不用改）：

```
append(Record)            readDay(day, max)        days()
appsSummary(maxDays)      search(day,q,limit,out)  readNewest(fromMs, limit)
readApp(app, fromMs, l)   appDays(app)             readAppDay(app, day)
countAll()                totalBytes()             deleteRange(...)
clearAll()                closeAll()               labels(day)
putAppLabel(day,app,label) isSkippedPackage(pkg)   root()
```

语义变化：
- `append` → 只写 SQLite（**不再写 JSONL**）
- `readDay` / `days` / `search` / ... → 只查 SQLite（**删掉"查不到就回退读文件"**的分支）
- `labels(day)` / `fileInventory(day,n)` → 改成 SQL（`SELECT app, MAX(app_label) ... GROUP BY app`）
- `root()` → 返回应用私有目录（数据库所在目录），仅用于日志/导出命名
- 若索引不可用 → 返回空 + `backend = "SQLite 不可用"`，**不再假装有数据**

删除的代码：所有 `RandomAccessFile` 句柄管理、`readFile`/`group` 正则解析、`dayIndexCache`、`writeIndex`、`deleteTree`、`cleanupOldDays` 的文件遍历（保留按天数删库的逻辑）。

### 2. `Exporter.java` —— 从数据库生成 zip

现在是 `writeZip(File logsRoot, OutputStream)` 直接打包目录。改为：

```java
/** 从数据库生成 jsonl 结构的 zip；返回条目数 */
static int writeZipFromStore(LogStore store, OutputStream os)
```

实现：`for (day : store.days()) for (app : store.labels(day)) { 逐行 Json.line(...) }`
保持 `<day>/<app>.jsonl` 的目录结构不变，分析脚本零改动。

对外方法（`toPublicDownloads` / `toCache` / `backupToPublicDownloads`）签名改成收 `LogStore` 而不是 `File logsRoot`。

### 3. `ProbeReceiver.java`

- `fileInventory(today, 120)` → 改用新的 SQL 版（签名不变，内部换）
- `Exporter.backupToPublicDownloads(context, store.root(), ...)` → 改传 `store`
- **新增 `verify` 命令**（见第五节）

### 4. `TypelogService.java`

- `store.rebuildIndexIfEmpty()` / `backfillLabels()` → 合并成一个 `migrateFromJsonlIfNeeded()`
- `store.root()` 仅剩日志用途，保留
- 迁移完成后**删除旧的 JSONL 日期目录**（断舍离的本体）

### 5. 测试：`StoreTest` → `SqlTest` + `JsonTest`

**`test/SqlTest.java`（新，用 sqlite-jdbc 跑真 SQLite）**

| 断言 | 内容 |
|---|---|
| 建表 | schema + 4 个索引都能建 |
| 写入/读回 | 中文、换行、引号、表情往返一致 |
| 读某一天 | `readDay` 的 SQL 正确 |
| 按应用统计 | `appsSummary` 的 GROUP BY / COUNT 正确 |
| 搜索 | LIKE 命中；`%` `_` 通配符不误伤 |
| 删除 | `deleteRange` 只删匹配行 |
| SQL 拼串 | `where` 为空/非空两种情况下 SQL 都合法（**今天那个空格的 bug**） |

**`test/JsonTest.java`（改造自 StoreTest，纯字符串）**

| 断言 | 内容 |
|---|---|
| `Json.line` | 换行 / 引号 / 反斜杠 / 表情 / 制表符 的转义 |
| `LogStore.unescape` | 与上面互逆 |
| `Record` 默认值 | `ev`、`comp`、`delta` 的默认状态 |

CI 加载三个 jar 的方式：`tools/fetch-test-jars.ps1`（提交脚本，不提交 jar），
下载到 `build/jars/`；`build/` 已在 `.gitignore` 里。

### 6. CI（`.github/workflows/test.yml`）

`Run storage tests` 步骤改为 `Run json tests`（跑 `JsonTest` + `BurstTest`），并在提交信息/文档里写明：
> 存储层无法在 CI 覆盖，改为设备端 `verify` 探针 + 量化校验（见 docs/SQLITE-ONLY.md 第五节）

---

## 四、迁移步骤（一次性，必须在真机上验证）

```
1. 启动服务 → 检测 draftguard.db 是否为空
2. 空 → 从 JSONL 导入全部行（已有 rebuildIndexIfEmpty 的逻辑，保留）
3. 导入后校验：数据库行数 == JSONL 行数（按天、按应用逐项比）
4. 校验通过 → 删除 draftguard/<日期>/ 目录（只删 jsonl 和 index.json）
5. 校验失败 → 不删任何东西，写诊断 + 通知用户
```

**删除前必须先有备份**：实现里先调 `backupAll()` 生成 zip（从数据库生成，此时数据已在库里），
确认 zip 生成成功后再删目录。

---

## 五、设备端校验（取代原来的 22 项单元测试）

`ProbeReceiver` 新增命令：

```bash
adb shell am broadcast -n com.draftguard/.PROBE \
  -a com.draftguard.PROBE --es cmd verify
```

输出若干条断言，例如：

```
verify: rows=21108  days=17  distinct_app=23
verify: 空文本行=795（应等于 send 边界数）
verify: 有 ts 但无 day 的行=0        (期望 0)
verify: day 与 ts 前 10 位不一致的行=0 (期望 0)
verify: 重复 (ts,app,field) 的行=0    (期望 0)
verify: app_label 为空的行=0          (期望 0)
verify: 最早 ts=2026-10-08T23:42:57  最新 ts=2026-10-24T21:33:xx
verify: OK
```

这份输出就是"存储层健康检查"，可以随时在真机上重跑。

---

## 六、实施顺序（建议按此提交）

1. `refactor: LogStore 改为纯 SQLite（去掉 JSONL 读写路径）`
2. `feat: 导出改为从数据库生成 zip（JSONL 仅作导出格式）`
3. `feat: 迁移后删除 JSONL 目录 + 探针 verify 命令`
4. `test: StoreTest -> JsonTest（序列化层）+ CI 步骤改名`
5. `docs: 更新 ARCHITECTURE / TROUBLESHOOTING 说明"为什么没有存储层单测"`

每一步都要：`build-apk.ps1` 构建 → 真机安装 → `verify` 探针 → 比对行数。

---

## 七、回滚方案

- 代码：`git revert` 到当前提交（`77 个提交`，即 2.39.2）即可，JSONL 路径当时还在
- 数据：PC 上已有 `DraftGuard-备份/DraftGuard-20261024-2146.zip`（21,109 个版本，SHA256 已记录）
  和 App 内 `backupAll()` 生成的 zip

---

## 八、一句话

> 双路径的代价不在于"多写一份"，而在于**任何一处不一致都表现为"看起来正常"**。
> 断舍离之后只有一个真相：**SQLite**。JSONL 退化成导出格式，不再参与运行时。
