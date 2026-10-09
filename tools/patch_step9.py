import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")

p = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
src = p.read_text(encoding="utf-8")

# 1) 原始事件区：区分"服务没跑 / 日志没开 / 开了但还没事件"
old = '''        sb.append("\\n最近 25 条原始事件（需在设置里开诊断日志）：\\n");
        java.util.List<String> diag = TypelogService.DIAG;
        synchronized (diag) {
            if (diag.isEmpty()) {
                sb.append("（诊断日志未开启）\\n");
            } else {
                int from = Math.max(0, diag.size() - 25);
                for (int i = from; i < diag.size(); i++) {
                    sb.append("· ").append(diag.get(i)).append("\\n");
                }
            }
        }'''
new = '''        // 原始事件是排查问题的关键证据，所以这里要把"为什么没有"说清楚：
        // 以前只要缓冲区为空就写"诊断日志未开启"，但空有三种完全不同的原因
        // （服务没在跑 / 日志开关没开 / 开了但还没收到事件），
        // 一律说"未开启"会把人引到错误方向。
        sb.append("\\n最近 25 条原始事件：\\n");
        sb.append("诊断日志开关：").append(Prefs.debug(this) ? "已开启" : "未开启")
          .append("　无障碍服务：").append(TypelogService.running ? "已连接" : "未连接").append("\\n");
        java.util.List<String> diag = TypelogService.DIAG;
        synchronized (diag) {
            if (!diag.isEmpty()) {
                int from = Math.max(0, diag.size() - 25);
                for (int i = from; i < diag.size(); i++) {
                    sb.append("· ").append(diag.get(i)).append("\\n");
                }
            } else if (!Prefs.debug(this)) {
                sb.append("（诊断日志未开启：到「设置」里打开「诊断日志」）\\n");
            } else if (!TypelogService.running) {
                sb.append("（无障碍服务未连接，收不到任何事件："
                        + "到 系统设置 → 无障碍 → DraftGuard 重新打开）\\n");
            } else {
                sb.append("（已开启，但本次会话还没收到事件："
                        + "去任意 App 打几个字，或切一下应用就会出现在这里）\\n");
            }
        }'''
if old not in src:
    raise SystemExit("原始事件区锚点未找到")
src = src.replace(old, new, 1)
print("  ✓ 原始事件区：区分三种'没有事件'的原因")

# 2) 采集状态卡：服务未连接时给出明确指引
old2 = '''        setCardText(statusView, "采集状态",
                (isServiceEnabled() ? "● 已开启，正在记录" : "○ 未开启")'''
new2 = '''        setCardText(statusView, "采集状态",
                (isServiceEnabled() ? "● 已开启，正在记录"
                        : "○ 未开启 —— 现在不会记录任何内容\\n"
                          + "   到 系统设置 → 无障碍 → DraftGuard 重新打开")'''
if old2 not in src:
    raise SystemExit("采集状态卡锚点未找到")
src = src.replace(old2, new2, 1)
print("  ✓ 采集状态卡：未连接时给出明确指引")

p.write_text(src, encoding="utf-8")
print("已写入")
