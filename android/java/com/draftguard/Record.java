package com.draftguard;

/** 一条采集记录（对外可见，供 LogStore / TypelogService 共用） */
final class Record {
    String ts = "";        // 2026-10-08T14:32:07.418
    long ms;
    String day = "";       // 2026-10-08
    String minute = "";    // 14:32
    String app = "";       // 包名
    String appLabel = "";  // App 中文名（写入 index，不重复存进每行）
    String field = "";     // 输入框标识（焦点/控件 id）
    String text = "";      // 该输入框此刻的完整文本
    int delta;             // 相对上一版的字数变化
    boolean comp;          // 是否处于输入法未上屏状态
    String ev = "text";    // 事件类型
    String detail = null;  // 失败用：原因
}
