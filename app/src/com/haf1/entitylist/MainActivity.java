package com.haf1.entitylist;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主页：实体状态变化流水。
 *
 * 两条数据通道：
 *   1) WebSocket 实时推送（主）—— 事件不经数据库，延迟 = 网络往返
 *   2) REST 历史接口轮询（辅）—— 首次回溯，以及实时断开时的兜底
 * 实时在线时仍保留一个低频「对账」轮询，兜住断线重连期间漏掉的变化。
 *
 * 标题栏（含右上角设置键）之下**只有列表**，最新变化在最上，新记录到达时自动置顶。
 */
public class MainActivity extends Activity {

    private static final int REQ_SETTINGS = 1;

    private static final int COLOR_BAR = 0xFF1976D2;
    private static final int COLOR_BAR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_BAR_SUB = 0xFFBBDEFB;
    private static final int COLOR_ERROR = 0xFFFFCDD2;

    /** 实时在线时的对账轮询间隔：兜住断线重连期间漏掉的变化。 */
    private static final long RECONCILE_MS = 60000L;
    /** 提示音最小间隔，避免事件突发时连环炸响。 */
    private static final long BEEP_MIN_GAP_MS = 2000L;

    private Prefs prefs;
    private Notifier notifier;

    private TextView titleView;
    private TextView statusView;

    private ListView listView;
    private RowAdapter adapter;

    /**
     * 全部变化记录，键 = {@link Change#key()}（秒级时间戳 + 值）。
     *
     * 不能用 HA 原始的 last_changed 字符串做键：实时推送（带微秒）与历史库查询
     * （微秒被抹掉）对同一次变化给出的字符串不同，会导致同一条记录重复出现。
     * 详见 Change.dedupKey 的注释。
     */
    private final LinkedHashMap<String, Change> all = new LinkedHashMap<String, Change>();
    /** 已知最新一条变化的时间，增量拉取的起点。 */
    private long newestTime = 0L;
    /** 首次全量回溯是否已完成。用它而不是 all.isEmpty()，否则窗口内无变化时会一直当成首次。 */
    private boolean backfillDone = false;
    /** 当前实际显示（已过筛选）的记录，按时间倒序。 */
    private final List<Change> shown = new ArrayList<Change>();

    private final Handler handler = new Handler();
    private boolean busy = false;
    private boolean autoOpenedSettings = false;
    private String lastSignature = null;

    private final SimpleDateFormat fmt =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    // ---- 实时推送状态 ----
    private volatile boolean wsRunning = false;
    private volatile boolean wsConnected = false;
    private volatile HaWebSocket ws = null;
    private Thread wsThread = null;
    private String modePrefix = "";
    private String wsNote = "";
    private String lastStatus = "";
    private boolean lastStatusOk = true;
    private long lastBeepAt = 0L;

    // ------------------------------------------------------------------
    // 界面搭建
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.load(this);
        notifier = new Notifier(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);

        root.addView(buildTitleBar());

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setCacheColorHint(0);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private View buildTitleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COLOR_BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(8), dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(this);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setTextColor(COLOR_BAR_TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setTextColor(COLOR_BAR_SUB);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(statusView);

        bar.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView settings = new TextView(this);
        settings.setText("设置");
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        settings.setTextColor(COLOR_BAR_TEXT);
        settings.setPadding(dp(14), dp(8), dp(14), dp(8));
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class),
                        REQ_SETTINGS);
            }
        });
        bar.addView(settings);

        return bar;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        prefs = Prefs.load(this);

        String sig = signature(prefs);
        if (lastSignature == null || !sig.equals(lastSignature)) {
            // 配置变了（含首次进入）：清空重来
            lastSignature = sig;
            all.clear();
            shown.clear();
            newestTime = 0L;
            backfillDone = false;
            lastBeepAt = 0L;
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }

        if (!prefs.isConfigured()) {
            titleView.setText("未配置");
            setMode("");
            setStatus("请点右上角「设置」填写地址、令牌和实体名", false);
            if (!autoOpenedSettings) {
                autoOpenedSettings = true;
                startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
            }
            return;
        }

        titleView.setText(prefs.entityId);
        setStatus("正在读取最近 " + prefs.historyHours + " 小时的变化…", true);

        startRealtime();
        startPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRealtime();
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRealtime();
        stopPolling();
        if (notifier != null) {
            notifier.release();
        }
    }

    private static String signature(Prefs p) {
        return p.baseUrl() + "|" + p.token + "|" + p.entityId + "|" + p.historyHours
                + "|" + p.mode + "|" + p.keywords.size() + "|" + p.realtimeEnabled;
    }

    // ------------------------------------------------------------------
    // 通道一：WebSocket 实时推送
    // ------------------------------------------------------------------

    private void startRealtime() {
        stopRealtime();
        if (!prefs.realtimeEnabled) {
            wsNote = "";
            setMode("");
            return;
        }
        wsRunning = true;
        setMode("⏳ 连接中");

        wsThread = new Thread(new Runnable() {
            public void run() {
                int failures = 0;
                while (wsRunning) {
                    final HaWebSocket sock = new HaWebSocket(prefs, wsListener);
                    ws = sock;
                    boolean ok = false;
                    try {
                        ok = sock.runAndReport();
                    } catch (Throwable ignored) {
                        // runAndReport 内部已兜住，这里只是最后一道保险
                    }
                    ws = null;
                    if (!wsRunning) {
                        break;
                    }
                    if (ok) {
                        failures = 0;
                    }
                    failures++;
                    // 指数退避，最多 30 秒
                    long waitSec = Math.min(30L, 1L << Math.min(failures, 5));
                    try {
                        Thread.sleep(waitSec * 1000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "ha-ws");
        wsThread.start();
    }

    private void stopRealtime() {
        wsRunning = false;
        HaWebSocket sock = ws;
        if (sock != null) {
            sock.shutdown();
        }
        ws = null;
        wsConnected = false;
        Thread t = wsThread;
        wsThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private final HaWebSocket.Listener wsListener = new HaWebSocket.Listener() {

        public void onOpen() {
            wsConnected = true;
            handler.post(new Runnable() {
                public void run() {
                    wsNote = "";
                    setMode("⚡ 实时");
                    updateStatus();
                    // 补一次对账，封住「历史回溯结束」到「实时订阅建立」之间的缝
                    if (backfillDone) {
                        fetchOnce(false);
                    }
                }
            });
        }

        public void onState(final long time, final String state, final String raw) {
            handler.post(new Runnable() {
                public void run() {
                    List<Change> one = new ArrayList<Change>(1);
                    one.add(new Change(time, state, raw));
                    mergeChanges(one, true);
                }
            });
        }

        public void onError(final String message) {
            wsConnected = false;
            handler.post(new Runnable() {
                public void run() {
                    wsNote = "实时失败：" + message;
                    setMode("⏱ 轮询");
                    updateStatus();
                }
            });
        }

        public void onClose() {
            wsConnected = false;
            handler.post(new Runnable() {
                public void run() {
                    if (wsRunning) {
                        setMode("⏳ 连接中");
                    }
                }
            });
        }
    };

    // ------------------------------------------------------------------
    // 通道二：REST 历史接口
    // ------------------------------------------------------------------

    private void startPolling() {
        handler.removeCallbacks(pollTask);
        handler.post(pollTask);
    }

    private void stopPolling() {
        handler.removeCallbacks(pollTask);
        busy = false;
    }

    private final Runnable pollTask = new Runnable() {
        public void run() {
            fetchOnce(false);
            long gap = wsConnected
                    ? RECONCILE_MS                              // 实时在线：低频对账即可
                    : Prefs.clampPoll(prefs.pollSeconds) * 1000L;
            handler.postDelayed(this, gap);
        }
    };

    /** 首次进入用一次全量回溯，之后只增量拉取。 */
    private void fetchOnce(boolean forceInitial) {
        if (busy) {
            // 上一轮还没回来就跳过这一拍 —— 实际周期是
            // 「请求耗时向上取整到轮询间隔的整数倍」，而不总是设定值。
            return;
        }
        busy = true;
        final Prefs p = prefs;
        final boolean isInitial = forceInitial || !backfillDone;

        new Thread(new Runnable() {
            public void run() {
                try {
                    long now = System.currentTimeMillis();
                    long start;
                    if (isInitial || newestTime <= 0L) {
                        start = now - p.historyHours * 3600000L;
                    } else {
                        start = newestTime;
                    }
                    final List<Change> got = HaClient.fetchHistory(p, start, now);
                    handler.post(new Runnable() {
                        public void run() {
                            mergeChanges(got, !isInitial);
                            busy = false;
                        }
                    });
                } catch (final HaClient.HaException e) {
                    handler.post(new Runnable() {
                        public void run() {
                            setStatus(e.getMessage(), false);
                            busy = false;
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        public void run() {
                            setStatus("出错：" + e, false);
                            busy = false;
                        }
                    });
                }
            }
        }, "ha-fetch").start();
    }

    // ------------------------------------------------------------------
    // 合并 / 渲染
    // ------------------------------------------------------------------

    /** 合并一批变化并重绘。allowSound 为真且有通过筛选的新记录时响一声。 */
    private void mergeChanges(List<Change> items, boolean allowSound) {
        int added = 0;
        int addedShown = 0;
        for (int i = 0; i < items.size(); i++) {
            Change c = items.get(i);
            if (!all.containsKey(c.key())) {
                added++;
                if (prefs.accept(c.state)) {
                    addedShown++;
                }
            }
            all.put(c.key(), c);
            if (c.time > newestTime) {
                newestTime = c.time;
            }
        }

        // 丢掉超出回溯窗口的旧记录，避免内存无限增长
        long cutoff = System.currentTimeMillis() - prefs.historyHours * 3600000L;
        Iterator<Map.Entry<String, Change>> it = all.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().time < cutoff) {
                it.remove();
            }
        }

        rebuildShown();
        adapter.notifyDataSetChanged();

        if (added > 0) {
            listView.setSelection(0);       // 自动保持在顶部
        }
        if (allowSound && addedShown > 0 && prefs.soundEnabled) {
            beepThrottled();
        }

        backfillDone = true;
        updateStatus();
    }

    /** 提示音节流：实时推送下一条一响会连环炸，限制最小间隔。 */
    private void beepThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastBeepAt < BEEP_MIN_GAP_MS) {
            return;
        }
        lastBeepAt = now;
        if (notifier != null) {
            notifier.beep();
        }
    }

    private void rebuildShown() {
        List<Change> tmp = new ArrayList<Change>(all.values());
        // 时间倒序 = 最新在最上
        Collections.sort(tmp, new Comparator<Change>() {
            public int compare(Change a, Change b) {
                if (a.time != b.time) {
                    return a.time > b.time ? -1 : 1;
                }
                return b.raw.compareTo(a.raw);
            }
        });
        shown.clear();
        for (int i = 0; i < tmp.size(); i++) {
            Change c = tmp.get(i);
            if (prefs.accept(c.state)) {
                shown.add(c);
            }
        }
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(all.size()).append(" 条变化");
        if (!prefs.keywords.isEmpty()) {
            sb.append(" · 筛选后 ").append(shown.size()).append(" 条 · ")
                    .append(prefs.mode == Prefs.MODE_WHITELIST ? "白名单" : "黑名单");
        }
        if (wsNote.length() > 0) {
            sb.append(" · ").append(wsNote);
        }
        setStatus(sb.toString(), wsNote.length() == 0);
    }

    private void setMode(String m) {
        modePrefix = m;
        renderStatus();
    }

    private void setStatus(String text, boolean ok) {
        lastStatus = text;
        lastStatusOk = ok;
        renderStatus();
    }

    private void renderStatus() {
        String prefix = modePrefix.length() > 0
                ? (wsConnected ? modePrefix + " · " : modePrefix + " · ")
                : "";
        statusView.setText(prefix + lastStatus);
        statusView.setTextColor(lastStatusOk ? COLOR_BAR_SUB : COLOR_ERROR);
    }

    // ------------------------------------------------------------------
    // 列表适配器
    // ------------------------------------------------------------------

    private class RowAdapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int position) {
            return shown.get(position);
        }

        public long getItemId(int position) {
            return shown.get(position).time;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = (convertView instanceof Row) ? (Row) convertView : new Row();
            Change c = shown.get(position);
            row.setZebra(position);
            row.time.setText(fmt.format(new Date(c.time)));
            row.value.setText(c.state == null || c.state.length() == 0 ? "(空)" : c.state);
            return row;
        }
    }

    private class Row extends LinearLayout {
        final TextView time;
        final TextView value;

        Row() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(9), dp(14), dp(9));

            time = new TextView(MainActivity.this);
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            time.setTextColor(0xFF9E9E9E);
            addView(time);

            value = new TextView(MainActivity.this);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            value.setTextColor(0xFF212121);
            value.setMaxLines(3);
            value.setEllipsize(TextUtils.TruncateAt.END);
            addView(value);
        }

        void setZebra(int position) {
            setBackgroundColor(position % 2 == 0 ? 0xFFFFFFFF : 0xFFFAFAFA);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            // 回到前台时 onResume 会重新读配置并判断是否要重载
            lastSignature = null;
        }
    }
}
