package com.draftguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 把"逐字版本流"合并成"一段段完整的话"，用于给人看的视图。
 *
 * 背景：存储层为了防丢，会把每一次文本变化都落盘（你 → 你好 → 你好呀 → …）。
 * 原始数据必须保留 —— 崩在"你好呀"那一步，能捞回来的就是当时那一版。
 * 但**看的时候**不该显示这个增量过程，人想看的是"我最后打出来的整段话"。
 *
 * 判据为何不能用"前缀延伸"
 * ------------------------
 * 打字过程本身就是"上一版是下一版的前缀"，所以 startsWith 判不出
 * "在中间补字、替换某个字"这类修改。实测反例：
 *      "你好呀我很".startsWith("你好呀我") == true
 * （"很"插在"我"之后，新串依然以旧串开头）。
 *
 * 所以改用**偏离幅度**：
 *   · 共同开头之后的长度差很小  → 同一次输入的修修补补：合并，只留最后成型
 *   · 偏离很大（整段重写）      → 另一段话：开新段
 * 再用"间隔不超过 GAP_MS"兜住时间维度。
 */
final class Burst {

    /** 超过这个间隔就算"另一段" */
    static final long GAP_MS = 5 * 60 * 1000L;

    /**
     * "接着写未完成草稿"允许的最大间隔，比 GAP_MS 宽得多。
     *
     * 场景（用户反馈）：打完一段话 → 切出去办事 → 几分钟甚至几小时回来在后面继续打。
     * 那仍是同一段话 —— 没发送就切走的内容是**草稿**，不是已经发出的消息。
     *
     * "已发出又发了相似的一条"由"发送"信号排除：真发出去过，输入框会被清空，
     * 不会出现"新文本以旧文本开头"这种形态。
     */
    static final long LONG_GAP_MS = 6 * 60 * 60 * 1000L;

    /**
     * 共同开头占较短那一版的比例低于它，就认为是"换了一段话"。
     * 用比例而不是绝对长度差：<一二三四五六七八九十> → <完全不同的另一句话来了>
     * 长度只差 1，但共同开头只有 1 个字，明显是重写。
     */
    private static final double KEEP_RATIO = 0.5;
    /** 太短的文本（如"嗯""好"）不做重写判定，避免误拆 */
    private static final int MIN_LEN_FOR_REWRITE = 4;
    /**
     * 共同开头至少这么长，才认为"仍是同一条消息"。
     * 用来区分"改错别字"（共同开头通常很长）与"连发另一条消息"（几乎没有共同开头）。
     */
    private static final int MIN_COMMON_PREFIX = 2;
    /**
     * "短内容 + 极短间隔"视为同一个词的修改。
     *
     * 用于捕捉"整词打错后重打"：实测 13:21:43 &lt;来发展&gt; → 13:21:46 &lt;开发者&gt;，
     * 只隔 3.4 秒、中间无任何中间状态，用户在设置里找"开发者选项"打错了字。
     * 这两者共同开头为 0，靠共同开头判不出来。
     */
    private static final long RAPID_EDIT_MS = 10_000L;
    /** 判为"改一个词"的最大长度 */
    private static final int RAPID_EDIT_MAX = 8;

    /** "局部改一处"允许的最大间隔（改字通常紧接着发生） */
    private static final long LOCAL_EDIT_MS = 5 * 60 * 1000L;
    /** 判定"局部改一处"所需的最短共同开头 */
    private static final int LOCAL_EDIT_MIN_PREFIX = 3;
    /** 分叉后两边剩余都这么短，就认为是在改同一处 */
    private static final int LOCAL_EDIT_MAX_TAIL = 4;

    String app = "";
    String field = "";
    /** 该段最后成型的文本 */
    String text = "";
    String firstTs = "";
    String lastTs = "";
    String minute = "";
    /** 该段一共包含多少个原始版本 */
    int versions = 1;
    /** 其中多少次属于"从中间改动"（错别字修正等），供界面说明 */
    int edits = 0;
    /** 该段最终形态是否处于输入法未上屏状态 */
    boolean comp;

    static List<Burst> group(List<LogStore.Row> rows) {
        // 先把"提交"记录（ev=send，发送/搜索/发布）单独拎出来。
        // 它们是**分段边界**，本身不是内容。
        //
        // 为什么必须从数据里来：早先只把发送时间记在内存静态字段里，
        // 用"这条之后有没有发送过"判断，而那个时间是"现在"，
        // 于是所有历史记录都被判成"发送之后"，导致 QQ 里 1039 个版本一段都没合并。
        // 落盘后，判据变成"两条文本之间是否夹着一条发送记录"，与当前时间无关。
        final List<Long> sendTs = new ArrayList<>();
        List<Burst> raw = new ArrayList<>();
        for (LogStore.Row r : rows) {
            if ("send".equals(r.ev)) {
                sendTs.add(msOf(r.ts));
                continue;
            }
            raw.add(fromRow(r));
        }
        Collections.sort(sendTs);
        // 排序键必须是「应用 + 输入框 + 时间」，不能只按时间。
        //
        // 踩过的坑（用户实录）：在豆包里打一段话 → 中途切去拼多多搜了一下 →
        // 回来接着打。只按时间排的话，拼多多那条会插进豆包的两条之间，
        // 于是 mergeable() 因"不是同一个应用"返回 false，同一段话被拆成两段：
        //   [896] 12:51:35 nova      <我想放的久一点啦这个保质期60天>
        //   [897] 12:52:40 pinduoduo <鸡肉肠>
        //   [898] 12:53:06 nova      <我想放的久一点啦这个保质期60天，>
        //
        // 按输入框分组排序后，同一框的记录必然相邻，跨应用切换不再打断分段。
        Collections.sort(raw, new Comparator<Burst>() {
            @Override
            public int compare(Burst a, Burst b) {
                int c = a.app.compareTo(b.app);
                if (c != 0) {
                    return c;
                }
                c = a.field.compareTo(b.field);
                if (c != 0) {
                    return c;
                }
                return Long.compare(msOf(a.firstTs), msOf(b.firstTs));
            }
        });

        List<Burst> out = new ArrayList<>();
        Burst cur = null;
        for (Burst b : raw) {
            if (cur != null && mergeable(cur, b, sendTs)) {
                if (isMidEdit(cur.text, b.text)) {
                    cur.edits++;       // 从中间改动，记下来（界面可说明"含 N 次修改"）
                }
                cur.text = b.text;
                cur.lastTs = b.lastTs;
                cur.minute = b.minute;
                cur.comp = b.comp;
                cur.versions++;
            } else {
                cur = b;
                out.add(cur);
            }
        }
        // 剔除"清空边界"产生的空段：它只是分段标记，没有内容可展示
        List<Burst> content = new ArrayList<>();
        for (Burst b : out) {
            if (!b.text.isEmpty()) {
                content.add(b);
            }
        }
        // 上面按"应用+输入框"分组排序会打乱时间次序，展示前按时间重排回来
        Collections.sort(content, new Comparator<Burst>() {
            @Override
            public int compare(Burst a, Burst b) {
                return Long.compare(msOf(a.firstTs), msOf(b.firstTs));
            }
        });
        return content;
    }

    /** 合并后按时间倒序（新的在前），跟列表习惯一致 */
    /** 合并后按时间倒序，并剔除"清空边界"产生的空段 —— 空段只是分段标记，没有内容可看 */
    static List<Burst> groupNewestFirst(List<LogStore.Row> rows) {
        List<Burst> list = group(rows);
        List<Burst> out = new ArrayList<>();
        for (Burst b : list) {
            if (!b.text.isEmpty()) {
                out.add(b);
            }
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * 是不是"同一段话里改了一处"。
     *
     * 特征：共同开头之后，两边剩下的内容都很短，或两边剩下的内容同尾（互为前缀）。
     *
     * 用来识别"锤他→锤它""可以买→可以吗"这种中间一个字的修正 ——
     * 这类修正不满足"以对方开头"，只靠前缀判据会漏合
     * （用户看豆包聊天记录时发现的问题）。
     */
    private static boolean isLocalizedEdit(String a, String b) {
        int cp = commonPrefix(a, b);
        if (cp < LOCAL_EDIT_MIN_PREFIX) {
            return false;
        }
        String ra = a.substring(cp);
        String rb = b.substring(cp);
        if (ra.length() <= LOCAL_EDIT_MAX_TAIL && rb.length() <= LOCAL_EDIT_MAX_TAIL) {
            return true;
        }
        // 剩余互为前缀（同尾）：<…锤它腿还在动> / <…戳它腿还在动>
        return !ra.isEmpty() && !rb.isEmpty() && (ra.startsWith(rb) || rb.startsWith(ra));
    }

    /**
     * 两个短串是否共用一个"实义字"（排除高频虚词）。
     *
     * 「的了吗呢啊吧呀哦嗯是你我在」这类字在任何句子里都常见，
     * 用它们判断"是不是同一个词"没有意义，反而会把无关的两条消息误并。
     */
    private static boolean shareContentChar(String a, String b) {
        for (int i = 0; i < a.length(); i++) {
            char ch = a.charAt(i);
            if (isCommonChar(ch)) {
                continue;
            }
            if (b.indexOf(ch) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCommonChar(char ch) {
        String common = "的了吗呢啊吧呀哦嗯是你我在有和就不人都一上了也还很";
        return common.indexOf(ch) >= 0 || ch == ' ' || ch == '，' || ch == '。' || ch == '！';
    }

    private static boolean mergeable(Burst cur, Burst next) {
        return mergeable(cur, next, null);
    }

    private static boolean mergeable(Burst cur, Burst next, List<Long> sends) {
        if (!cur.app.equals(next.app)) {
            return false;
        }
        // 两条文本之间夹着一次"提交" ⇒ 必定分段（这是真信号，不是猜的）
        if (sends != null) {
            long from = msOf(cur.lastTs);
            long to = msOf(next.firstTs);
            for (int i = 0; i < sends.size(); i++) {
                long t = sends.get(i);
                if (t > from && t <= to) {
                    return false;
                }
            }
        }
        // 输入框标识：空表示"旧记录没存这个字段"，与任何标识都视为同一输入框。
        // 否则加入 field 存盘后，会与改造前的历史记录无法合并。
        if (!cur.field.isEmpty() && !next.field.isEmpty() && !cur.field.equals(next.field)) {
            return false;
        }
        // 间隔判据分两种情况 —— 这是用户反馈"切出去几分钟回来接着打就断成两段"后的修正。
        //
        // 关键区别：**没发送就切走的内容不是"消息"，而是"未完成的草稿"**。
        //   · 后面接着写（新文本以旧文本开头）⇒ 补全同一段草稿，允许跨很长时间（默认 6 小时）
        //   · 完全换了内容 ⇒ 才按 GAP_MS 判断是不是另起一段
        //
        // 为什么"接着写"可以放宽：如果第一段真的发出去过，输入框会被清空，
        // 也就不会出现"新文本以旧文本开头"这种形态 —— 这一点由"发送"信号保证。
        long gap = msOf(next.firstTs) - msOf(cur.lastTs);
        String pa = cur.text == null ? "" : cur.text;
        String pb = next.text == null ? "" : next.text;
        boolean continuingDraft = !pa.isEmpty() && pb.startsWith(pa);
        if (gap > (continuingDraft ? LONG_GAP_MS : GAP_MS)) {
            return false;
        }
        String a = cur.text == null ? "" : cur.text;
        String b = next.text == null ? "" : next.text;

        if (a.isEmpty() && b.isEmpty()) {
            return true;    // 连续清空：归并成一个边界标记
        }
        if (a.isEmpty() || b.isEmpty()) {
            return false;   // 一侧清空：消息边界（有些应用会留下空状态）
        }
        // 延伸（往后打 / 退格）：同一条消息
        if (b.startsWith(a) || a.startsWith(b)) {
            return true;
        }
        // 整段重写：另一段话
        if (isFullRewrite(a, b)) {
            return false;
        }

        // 到这里是"中间改动"（改错别字）或"换了另一条消息"，两者形态相似，
        // 用共同开头长度区分：
        //   · 改错别字：共同开头通常很长（你好呀我 → 你好呀我很，共同 4 字）
        //   · 连发消息：几乎没有共同开头（你好呀 → 小朋友，共同 0 字）
        //
        // 实测依据：微信发消息**不产生空状态事件**，文本会直接从旧消息跳到新消息：
        //   ev: 16 EditText [你好] → [你好呀] → [小朋友]
        // 所以"靠清空判边界"在微信里失效，只能靠共同开头这一形态特征。
        if (commonPrefix(a, b) >= MIN_COMMON_PREFIX) {
            return true;
        }

        // ── 局部修改：同一段话只改了一个字（共同开头在中间分叉）──
        //
        // 这是用户看豆包聊天记录时发现的漏合：
        //   02:06:26 <…半死不活锤他>  →  02:06:28 <…半死不活锤它>   （只差 1 字）
        //   12:24:23 <这个可以买>     →  12:24:25 <这个可以吗>      （只差 1 字）
        // 两者前 20 多字完全一样，只有中间一个字不同 —— 属于"改错别字"，
        // 但"新文本以旧文本开头"的判据在这里失效（分叉在中间，不在末尾）。
        //
        // 判据：共同开头足够长 + 分叉之后两边剩余部分都很短（都在改同一处）。
        //   · <…锤他> → <…锤它>：两边剩余都是 1 字 ⇒ 局部改动，合并
        //   · <…锤它腿还在动> → <…戳它腿还在动>：剩余 5 字 vs 5 字（都是"腿还在动"），
        //     虽然超了 4 字阈值，但两边**互为前缀**（完全同尾），也判为局部改动
        //   · <你好呀> → <小朋友>：共同开头 0 ⇒ 不是局部改动
        if (isLocalizedEdit(a, b) && gap <= LOCAL_EDIT_MS) {
            return true;
        }

        // 到这里"共同开头"很短，但还可能是**把整个词打错重打**的情况。
        // 实测（设置里找开发者选项）：13:21:43 <来发展> → 13:21:46 <开发者>，
        // 只隔 3.4 秒，中间没有任何中间状态 —— 用户打错后整词重打，
        // 而"来发展"与"开发者"共同开头为 0，靠共同开头判不出来。
        //
        // 判据：间隔很短 + 两边都是短内容 ⇒ 是在改一个词，不是发了新消息。
        // 聊天里连发两条短消息的间隔通常更长（要按发送、再打字），
        // 而且**两条完全不同的话不会在几秒内要求用户重打一遍**。
        if (gap > RAPID_EDIT_MS || a.length() > RAPID_EDIT_MAX || b.length() > RAPID_EDIT_MAX) {
            return false;
        }
        // 光靠"间隔短"不够：聊天里连发两条短消息的间隔同样只有一两秒
        // （实测 23:52:15 <你好呀> → 23:52:17 <小朋友>）。
        //
        // 先排除一种形态：**一条是另一条的真子串**（前缀情形已在上面合并掉了，
        // 能走到这里就说明分叉不在开头）。
        //   · 上一条「你好」，这一条只发一个「好」 ——「好」是「你好」的子串。
        //     这不是"打错重打"，而是"新消息恰好是上文的一部分"。
        //     若不排除，两者会被合并成一段，**上一条就在显示里消失了**。
        //   · 而「来发展」→「开发者」互不为子串，仍按整词重打合并。
        if (a.contains(b) || b.contains(a)) {
            return false;
        }
        //
        // 再加一条：两边要**共用至少一个"实义字"**，才认为是在重打同一个词。
        // 「来发展」与「开发者」共用「发」→ 同一个词的错打；
        // 「你好呀」与「小朋友」一字不共 → 两条消息。
        return shareContentChar(a, b);
    }
    /**
     * 是否"整段重写"（应视为另一段话）。
     *
     * 从共同开头处起算两边剩下的内容，如果长度差超过 REWRITE_DELTA、
     * 且偏离部分相对整段不算短，就认为是重写。借此区分：
     *   <你好呀我> → <你好呀我很>        长度差 1        → 同一段
     *   <今天天气不错> → <今天天气很好>    长度差 0        → 同一段
     *   <第一句话> → <完全不同的另一段内容> 偏离大、比例高  → 两段
     */
    static boolean isFullRewrite(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return false;   // 清空或从空开始，都属于同一段写作过程
        }
        int shorter = Math.min(a.length(), b.length());
        if (shorter < MIN_LEN_FOR_REWRITE) {
            return false;   // 太短，不判重写
        }
        int cp = commonPrefix(a, b);
        return (double) cp / shorter < KEEP_RATIO;
    }
    /**
     * 这一版是否属于"从中间改动"。
     *
     * 判据：共同开头之后，**旧文本还有剩余** —— 说明不是单纯在末尾接着打。
     * 注意不能用 startsWith 判断（见类注释里的实测反例）。
     */
    /**
     * 这一版是否属于"真正的修正" —— 也就是界面上值得提示"含 N 次修改"的那种。
     *
     * 定义：既不是纯追加、也不是纯退格的改动。
     *   · 纯追加：旧文本是新文本的前缀（往后接着打）        → 不算
     *   · 纯退格：新文本是旧文本的前缀（按删除键）          → 不算
     *   · 其余（等长替换、中间插字、改完再接）              → 算
     */
    /**
     * 这一版是否属于"能判定的修正"（等长替换、或长度缩减后又改写）。
     *
     * 能力边界（重要）：**仅凭文本无法区分"末尾追加"和"在末字前插入"** ——
     *   你好呀我 → 你好呀我饿   （追加"饿"）
     *   你好呀我 → 你好呀我很   （在"我"前插入"很"）
     * 两者产生的新串都以旧串开头，形态完全一致。要区分必须知道光标位置，
     * 而无障碍事件通常不提供。所以这里只判定能确定的情况：
     *   · 等长但内容不同        → 替换，算修正
     *   · 新串比旧串短          → 删除，算修正
     *   · 其余（变长）          → 视为继续书写，不算修正
     * 这会让"修正次数"偏保守（少报），但不会把正常书写误报成修改。
     */
    static boolean isMidEdit(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) {
            return false;
        }
        if (b.length() < a.length()) {
            return true;    // 删掉了一些字
        }
        if (b.length() == a.length()) {
            return true;    // 等长替换
        }
        return false;       // 变长：追加或插字，无法判定，按"继续书写"处理
    }
    static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static Burst fromRow(LogStore.Row r) {
        Burst b = new Burst();
        b.app = r.app == null ? "" : r.app;
        b.field = r.field == null ? "" : r.field;
        b.text = r.text == null ? "" : r.text;
        b.firstTs = r.ts == null ? "" : r.ts;
        b.lastTs = b.firstTs;
        b.minute = r.minute == null ? "" : r.minute;
        b.comp = r.comp;
        return b;
    }

    /** "2026-10-08T23:04:01.234" -> 毫秒；拿不到返回 0 */
    static long msOf(String iso) {
        if (iso == null || iso.length() < 23) {
            return 0L;
        }
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US);
            java.util.Date d = f.parse(iso.substring(0, 23));
            return d == null ? 0L : d.getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }
}
