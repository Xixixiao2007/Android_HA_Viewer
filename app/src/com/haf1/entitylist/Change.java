package com.haf1.entitylist;

/** 一条实体状态变化记录。 */
public class Change {
    /** 变化发生的时刻（epoch millis，已含毫秒小数部分）。 */
    public final long time;
    /** 变化后的值文本。 */
    public final String state;
    /** HA 返回的原始 last_changed 字符串（仅用于排查，不用于去重）。 */
    public final String raw;

    public Change(long time, String state, String raw) {
        this.time = time;
        this.state = state;
        this.raw = raw == null ? String.valueOf(time) : raw;
    }

    /**
     * 去重键：**秒级时间戳 + 值**。
     *
     * 为什么不能用原始的 last_changed 字符串做键：
     * 同一次状态变化，两条通道给出的时间戳精度不同 ——
     *   WebSocket 的 to_state.last_changed 来自内存里的实时状态机，带微秒；
     *   REST 历史接口读的是 recorder 数据库，微秒已被抹掉。
     * HA 自己也要为此做兼容（core PR #71704 里的 floored_timestamp，
     * 对应测试名 test_end_time_with_microseconds_zeroed）。
     *
     * 于是 "2026-09-05T14:23:05.123456+00:00" 与 "2026-09-05T14:23:05+00:00"
     * 会被当成两条不同的记录 —— 表现为同一条变化在 60 秒对账后重复出现。
     *
     * 用秒级粒度可同时兼容「抹掉微秒」「截断到毫秒」等不同数据库的行为。
     *
     * 代价：同一秒内「值也恰好相同」的两次变化会被合并成一条
     * （例如 A→B→A 发生在同一秒内）。这远比系统性重复轻微，且实际极少发生。
     */
    public static String dedupKey(long timeMillis, String state) {
        return (timeMillis / 1000L) + "\u0000" + (state == null ? "" : state);
    }

    /** 本记录的去重键。 */
    public String key() {
        return dedupKey(time, state);
    }
}
