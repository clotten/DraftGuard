import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")
from patch_java import insert_after

p = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
src = p.read_text(encoding="utf-8")
if "private TextView sectionHeader(" in src:
    print("已存在 sectionHeader")
else:
    code = '''/** 可点击的分节标题（带展开/收起箭头） */
    private TextView sectionHeader(String title, boolean expanded, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText((expanded ? "▾ " : "▸ ") + title);
        t.setTextColor(COL_FG);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(4), dp(18), dp(4), dp(8));
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }'''
    src = insert_after(src, "private void updateHeader(TextView t, String title, boolean expanded) {", code)
    p.write_text(src, encoding="utf-8")
    print("已加入 sectionHeader")
