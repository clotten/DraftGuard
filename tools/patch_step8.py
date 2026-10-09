import sys
from pathlib import Path
sys.path.insert(0, r"E:\desktop\酒馆\tools\ziJi\tools")
from patch_java import replace_method

p = Path(r"E:\desktop\酒馆\tools\ziJi\android\java\com\draftguard\MainActivity.java")
src = p.read_text(encoding="utf-8")

# ── 1) buildDiagBox：展开时立刻填充（原来只建了"读取中…"，填充留在 refreshTools 里）
old = '''        diagBox.addView(collapsedHint("排查问题用。内容较长，往下滚即可。"));
        diagView = cardView("事件与存储", "读取中…");
        diagBox.addView(diagView);
    }'''
new = '''        diagBox.addView(collapsedHint("排查问题用。内容较长，往下滚即可。"));
        diagView = cardView("事件与存储", "读取中…");
        diagBox.addView(diagView);
        // 展开后**立刻**填充。
        // 踩过的坑：填充原先只写在 refreshTools() 里，而它仅在切页或事件到来时执行，
        // 于是展开诊断后一直停在"读取中…"，要等下次有事件才显示。
        new Thread(() -> {
            final String d = diagText().toString();
            ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag").start();
    }'''
if old not in src:
    raise SystemExit("buildDiagBox 锚点未找到")
src = src.replace(old, new, 1)
print("  ✓ buildDiagBox：展开即刻填充")

# ── 2) refreshTools：去掉重复的诊断线程（改由 buildDiagBox 负责）
old2 = '''        new Thread(() -> {
            final String d = diagText().toString();
            ui.post(() -> {
                if (diagView != null) {
                    setCardText(diagView, "事件与存储", d);
                }
            });
        }, "typelog-diag").start();
    }

    /**
     * 诊断正文。'''
new2 = '''        // 诊断区的填充放在 buildDiagBox() 里：它决定"是否展开、是否已创建视图"，
        // 放在这里会出现"没展开也去读盘"以及"展开了却没人填"两种毛病。
    }

    /**
     * 诊断正文。'''
if old2 not in src:
    raise SystemExit("refreshTools 诊断线程锚点未找到")
src = src.replace(old2, new2, 1)
print("  ✓ refreshTools：移除重复诊断线程")

# ── 3) chooserRow：两个"异步弹窗"的项要在选完之后才重建
old3 = '''        row.setOnClickListener(v -> {
            applySetting(which);
            buildSettingsBox();      // 值可能变了，重建这一块
        });'''
new3 = '''        row.setOnClickListener(v -> {
            // 最少字数与保留天数是**弹窗里选**，选完才异步生效 ——
            // 若在这里立刻重建，拿到的是旧值（表现就是"改完不刷新，要再点一次才变"）。
            // 所以把重建交给选择完成后的回调。
            if (which == 4) {
                chooseMinChars(() -> buildSettingsBox());
            } else if (which == 5) {
                chooseRetention(() -> buildSettingsBox());
            } else {
                applySetting(which);
                buildSettingsBox();
            }
        });'''
if old3 not in src:
    raise SystemExit("chooserRow 锚点未找到")
src = src.replace(old3, new3, 1)
print("  ✓ chooserRow：异步选择完成后再重建")

# ── 4) chooseMinChars / chooseRetention 支持回调
src = replace_method(src, "private void chooseMinChars() {", '''private void chooseMinChars() {
        chooseMinChars(null);
    }

    /** @param after 选完之后的回调（用于刷新设置列表里的当前值） */
    private void chooseMinChars(final Runnable after) {
        final int[] opts = {1, 2, 3, 5};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " 字" + (opts[i] == 1 ? "（不设门槛，单字符也记）" : "");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("新建输入框至少几字才记录")
                .setItems(labels, (d, which) -> {
                    Prefs.setMinChars(this, opts[which]);
                    toast("已设为 " + opts[which] + " 字");
                    if (after != null) {
                        after.run();
                    }
                })
                .show();
    }''')
print("  ✓ chooseMinChars 支持回调")

src = replace_method(src, "private void chooseRetention() {", '''private void chooseRetention() {
        chooseRetention(null);
    }

    /** @param after 选完之后的回调（用于刷新设置列表里的当前值） */
    private void chooseRetention(final Runnable after) {
        final int[] opts = {7, 30, 90, 365};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " 天";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("记录保留多少天")
                .setItems(labels, (d, which) -> {
                    Prefs.setRetentionDays(this, opts[which]);
                    toast("已设为 " + opts[which] + " 天");
                    if (after != null) {
                        after.run();
                    }
                })
                .show();
    }''')
print("  ✓ chooseRetention 支持回调")

p.write_text(src, encoding="utf-8")
print("已写入")
