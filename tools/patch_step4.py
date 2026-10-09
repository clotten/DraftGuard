import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")
from patch_java import find_method

p = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
src = p.read_text(encoding="utf-8")

# 在"事件计数"段补 4 个字段，在末尾补存储层段
old = '''            sb.append("写入失败：").append(TypelogService.errors).append("\\n");
            sb.append("本次会话落盘：").append(TypelogService.written).append(" 条\\n\\n");'''
new = '''            sb.append("写入失败：").append(TypelogService.errors).append("\\n");
            sb.append("本次会话落盘：").append(TypelogService.written).append(" 条\\n");
            sb.append("跳过(其它原因)：").append(TypelogService.skippedOther).append("\\n");
            sb.append("宽松判据兜底命中：").append(TypelogService.evRelaxedHit).append("\\n");
            sb.append("最近一次错误的来源包：")
              .append(TextUtils.isEmpty(TypelogService.lastSourcePkg)
                      ? "（无）" : TypelogService.lastSourcePkg).append("\\n");
            sb.append("最近一次错误：")
              .append(TextUtils.isEmpty(TypelogService.lastError)
                      ? "（无）" : TypelogService.lastError).append("\\n");
            sb.append("存储层：写入成功 ").append(LogStore.diagWrittenRows)
              .append(" 行，累计 ").append(LogStore.diagByteCount).append(" 字节")
              .append("，跳过 ").append(LogStore.diagSkippedRows).append(" 行\\n");
            sb.append("存储层最近一次写入：")
              .append(LogStore.diagLastWriteAt == 0 ? "（本次进程还没写过）"
                      : new java.util.Date(LogStore.diagLastWriteAt).toString()).append("\\n");
            sb.append("存储层最近写入的行（前 120 字）：")
              .append(TextUtils.isEmpty(LogStore.diagLastLine)
                      ? "（无）" : tail(LogStore.diagLastLine, 120)).append("\\n");
            sb.append("存储层最近一次错误：")
              .append(TextUtils.isEmpty(LogStore.diagLastError)
                      ? "（无）" : LogStore.diagLastError).append("\\n");
            sb.append("最近一次清空前的备份：")
              .append(TextUtils.isEmpty(LogStore.diagLastBackupPath)
                      ? "（本次进程还没清空过）" : LogStore.diagLastBackupPath).append("\\n\\n");'''
if old not in src:
    raise SystemExit("锚点未找到")
src = src.replace(old, new, 1)
p.write_text(src, encoding="utf-8")
print("已把 8 个缺失字段补进诊断面板")
