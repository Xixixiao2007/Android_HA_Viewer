package com.haf1.entitylist;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页：实体名 + 黑白名单筛选 + 刷新与提示音（右上角设置键进入）。
 *
 * 全部界面用代码构建 —— 不引入任何 res/layout，构建流水线因此不需要 R.java。
 */
public class SettingsActivity extends Activity {

    private static final int COLOR_TEXT = 0xFF212121;
    private static final int COLOR_HINT = 0xFF757575;
    private static final int COLOR_SECTION = 0xFF1976D2;

    private EditText baseEdit;
    private EditText tokenEdit;
    private EditText entityEdit;
    private EditText hoursEdit;
    private EditText pollEdit;
    private Switch soundSwitch;
    private Switch realtimeSwitch;
    private Switch modeSwitch;
    private TextView modeLabel;
    private EditText keywordEdit;
    private LinearLayout keywordBox;

    private final java.util.List<String> keywords = new java.util.ArrayList<String>();
    private int mode = Prefs.MODE_WHITELIST;
    private boolean soundEnabled = true;
    private boolean realtimeEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Prefs p = Prefs.load(this);
        baseEdit = field("服务器地址（含端口）", p.base);
        tokenEdit = field("长期访问令牌", p.token);
        tokenEdit.setSingleLine(false);
        tokenEdit.setMaxLines(3);
        tokenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        entityEdit = field("实体名 / 实体 ID", p.entityId);
        hoursEdit = field("回溯小时数", String.valueOf(p.historyHours));
        hoursEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        pollEdit = field("刷新间隔（秒）", String.valueOf(p.pollSeconds));
        pollEdit.setInputType(InputType.TYPE_CLASS_NUMBER);

        keywords.clear();
        keywords.addAll(p.keywords);
        mode = p.mode;
        soundEnabled = p.soundEnabled;
        realtimeEnabled = p.realtimeEnabled;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF5F5F5);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(20));
        scroll.addView(root);

        // ---------- 连接 ----------
        root.addView(section("连接"));
        root.addView(caption("HA 地址。端口不确定就试 8123，新版 HAOS 也可能是 80。"));
        root.addView(baseEdit);
        root.addView(caption("长期访问令牌：HA 网页版 → 个人资料 → 安全 → 长期访问令牌。"
                + "普通权限用户即可，无需管理员。"));
        root.addView(tokenEdit);
        root.addView(caption("要监控的实体，例如 sensor.xxx 或 input_text.xxx"));
        root.addView(entityEdit);
        root.addView(caption("启动时往前读多少小时的历史变化。"));
        root.addView(hoursEdit);

        // ---------- 刷新与提示音 ----------
        root.addView(section("刷新与提示音"));
        root.addView(pollEdit);
        root.addView(caption("越小越实时，但向 HA 请求更频繁。范围 "
                + Prefs.POLL_MIN + "~" + Prefs.POLL_MAX + " 秒，建议 2~5。"
                + "提示音开关见下。"));

        soundSwitch = new Switch(this);
        soundSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        soundSwitch.setTextColor(COLOR_TEXT);
        soundSwitch.setPadding(0, dp(12), 0, 0);
        root.addView(soundSwitch);
        realtimeSwitch = new Switch(this);
        realtimeSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        realtimeSwitch.setTextColor(COLOR_TEXT);
        realtimeSwitch.setPadding(0, dp(12), 0, 0);
        root.addView(realtimeSwitch);
        root.addView(caption("实时推送走 WebSocket：事件不经数据库，延迟降到网络往返级别。"
                + "关掉则退回纯轮询。断线会自动重连，重连期间自动走轮询。"));

        // ---------- 筛选 ----------
        root.addView(section("筛选（白名单 / 黑名单）"));
        modeLabel = new TextView(this);
        modeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        modeLabel.setTextColor(COLOR_TEXT);
        root.addView(modeLabel);

        modeSwitch = new Switch(this);
        modeSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        modeSwitch.setTextColor(COLOR_TEXT);
        modeSwitch.setPadding(0, dp(8), 0, dp(8));
        root.addView(modeSwitch);

        root.addView(caption("关键词匹配「变化后的值」，不区分大小写、子串匹配。"
                + "白名单为空 = 全部显示。"));
        keywordEdit = field("输入关键词", "");
        keywordEdit.setSingleLine(true);
        Button addBtn = button("添加关键词");
        addBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String k = keywordEdit.getText().toString().trim();
                if (k.length() == 0) {
                    return;
                }
                keywords.add(k);
                keywordEdit.setText("");
                renderKeywords();
            }
        });
        root.addView(keywordEdit);
        root.addView(addBtn);

        keywordBox = new LinearLayout(this);
        keywordBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(keywordBox);

        // ---------- 操作 ----------
        root.addView(section("操作"));
        Button testBtn = button("测试连接");
        testBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                testConnection();
            }
        });
        root.addView(testBtn);

        Button saveBtn = button("保存");
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });
        root.addView(saveBtn);

        // 先渲染开关状态，再挂监听，避免初始化时触发回调
        modeSwitch.setOnCheckedChangeListener(null);
        modeSwitch.setChecked(mode == Prefs.MODE_WHITELIST);
        updateModeLabel();
        modeSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        mode = checked ? Prefs.MODE_WHITELIST : Prefs.MODE_BLACKLIST;
                        updateModeLabel();
                    }
                });

        soundSwitch.setOnCheckedChangeListener(null);
        soundSwitch.setChecked(soundEnabled);
        updateSoundLabel();
        soundSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        soundEnabled = checked;
                        updateSoundLabel();
                    }
                });

        realtimeSwitch.setOnCheckedChangeListener(null);
        realtimeSwitch.setChecked(realtimeEnabled);
        updateRealtimeLabel();
        realtimeSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        realtimeEnabled = checked;
                        updateRealtimeLabel();
                    }
                });
        renderKeywords();

        setContentView(scroll);
    }

    // ------------------------------------------------------------------
    // 控件工厂
    // ------------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView section(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(COLOR_SECTION);
        tv.setPadding(0, dp(18), 0, dp(6));
        return tv;
    }

    private TextView caption(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(COLOR_HINT);
        tv.setPadding(0, dp(4), 0, dp(2));
        return tv;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateModeLabel() {
        boolean white = mode == Prefs.MODE_WHITELIST;
        modeLabel.setText(white
                ? "当前：白名单模式 —— 只显示命中关键词的记录"
                : "当前：黑名单模式 —— 隐藏命中关键词的记录");
        modeSwitch.setText(white ? "白名单" : "黑名单");
    }

    private void updateRealtimeLabel() {
        realtimeSwitch.setText(realtimeEnabled
                ? "实时推送：开 —— WebSocket，延迟最低"
                : "实时推送：关 —— 仅用轮询");
    }

    private void updateSoundLabel() {
        soundSwitch.setText(soundEnabled
                ? "提示音：开 —— 有通过筛选的新记录时响一声"
                : "提示音：关");
    }

    private void renderKeywords() {
        keywordBox.removeAllViews();
        if (keywords.isEmpty()) {
            keywordBox.addView(caption("（还没有关键词。留空 = 不过滤）"));
            return;
        }
        for (int i = 0; i < keywords.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);

            TextView tv = new TextView(this);
            tv.setText((i + 1) + ".  " + keywords.get(i));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextColor(COLOR_TEXT);
            row.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            del.setTextColor(0xFFD32F2F);
            del.setPadding(dp(12), dp(4), dp(4), dp(4));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    keywords.remove(idx);
                    renderKeywords();
                }
            });
            row.addView(del);

            keywordBox.addView(row);
        }
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    /** 用当前输入框内容拼一份临时配置（不落盘），供测试连接使用。 */
    private Prefs draft() {
        Prefs p = new Prefs();
        p.base = baseEdit.getText().toString().trim();
        p.token = tokenEdit.getText().toString().trim();
        p.entityId = entityEdit.getText().toString().trim();
        p.mode = mode;
        p.keywords = new java.util.ArrayList<String>(keywords);
        p.historyHours = parseHours();
        p.pollSeconds = parsePoll();
        p.soundEnabled = soundEnabled;
        p.realtimeEnabled = realtimeEnabled;
        return p;
    }

    private int parseHours() {
        try {
            int h = Integer.parseInt(hoursEdit.getText().toString().trim());
            return h > 0 && h <= 24 * 30 ? h : 24;
        } catch (Exception e) {
            return 24;
        }
    }

    private int parsePoll() {
        try {
            return Prefs.clampPoll(Integer.parseInt(pollEdit.getText().toString().trim()));
        } catch (Exception e) {
            return 2;
        }
    }

    private void testConnection() {
        final Prefs p = draft();
        if (p.baseUrl().length() == 0 || p.token.length() == 0) {
            toast("请先填写服务器地址和令牌");
            return;
        }
        toast("测试中…");
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    msg = HaClient.testConnection(p);
                } catch (HaClient.HaException e) {
                    msg = e.getMessage();
                } catch (Exception e) {
                    msg = "出错：" + e;
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    public void run() {
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("测试结果")
                                .setMessage(m)
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        }, "ha-test").start();
    }

    private void save() {
        Prefs p = draft();
        if (p.baseUrl().length() == 0) {
            toast("请填写服务器地址");
            return;
        }
        if (!p.baseUrl().startsWith("http://") && !p.baseUrl().startsWith("https://")) {
            toast("地址要以 http:// 或 https:// 开头");
            return;
        }
        if (p.token.length() == 0) {
            toast("请填写长期访问令牌");
            return;
        }
        if (p.entityId.length() == 0) {
            toast("请填写实体名");
            return;
        }
        p.historyHours = parseHours();
        p.pollSeconds = parsePoll();
        p.soundEnabled = soundEnabled;
        p.realtimeEnabled = realtimeEnabled;
        p.save(this);
        toast("已保存");
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
