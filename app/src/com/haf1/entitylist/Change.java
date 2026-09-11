package com.haf1.entitylist;

/** 一条实体状态变化记录。 */
public class Change {
    /** 变化发生的时刻（epoch millis，已含毫秒小数部分）。 */
    public final long time;
    /** 变化后的值文本。 */
    public final String state;
    /** HA 返回的原始 last_changed 字符串。 */
    public final String raw;

    public Change(long time, String state, String raw) {
        this.time = time;
        this.state = state;
        this.raw = raw == null ? String.valueOf(time) : raw;
    }

    /**
     * 去重键：**HA 原始的 last_changed 字符串**。
     *
     * 曾经担心 WebSocket（实时状态机、带微秒）与 REST（历史库）精度不同，
     * 一度改成「秒级时间戳 + 值」。后来对真实 HA 实测证明：两条通道对同一次变化
     * 给出的 last_changed 与 state **逐字节相同**，那个担心是多余的。
     * 用原始字符串做键最精确，也不会把同一秒内的两次不同变化误合并。
     *
     * 真正造成重复的是历史接口开头那条「期初状态」（时间戳被改写成请求起始时刻），
     * 已在 HaClient.fetchHistory 里丢弃 —— 见 HaClient.isSyntheticStartState。
     */
    public String key() {
        return raw;
    }
}
