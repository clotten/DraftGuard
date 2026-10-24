package com.draftguard;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍节点树的扫描工具（**只做诊断，不参与记录**）。
 *
 * <h3>用途</h3>
 * 当"某个应用记不到字"时，需要知道到底是：
 * <ul>
 *   <li>活动窗口根本拿不到（输入法窗口 / 系统限制）</li>
 *   <li>拿到了窗口，但没有输入焦点</li>
 *   <li>有焦点，但控件不是可编辑的</li>
 *   <li>一切正常（那就是分段/过滤环节的问题）</li>
 * </ul>
 * 输出会进诊断面板的"最近一次扫描情况"。
 *
 * <h3>为什么单独成类</h3>
 * 它是纯读操作（不写状态、不落盘），与"记录流程"无关。
 * 混在 {@link TypelogService} 里时，改记录逻辑容易顺手碰坏它，
 * 而它恰恰是记录失败时唯一的现场证据。
 */
final class NodeScanner {

    /** 递归深度上限：防止个别应用给出异常深的树把主线程拖死 */
    private static final int MAX_DEPTH = 12;

    private NodeScanner() {
    }

    /**
     * 扫描当前活动窗口，返回一行人可读的结论。
     *
     * @param service 无障碍服务（用来拿 activity window 的根节点）
     * @param pkg     当前前台包名，仅用于输出
     */
    static String scan(AccessibilityService service, String pkg) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("包名 ").append(pkg).append("；");
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                sb.append("活动窗口拿不到（可能是输入法窗口或系统限制）");
                return sb.toString();
            }
            sb.append("根节点 ").append(root.getClassName()).append("；");
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            sb.append("输入焦点=").append(f == null ? "无"
                    : f.getClassName() + (f.isEditable() ? "(可编辑)" : "(不可编辑)"));
            int[] editables = new int[]{0};
            int[] nodes = new int[]{0};
            countTree(root, 0, nodes, editables);
            sb.append("；窗口内控件 ").append(nodes[0])
                    .append(" 个，其中可编辑 ").append(editables[0]).append(" 个");
        } catch (Throwable t) {
            sb.append("扫描异常：").append(t);
        }
        return sb.toString();
    }

    /** 统计子树里的控件数与可编辑控件数（写进数组是为了免去装箱） */
    private static void countTree(AccessibilityNodeInfo node, int depth,
                                  int[] nodes, int[] editables) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        nodes[0]++;
        try {
            if (node.isEditable()) {
                editables[0]++;
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                countTree(node.getChild(i), depth + 1, nodes, editables);
            }
        } catch (Throwable ignored) {
        }
    }
}
