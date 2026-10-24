package com.draftguard;

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 诊断正文的组装。
 *
 * <h3>为什么单独成类</h3>
 * <ol>
 *   <li><b>它是排查的主要手段。</b>本项目所有难查的 bug，最后都是靠"读诊断 → 看清走了
 *       哪条分支"定位的。它不该和界面代码缠在一起，否则一改界面就容易顺手删掉几行。</li>
 *   <li><b>它以前被弄丢过一次。</b>记录页重构时把诊断搬进弹窗，漏掉了六段内容
 *       （含"最近 25 条原始事件"），用户明确要求"不要删功能，否则以后不好诊断"。
 *       现在由 {@code tools/check_diag_fields.py} 在 CI 里守着：任何新增的计数器
 *       只要没出现在这里，CI 就红。</li>
 *   <li>页面与弹窗**共用这一份**文本 —— 分两处写就一定会走样。</li>
 * </ol>
 *
 * <h3>纪律</h3>
 * 这里只做"读状态 → 拼字符串"，不改任何状态、不弹窗、不碰 View。
 * 唯一的写操作是 {@code serviceState()} 之前的那次探测（由调用方传入结果）。
 */
final class Diagnostics {

    private Diagnostics() {
    }

    /** 列表加载的统计，由界面在刷新时记下，诊断里展示 */
    static final class ListStats {
        long ms;
        int days;
        int rows;
        int segmented;

        static ListStats of(long ms, int days, int rows, int segmented) {
            ListStats s = new ListStats();
            s.ms = ms;
            s.days = days;
            s.rows = rows;
            s.segmented = segmented;
            return s;
        }
    }

    /**
     * 组装诊断正文。
     *
     * @param ctx       用作 Prefs 读配置
     * @param stats     最近一次列表加载的统计
     * @param stateProbe 服务状态探测的原始依据（已经在调用方算过）
     * @param stateName  服务状态名
     */
    static StringBuilder build(Context ctx, ListStats stats, String stateProbe, String stateName) {
        LogStore store = LogStore.get(ctx.getFilesDir());
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new Date());
        Map<String, String> labels = store.labels(today);

        StringBuilder sb = new StringBuilder();
        sb.append("收到的无障碍事件总数：").append(TypelogService.evAll).append("\n");
        sb.append("其中文本变化事件：").append(TypelogService.evText).append("\n");
        sb.append("取到文本并进入记录：").append(TypelogService.evCaptured).append("\n");
        sb.append("事件里节点为空(靠兜底找回)：").append(TypelogService.evSourceNull)
                .append(" / 兜底成功 ").append(TypelogService.evTraverseHit).append("\n");
        sb.append("宽松判据兜底命中：").append(TypelogService.evRelaxedHit).append("\n");
        sb.append("节点不是输入框：").append(TypelogService.evNotEditable).append("\n");
        sb.append("跳过系统UI/不可记录包：").append(TypelogService.skippedSelf).append("\n");
        sb.append("跳过输入法键盘自身事件：").append(TypelogService.skippedIme).append("\n");
        sb.append("跳过删除操作(按设置)：").append(TypelogService.skippedDelete).append("\n");
        sb.append("跳过未聚焦的框：").append(TypelogService.skippedNoFocus).append("\n");
        sb.append("跳过占位提示/单字碎片：").append(TypelogService.skippedNoise).append("\n");
        sb.append("跳过首条文本重现(占位兜底)：").append(TypelogService.skippedRepeat).append("\n");
        sb.append("跳过同分钟去重(内容未变)：").append(TypelogService.skippedDedup).append("\n");
        sb.append("跳过清空后的删除碎片：").append(TypelogService.skippedFragment).append("\n");
        sb.append("存储后端：").append(LogStore.backend)
                .append("　索引 ").append(LogStore.indexRows).append(" 行")
                .append(LogStore.indexImported > 0
                        ? "（本次从旧 JSONL 导入 " + LogStore.indexImported + " 行）" : "")
                .append("\n");
        sb.append("列表加载耗时：").append(stats.ms).append(" ms（")
                .append(stats.days).append(" 天 / ")
                .append(stats.rows).append(" 个版本，实际分段 ")
                .append(stats.segmented).append(" 行）\n");
        sb.append("跳过(设置里排除的)：").append(TypelogService.skippedIgnored).append("\n");
        sb.append("跳过(其它原因)：").append(TypelogService.skippedOther).append("\n");
        sb.append("跳过密码框：").append(TypelogService.skippedPassword).append("\n");
        sb.append("检测到点击「发送/搜索/发布」：").append(TypelogService.sendBoundaries)
                .append(" 次（消息分段依据）\n");
        sb.append("其中由「清空」抢救落盘：").append(TypelogService.flushes).append(" 次\n");
        sb.append("写入失败：").append(TypelogService.errors).append("\n");
        sb.append("本次会话落盘：").append(TypelogService.written).append(" 条\n");
        sb.append("最近一次错误的来源包：")
                .append(TextUtils.isEmpty(TypelogService.lastSourcePkg) ? "（无）"
                        : TypelogService.lastSourcePkg).append("\n");
        sb.append("最近一次错误：")
                .append(TextUtils.isEmpty(TypelogService.lastError) ? "（无）"
                        : TypelogService.lastError).append("\n");
        sb.append("存储层：写入成功 ").append(LogStore.diagWrittenRows)
                .append(" 行，累计 ").append(LogStore.diagByteCount).append(" 字节")
                .append("，跳过 ").append(LogStore.diagSkippedRows).append(" 行\n");
        sb.append("存储层最近一次写入：")
                .append(LogStore.diagLastWriteAt == 0 ? "（本次进程还没写过）"
                        : new Date(LogStore.diagLastWriteAt).toString()).append("\n");
        sb.append("存储层最近写入的行（前 120 字）：")
                .append(TextUtils.isEmpty(LogStore.diagLastLine) ? "（无）"
                        : tail(LogStore.diagLastLine, 120)).append("\n");
        sb.append("存储层最近一次错误：")
                .append(TextUtils.isEmpty(LogStore.diagLastError) ? "（无）"
                        : LogStore.diagLastError).append("\n");
        sb.append("最近一次清空前的备份：")
                .append(TextUtils.isEmpty(LogStore.diagLastBackupPath) ? "（本次进程还没清空过）"
                        : LogStore.diagLastBackupPath).append("\n");

        sb.append("\n哪些应用发过事件（次数）：\n");
        List<Map.Entry<String, Integer>> es =
                new ArrayList<>(TypelogService.ALL_EVENT_PKGS.entrySet());
        java.util.Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
        if (es.isEmpty()) {
            sb.append("（一次都没收到）\n");
        }
        for (int i = 0; i < es.size() && i < 12; i++) {
            Map.Entry<String, Integer> e = es.get(i);
            Integer t = TypelogService.TEXT_EVENT_PKGS.get(e.getKey());
            sb.append("· ").append(name(labels, e.getKey()))
                    .append("  [").append(e.getKey()).append("]  总 ").append(e.getValue())
                    .append(" 次，其中文本变化 ").append(t == null ? 0 : t).append(" 次\n");
        }

        Integer wx = TypelogService.ALL_EVENT_PKGS.get("com.tencent.mm");
        sb.append("\n微信(com.tencent.mm)：");
        if (wx == null) {
            sb.append("一次事件都没收到 ← 系统层面没放行");
        } else {
            Integer wxt = TypelogService.TEXT_EVENT_PKGS.get("com.tencent.mm");
            sb.append("收到 ").append(wx).append(" 次事件，其中文本变化 ")
                    .append(wxt == null ? 0 : wxt).append(" 次");
        }

        sb.append("\n\n最近一次扫描情况：\n")
                .append(TextUtils.isEmpty(TypelogService.lastScanInfo) ? "（还没有扫描失败过）"
                        : TypelogService.lastScanInfo).append("\n");

        sb.append("\n磁盘上的原始文件（绕开所有缓存，最硬的证据）：\n");
        for (String line : store.fileInventory(today, 40)) {
            sb.append("· ").append(line).append("\n");
        }

        // 原始事件是排查的关键证据，所以要把"为什么没有"说清楚：
        // 缓冲区为空有三种完全不同的原因（服务没在跑 / 日志开关没开 / 开了但还没收到事件），
        // 一律说"未开启"会把人引到错误方向。
        sb.append("\n最近 25 条原始事件：\n");
        sb.append("诊断日志开关：").append(Prefs.debug(ctx) ? "已开启" : "未开启")
                .append("　无障碍服务：").append(TypelogService.running ? "已连接" : "未连接")
                .append("\n");
        sb.append("状态探测依据：").append(stateProbe)
                .append("　判定=").append(stateName).append("\n");
        List<String> diag = TypelogService.DIAG;
        synchronized (diag) {
            if (!diag.isEmpty()) {
                int from = Math.max(0, diag.size() - 25);
                for (int i = from; i < diag.size(); i++) {
                    sb.append("· ").append(diag.get(i)).append("\n");
                }
            } else if (!Prefs.debug(ctx)) {
                sb.append("（诊断日志未开启：到「设置」里打开「诊断日志」）\n");
            } else if (!TypelogService.running) {
                sb.append("（无障碍服务未连接，收不到任何事件："
                        + "到 系统设置 → 无障碍 → DraftGuard 重新打开）\n");
            } else {
                sb.append("（已开启，但本次会话还没收到事件："
                        + "去任意 App 打几个字，或切一下应用就会出现在这里）\n");
            }
        }

        if (TypelogService.skippedNoFocus > 0 && TypelogService.evCaptured == 0) {
            sb.append("\n⚠ 已跳过 ").append(TypelogService.skippedNoFocus)
                    .append(" 个未聚焦的框，且一条都没记到。\n")
                    .append("   焦点过滤可能对本机过严，把「只记录有焦点的输入框」关掉试试。\n");
        }
        return sb;
    }

    /** 应用显示名（实现在 TextUtil，保持纯函数不染 Android 依赖） */
    static String name(Map<String, String> labels, String app) {
        return TextUtil.name(labels, app);
    }

    /** 取末尾 n 个字符（实现在 TextUtil） */
    static String tail(String s, int n) {
        return TextUtil.tail(s, n);
    }
}
