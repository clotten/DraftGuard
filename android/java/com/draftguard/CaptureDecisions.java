package com.draftguard;

/**
 * 采集层的**纯决策函数**：什么该记、什么不该记。
 *
 * <h3>为什么单独成类</h3>
 * 这些判断原先散落在 {@link TypelogService#handleText} 和 {@link TypelogService#commit}
 * 里，参数是私有的 {@code Pending}，因此**无法离线单测** ——
 * 而项目里最难查的几个 bug 恰好都出在这里：
 * <ul>
 *   <li>删除到空时把残渣（单个「Y」「A」）当成草稿记下来</li>
 *   <li>输入法补发"内容相同"的事件，把"上一步在变短"的趋势判断覆盖掉</li>
 *   <li>重复粘贴被同分钟去重全部吃掉（8 次粘贴只记 2 次）</li>
 * </ul>
 * 抽成纯函数后，这些场景都能写成断言（见 {@code test/DecisionsTest.java}）。
 *
 * <h3>纪律</h3>
 * 本类**不许**碰 Android API、不许读写状态、不许记日志。
 * 只做"输入 → 布尔"的判定，这样它才能在普通 JVM 上跑。
 */
final class CaptureDecisions {

    private CaptureDecisions() {
    }

    /**
     * 是不是"清空之后留下的截断碎片"。
     *
     * <h4>场景</h4>
     * 用户逐个删除：「JT5514607829298」→「JT5」→「J」→ 空。
     * 输入法在每次删除后会发出事件；等到内容真的变空时，某些输入法又会补发
     * 一个中间态（比如「J」），这个中间态就是碎片 —— 它不是用户新写的草稿。
     *
     * <h4>判据</h4>
     * 不需要区分"逐个删除"还是"按钮清空"（这是用户的疑问，也是设计上的关键）：
     * <ul>
     *   <li>此刻输入框是空的（{@code charsBefore == 0}）—— 只有刚被清空才可能产生碎片</li>
     *   <li>新出现的这段是"刚才那段"的前缀截断（{@code before.startsWith(text)}）</li>
     * </ul>
     * 两条都满足才丢。都是内容层面的判据，与操作方式无关。
     *
     * @param charsBefore       清空之前那一版的字数（本判据要求为 0）
     * @param lastNonEmptyText  最近一次的非空文本
     * @param text              现在出现的这段
     */
    static boolean isDeletionFragment(int charsBefore, String lastNonEmptyText, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (charsBefore != 0) {
            return false;       // 只有"刚被清空"之后才可能是碎片
        }
        return lastNonEmptyText != null
                && lastNonEmptyText.length() > text.length()
                && lastNonEmptyText.startsWith(text);
    }

    /**
     * 同一分钟、内容完全没变 → 不重复落盘。
     *
     * <h4>为什么需要它</h4>
     * 轮询兜底每 900ms 会重读一次输入框，不去重就会把同一段文字反复记录。
     *
     * <h4>为什么只能是"收窄"而不是"移除"</h4>
     * 曾经为了修"重复粘贴不记录"把这条规则整个作废，
     * 结果一个快递单号写出 **53 个版本** —— 因为它原本在挡轮询。
     * 正解是收窄：只有"文本真的一模一样 + 同一分钟"才去重。
     */
    static boolean isSameMinuteDedup(String text, String minute,
                                     String lastWriteText, String lastWriteMinute) {
        if (text == null || minute == null) {
            return false;
        }
        return text.equals(lastWriteText) && minute.equals(lastWriteMinute);
    }

    /**
     * 这一次变化是不是"变短了"（删除行为）。
     *
     * <p><b>注意</b>：本函数只回答"长度上是不是变短"，
     * 是否要用它更新"趋势"由调用方决定 —— 因为输入法会补发**内容相同**的事件，
     * 那种事件不应该改变趋势（见 {@link #shouldUpdateTrend}）。
     */
    static boolean isShrink(int charsBefore, String text) {
        return text != null && charsBefore > 0 && text.length() < charsBefore;
    }

    /**
     * 要不要用这一次的"变短/变长"更新趋势状态。
     *
     * <h4>为什么需要这条</h4>
     * 输入法每次删除会**先补发一个"内容与上次相同"的事件**。
     * 如果那个事件也更新趋势，就会把"上一步在变短"覆盖成 false，
     * 紧接着的清空就被误判成"发送/按钮清空"，于是删除残渣被当成草稿救了 ——
     * 这就是「删完只留下一个 Y」那个 bug 的根因。
     *
     * <p>规则：<b>内容没变的重复事件，不参与趋势判断。</b>
     */
    static boolean shouldUpdateTrend(String text, String prevText) {
        return text != null && !text.equals(prevText);
    }
}
