package com.haf1.entitylist;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 配置存取（SharedPreferences 包装）。 */
public class Prefs {

    private static final String NAME = "ha_change_feed";

    /**
     * 默认 HA 地址。homeassistant.local 是 HA 通过 mDNS 广播的默认主机名，
     * 多数家庭网络可直接解析。首次配置时请按实际情况改（端口不确定就试 8123，
     * 新版 HAOS 也可能是 80）。
     */
    public static final String DEFAULT_BASE = "http://homeassistant.local:8123";

    /** 筛选模式：白名单。 */
    public static final int MODE_WHITELIST = 0;
    /** 筛选模式：黑名单。 */
    public static final int MODE_BLACKLIST = 1;

    /** 轮询间隔允许的范围（秒）。 */
    public static final int POLL_MIN = 1;
    public static final int POLL_MAX = 60;

    public String base = DEFAULT_BASE;
    public String token = "";
    public String entityId = "";
    public int mode = MODE_WHITELIST;
    public List<String> keywords = new ArrayList<String>();
    /** 启动时回溯多少小时。 */
    public int historyHours = 24;
    /** 轮询间隔（秒）。 */
    public int pollSeconds = 2;
    /** 有新记录时是否播放提示音。 */
    public boolean soundEnabled = true;
    /** 是否启用 WebSocket 实时推送（关掉则退回纯轮询）。 */
    public boolean realtimeEnabled = true;

    public static Prefs load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        Prefs p = new Prefs();
        p.base = sp.getString("base", DEFAULT_BASE);
        p.token = sp.getString("token", "");
        p.entityId = sp.getString("entity", "");
        p.mode = sp.getInt("mode", MODE_WHITELIST);
        p.keywords = splitKeywords(sp.getString("keywords", ""));
        p.historyHours = sp.getInt("hours", 24);
        p.pollSeconds = clampPoll(sp.getInt("poll", 2));
        p.soundEnabled = sp.getBoolean("sound", true);
        p.realtimeEnabled = sp.getBoolean("realtime", true);
        return p;
    }

    public void save(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putString("base", base)
                .putString("token", token)
                .putString("entity", entityId)
                .putInt("mode", mode)
                .putString("keywords", joinKeywords(keywords))
                .putInt("hours", historyHours)
                .putInt("poll", clampPoll(pollSeconds))
                .putBoolean("sound", soundEnabled)
                .putBoolean("realtime", realtimeEnabled)
                .apply();
    }

    public static int clampPoll(int v) {
        if (v < POLL_MIN) {
            return POLL_MIN;
        }
        if (v > POLL_MAX) {
            return POLL_MAX;
        }
        return v;
    }

    /** 令牌 / 地址 / 实体 三者齐全才算配置完成。 */
    public boolean isConfigured() {
        return base != null && base.trim().length() > 0
                && token != null && token.trim().length() > 0
                && entityId != null && entityId.trim().length() > 0;
    }

    /** 规范化后的服务器地址，去掉结尾斜杠。 */
    public String baseUrl() {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    /**
     * 判断一条变化记录是否应当显示。
     * 关键词匹配对象 = 变化后的值文本，大小写不敏感，子串匹配。
     */
    public boolean accept(String value) {
        if (keywords == null || keywords.isEmpty()) {
            // 白名单为空 = 全都要；黑名单为空 = 全都不排除。两种情况都显示。
            return true;
        }
        String v = value == null ? "" : value.toLowerCase();
        boolean hit = false;
        for (int i = 0; i < keywords.size(); i++) {
            String k = keywords.get(i);
            if (k == null || k.length() == 0) {
                continue;
            }
            if (v.indexOf(k.toLowerCase()) >= 0) {
                hit = true;
                break;
            }
        }
        if (mode == MODE_WHITELIST) {
            return hit;              // 白名单：必须命中
        }
        return !hit;                 // 黑名单：命中则排除
    }

    private static List<String> splitKeywords(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        String[] parts = s.split("\n");
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i].trim();
            if (t.length() > 0) {
                out.add(t);
            }
        }
        return out;
    }

    private static String joinKeywords(List<String> list) {
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                String t = list.get(i) == null ? "" : list.get(i).trim();
                if (t.length() > 0) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
        }
        return sb.toString();
    }
}
