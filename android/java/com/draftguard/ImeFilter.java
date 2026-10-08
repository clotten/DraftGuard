package com.draftguard;

import android.content.Context;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 判断某个包名是不是"输入法键盘自己"。
 *
 * 为什么要单独做这件事：输入法（例如讯飞、搜狗、Gboard）会以**自己的包名**向无障碍服务
 * 发送大量事件（候选栏、键盘按键回显）。这些不是"用户在输入框里打出的字"，必须排除，
 * 否则记录会被键盘文字污染 —— 而且它们会把真正有用的应用事件淹掉。
 *
 * 这里用系统 API 取已安装输入法列表来判断，而不是硬编码包名：换输入法也能正确识别。
 * 结果缓存 60 秒，避免频繁查询系统服务。
 */
final class ImeFilter {

    private static long lastQuery;
    private static Set<String> cached = new HashSet<>();

    private ImeFilter() {
    }

    static boolean isIme(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastQuery > 60_000L) {
            lastQuery = now;
            cached = query(ctx);
        }
        return cached.contains(pkg);
    }

    private static Set<String> query(Context ctx) {
        Set<String> out = new HashSet<>();
        try {
            InputMethodManager imm =
                    (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                return out;
            }
            List<InputMethodInfo> list = imm.getInputMethodList();
            if (list == null) {
                return out;
            }
            for (InputMethodInfo info : list) {
                try {
                    if (info.getPackageName() == null) {
                        continue;
                    }
                    // 只排除"键盘类"输入法；手写/语音等也一并算进来更安全
                    out.add(info.getPackageName());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
