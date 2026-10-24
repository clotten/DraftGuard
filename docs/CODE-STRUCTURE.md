# 代码结构规范化：为什么"拆包"不划算，以及真正该拆的是什么

> 状态：**✅ 已实施**（v3.5.x，2026-10-24）：11 步拆分完成，MainActivity 2294 → 871
> 记录时间：2026-10

---

## 一、先说结论

| 想做的事 | 结论 | 原因 |
|---|---|---|
| 拆成 `data/` `capture/` `ui/` 子包 | **不划算，不做** | 见第二节 |
| 拆分 `MainActivity`（2400 行） | **值得做** | 见第三节 |
| 拆分 `LogStore`（970 行） | 可以考虑 | SQL 已提成常量，拆成 `Schema` + `Dao` 很自然 |
| 拆分 `TypelogService`（1500 行） | 值得做，但要小心 | 状态机与 IO 可以分开 |

---

## 二、为什么"拆包"是负收益

### 现状

```
com.draftguard/          15 个类，全部包内可见（package-private）
  LogStore.java          970 行
  TypelogService.java   1500 行
  MainActivity.java     2400 行
  Burst.java / Json.java / Record.java / PlainText.java / ...
```

### 拆包要付的代价

1. **所有跨包访问的成员必须改成 `public`**
   实测：`LogStore` 有 666 处非 public 成员、`Burst` 377 处。
   拆包等于**主动放弃包级封装** —— 本类之外任何包都能调，改一个内部方法都要担心外部依赖。

2. **AndroidManifest.xml 里 5 个组件名要同步改**
   `.MainActivity` / `.TypelogService` / `.KeepAliveService` / `.ProbeReceiver` / `.LogFileProvider`
   改错一个 = 应用启动崩或无障碍服务起不来。

3. **所有文档和命令行要同步改**
   `adb shell am broadcast -n com.draftguard/.ProbeReceiver ...`
   会变成 `com.draftguard.capture/.ProbeReceiver` —— 这类"复制粘贴即用"的命令一旦失效，
   排查成本立刻上升（本项目最依赖的就是这些命令）。

4. **CI 的守护脚本要改扫描路径**
   `tools/check_diag_fields.py` 按路径找源文件。

5. **换来的收益**：目录层级好看一点。

### 判断

> 对一个 **15 个类、紧耦合、单一用途**的模块，包内可见是**刻意的设计**而不是"不规范"。
> 拆包的唯一收益是视觉，而代价是封装 + 3 处外部契约。**这买卖不划算。**

**什么时候值得拆包**：类数超过 ~40、或者出现明确的复用边界（比如要发布一个独立的库）。
到那时再拆。

---

## 三、真正该拆的：过长的文件

### 问题

| 文件 | 行数 | 症状 |
|---|---|---|
| `MainActivity.java` | 2400 | 三个页面的 UI、渲染、线程、对话框全在一个类里 |
| `TypelogService.java` | 1500 | 事件处理、去抖、写入、诊断全在一起 |
| `LogStore.java` | 970 | schema + SQL + 导出 + 迁移 |

单文件过长的实际代价（本项目已经吃到）：
- 改一个页面的渲染会动到另一个页面的邻近代码，容易误伤
- 合并冲突面大
- 想单测某个逻辑时发现它绑定在 Activity 生命周期上

### 建议的拆法（**同一包内**，不需要 public）

#### 1. `MainActivity` → 4 个类

```
MainActivity      只保留：onCreate、三个页面的切换、refreshList 的调度
RecordsPage       记录页：搜索框 + 范围按钮 + ListView + 删除回调
AppsPage          应用页：应用列表 + 按天翻页详情
ToolsPage         工具页：状态卡片 + 可折叠设置 + 可折叠诊断
```

**拆分方式**：不是改成 `Fragment`（那会引入 androidx），而是普通的
"页面对象持有 `View` 根节点 + `Context`"：

```java
final class RecordsPage {
    private final MainActivity host;   // 需要 Context / runOnUiThread 时用
    private final View root;
    RecordsPage(MainActivity host, ViewGroup parent) { ... }
    void refresh() { ... }
}
```

好处：**可见性不变**（同包），`MainActivity` 从 2400 行降到 ~300 行，每页独立可读。

#### 2. `LogStore` → 2 个类（可选）

```
LogStore.Schema   所有 SQL 常量（已经是 static final，直接搬）
LogStore          连接、读写、导出、迁移
```

`Schema` 单独出来后，`SqlTest` 只依赖它，不必依赖整个存储类。

#### 3. `TypelogService` → 2 个类（谨慎）

```
TypelogService    无障碍回调 + 事件分发
CaptureState      去抖、挂起版本、写入队列（纯逻辑，可单测）
```

`CaptureState` 不碰 Android API 的话**可以进 CI 单测** —— 这是最大的收益。

---

## 四、实施顺序（每步都要构建 + 真机验证）

1. `LogStore` 拆出 `SqlSchema`（最小、最安全，只搬常量）
2. `MainActivity` 拆出 `ToolsPage`（诊断/设置是独立的一块，耦合最少）
3. `MainActivity` 拆出 `AppsPage`
4. `MainActivity` 拆出 `RecordsPage`
5. （可选）`TypelogService` 拆出可测的 `CaptureState`

每步都跑：`build-apk.ps1` → 真机安装 → 记录页/应用页/工具页各看一眼 → `SqlTest` + `BurstTest`。

---

## 五、不做的事（明确排除）

- ❌ 拆子包（`data/` `capture/` `ui/`）
- ❌ 引入 androidx / Fragment / ViewModel（本项目刻意的约束：无 Gradle、无依赖）
- ❌ 引入注解处理器、依赖注入框架

> 保持"一个包 + 少量长文件"比"多包 + 全 public"更符合这个项目的规模。
