package com.haf1.entitylist;

/** 一条实体状态变化记录。 */
public class Change {
    /** 变化发生的时刻（epoch millis，已含毫秒小数部分）。 */
    public final long time;
    /** 变化后的值文本。 */
    public final String state;
    /**
     * HA 返回的原始 last_changed 字符串。
     *
     * 用它做去重键而不是 time：HA 的 last_changed 精确到微秒，
     * 截断到毫秒后同一毫秒内的两次变化会撞键、被静默丢掉一条。
     */
    public final String raw;

    public Change(long time, String state, String raw) {
        this.time = time;
        this.state = state;
        this.raw = raw == null ? String.valueOf(time) : raw;
    }
}
