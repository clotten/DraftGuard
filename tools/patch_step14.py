import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")
from patch_java import replace_method

p = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
src = p.read_text(encoding="utf-8")

# 1) 宽限期放宽到 10 秒（安装/force-stop 后重新绑定可能要 8 秒以上）
src = src.replace("    private static final long CONNECT_GRACE_MS = 6000;",
                  "    private static final long CONNECT_GRACE_MS = 10000;", 1)
print("  ✓ 宽限期 6s → 10s")

# 2) onResume：状态刷新与"弹窗提醒"分成两次调度 ——
#    只在第二次（约 22 秒）仍未连上时才提醒，避免刚重启就误报
old = """        // 宽限期之后再判断服务是否真的连上，避免刚打开就误报"已停用"
        ui.postDelayed(this::checkAndWarnService, CONNECT_GRACE_MS + 1500);"""
new = """        // 宽限期后先刷新状态显示；**再等一段**才考虑弹窗。
        // 实测：安装/force-stop 之后系统重新绑定无障碍服务可能要 8 秒以上，
        // 只查一次会在服务马上要连上时误报"记录已经停了"。
        ui.postDelayed(this::refreshServiceCard, CONNECT_GRACE_MS + 1500);
        ui.postDelayed(this::warnIfServiceStalled, 22000);"""
if old not in src:
    raise SystemExit("onResume 调度锚点未找到")
src = src.replace(old, new, 1)
print("  ✓ 改为两次确认：10s 刷状态，22s 才可能提醒")

# 3) checkAndWarnService → refreshServiceCard（只刷状态，不弹窗）
src = replace_method(src, "private void checkAndWarnService() {", '''private void refreshServiceCard() {
        if (statusView != null) {
            setCardText(statusView, "采集状态", serviceStateText());
        }
    }''')
print("  ✓ checkAndWarnService → refreshServiceCard")

p.write_text(src, encoding="utf-8")
print("已写入")
