from pathlib import Path
p = Path(r"E:\desktop\酒馆\tools\ziJi\README.md")
c = p.read_text(encoding="utf-8")
c = c.replace("DraftGuard-2.12.2.apk", "DraftGuard-2.27.1.apk")
if "### Pages" not in c:
    old = "## Privacy"
    new = """### Pages

- **Records** — one line of status up top, a search box, three controls, then the records
  themselves. Each entry is a card with the app's icon.
- **Apps** — every app that has been recorded, with its icon, name and row count; tap to read
  that app's full history grouped by day.
- **Tools** — capture status, live preview, today's stats and the app list, plus collapsible
  **Settings** (booleans are real switches) and **Diagnostics** (all 41 internal counters,
  including the last 25 raw accessibility events).

## Privacy"""
    c = c.replace(old, new, 1)
p.write_text(c, encoding="utf-8")
print("README 已更新（三页说明 + 版本号）")
