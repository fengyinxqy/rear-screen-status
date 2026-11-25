package com.dsh.rearscreen;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.rearscreen.data.AgentState;
import com.dsh.rearscreen.data.StateStore;
import com.dsh.rearscreen.data.StatusSource;
import com.dsh.rearscreen.shizuku.RearController;
import com.dsh.rearscreen.shizuku.Shell;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Settings and the one-time grants.
 *
 * Everything the app needs from the system is granted outside the app — Shizuku permission,
 * the rear screen, battery and autostart — so this screen's job is to say which of those is
 * missing and to do the part it can do itself.
 *
 * This screen is NOT the panel. The panel is glanced at from across a desk and can afford to be
 * one word on absolute black; this page is read line by line, at night, and needs the opposite:
 * a background lifted off black, each group on its own surface, dividers between rows, and ink
 * that stays legible for more than a few seconds. The family resemblance is kept in the type,
 * the single accent colour and one hero reading at the top — not in starving the contrast.
 */
public class MainActivity extends Activity implements StateStore.Listener {

    public static final String ACTION_PLACE = "com.dsh.rearscreen.PLACE";
    public static final String ACTION_ENABLE = "com.dsh.rearscreen.ENABLE";
    public static final String ACTION_PROBE = "com.dsh.rearscreen.PROBE";

    /**
     * Pins one panel state for inspection, e.g.
     *   adb shell am broadcast -a com.dsh.rearscreen.PREVIEW --es kind offline -p com.dsh.rearscreen
     * A null kind returns the panel to live readings.
     */
    public static final String ACTION_PREVIEW = RearActivity.ACTION_PREVIEW;

    /**
     * Debug entry points, so placement can be driven from adb instead of a tap:
     *
     *   adb shell am broadcast -a com.dsh.rearscreen.ENABLE -p com.dsh.rearscreen
     *   adb shell am broadcast -a com.dsh.rearscreen.PLACE  -p com.dsh.rearscreen
     *
     * Broadcast by package: -n com.dsh.rearscreen/.MainActivity names the settings Activity,
     * and the system will not hand an Activity class an onReceive — the call reports success
     * and nothing happens.
     */
    public static class DebugReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(android.content.Context ctx, android.content.Intent intent) {
            String a = intent == null ? "" : String.valueOf(intent.getAction());
            android.util.Log.i("RearStatus", "debug action: " + a
                    + " display=" + StateStore.displayId(ctx)
                    + " enabled=" + StateStore.enabled(ctx));
            if (ACTION_ENABLE.equals(a)) {
                StateStore.setEnabled(ctx, true);
                StatusService.start(ctx);
            }
            if (ACTION_PREVIEW.equals(a)) {
                String kind = intent == null ? "" : intent.getStringExtra("kind");
                com.dsh.rearscreen.RearActivity.showPreview(kind);
                android.util.Log.i("RearStatus", "preview: " + (kind == null || kind.isEmpty() ? "(live)" : kind));
            }
            if (ACTION_PROBE.equals(a)) {
                final android.content.Context app0 = ctx.getApplicationContext();
                new Thread(() -> {
                    com.dsh.rearscreen.shizuku.Shell.Result raw =
                            com.dsh.rearscreen.shizuku.Shell.exec(6000, "/system/bin/am", "stack", "list");
                    int tid = com.dsh.rearscreen.RearActivity.taskId;
                    android.util.Log.i("RearStatus", "PROBE taskId=" + tid
                            + " viaService=" + com.dsh.rearscreen.shizuku.RearService.taskDisplay(tid)
                            + " panelDisplay=" + com.dsh.rearscreen.shizuku.RearController.panelDisplay(app0)
                            + " taskDisplay=" + com.dsh.rearscreen.shizuku.RearService.taskDisplay(tid));
                    int display = -1;
                    java.util.regex.Pattern pLine =
                            java.util.regex.Pattern.compile("taskId=(\\d+):\\s*([^/\\s]+)/([^\\s]+)");
                    for (String line : raw.out.split("\n")) {
                        String t = line.trim();
                        java.util.regex.Matcher rd =
                                java.util.regex.Pattern.compile("^RootTask id=\\d+.*displayId=(\\d+)").matcher(t);
                        if (rd.find()) {
                            display = Integer.parseInt(rd.group(1));
                            if (display == 1) android.util.Log.i("RearStatus", "PROBE hdr1 " + t);
                            continue;
                        }
                        java.util.regex.Matcher m = pLine.matcher(t);
                        if (m.find()) {
                            android.util.Log.i("RearStatus", "PROBE hit disp=" + display
                                    + " pkg=" + m.group(2) + " act=" + m.group(3)
                                    + " id=" + m.group(1));
                        }
                    }
                }, "probe").start();
            }
            final android.content.Context app = ctx.getApplicationContext();
            new Thread(() -> {
                int d = StateStore.displayId(app);
                if (d < 0) {
                    d = com.dsh.rearscreen.shizuku.RearController.findRearDisplay(app);
                    if (d >= 0) StateStore.setDisplayId(app, d);
                }
                String msg = com.dsh.rearscreen.shizuku.RearController.place(app, d);
                android.util.Log.i("RearStatus", "debug place(" + d + "): " + msg
                        + " -> task on display "
                        + com.dsh.rearscreen.shizuku.RearService.taskDisplay(
                                com.dsh.rearscreen.RearActivity.taskId));
            }, "debug-place").start();
        }
    }

    // ── palette ──────────────────────────────────────────────────────────────────────────
    // Not the panel's absolute black: a settings page read at night needs a surface the eye can
    // separate rows against, and ink that keeps its contrast after a minute of reading.
    private static final int BG = 0xFF101015;
    private static final int CARD = 0xFF18181F;
    private static final int CARD_EDGE = 0xFF272730;
    private static final int LINE = 0xFF2B2B35;
    private static final int INK = 0xFFF3F3F6;
    private static final int INK_2 = 0xFFBEBEC8;
    private static final int INK_3 = 0xFF8E8E99;
    private static final int GOOD = 0xFF3DDC84;
    private static final int BAD = 0xFFF87171;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusWord;
    private TextView statusMeta;
    private TextView statusAgents;
    private View statusSeam;
    private TextView healthRear;
    private TextView healthPanel;
    private TextView healthShizuku;
    private TextView shizukuView;
    private TextView displayView;
    private EditText sourceView;
    private LinearLayout displayRows;
    private TextView panelButton;
    private TextView logView;
    private ScrollView scroll;

    private final List<Integer> displayIds = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
        StateStore.addListener(this);
        refreshShizuku();
        refreshDisplay();
        render(StateStore.current());
    }

    @Override
    protected void onResume() {
        super.onResume();
        StateStore.addListener(this);
        refreshShizuku();
        render(StateStore.current());
    }

    @Override
    protected void onDestroy() {
        StateStore.removeListener(this);
        super.onDestroy();
    }

    // ── layout ───────────────────────────────────────────────────────────────────────────

    private View build() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(26), dp(16), dp(40));

        TextView brand = text("背屏 AGENT 状态", 12, INK_3);
        brand.setLetterSpacing(0.14f);
        brand.setPadding(dp(4), 0, 0, dp(20));
        root.addView(brand);

        root.addView(statusBlock());
        root.addView(healthRow());

        panelButton = action("", true, v -> togglePanel());
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        toggleLp.topMargin = dp(18);
        toggleLp.leftMargin = dp(4);
        toggleLp.rightMargin = dp(4);
        panelButton.setLayoutParams(toggleLp);
        root.addView(panelButton);

        // 01 · Shizuku
        LinearLayout shizuku = card("01", "Shizuku", "借 shell 权限把面板放上背屏，免 root");
        shizukuView = text("", 14, INK_2);
        shizukuView.setPadding(dp(26), dp(12), 0, dp(14));
        shizuku.addView(shizukuView);
        shizuku.addView(divider());
        shizuku.addView(action("授予 Shizuku 权限", false, v -> {
            if (!Shell.available()) {
                toast("先安装并启动 Shizuku");
                return;
            }
            Shell.request();
            ui.postDelayed(this::refreshShizuku, 800);
        }));
        root.addView(shizuku);

        // 02 · status source
        LinearLayout source = card("02", "状态源", "PC 上的 daemon 地址，或手机里的一个文件");
        source.addView(sourceField());
        TextView hint = text("也可以 adb push 一个 JSON 到 /sdcard/rear/status.json", 13, INK_3);
        hint.setPadding(dp(26), dp(10), 0, dp(14));
        source.addView(hint);
        source.addView(divider());
        source.addView(action("保存并测试", false, v -> saveSource()));
        root.addView(source);

        // 03 · rear display
        LinearLayout rear = card("03", "背屏", "面板放在哪块屏上");
        displayRows = column();
        displayRows.setPadding(dp(26), dp(6), 0, 0);
        rear.addView(displayRows);
        displayView = text("", 13, INK_3);
        displayView.setPadding(dp(26), dp(10), 0, dp(4));
        rear.addView(displayView);
        rear.addView(divider());
        LinearLayout pair = row();
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(46), 1f);
        half.rightMargin = dp(10);
        TextView place = action("立即上屏", false, v -> {
            final int d = StateStore.displayId(this);
            Executors.newSingleThreadExecutor().execute(() -> {
                final String msg = RearController.place(MainActivity.this, d);
                ui.post(() -> { toast(msg); log(msg); });
            });
        });
        TextView wake = action("点亮背屏", false, v -> {
            final int d = StateStore.displayId(this);
            Executors.newSingleThreadExecutor().execute(() -> {
                RearController.wake(MainActivity.this, d);
                ui.post(() -> toast("已发送点亮"));
            });
        });
        pair.addView(place, half);
        pair.addView(wake, new LinearLayout.LayoutParams(0, dp(46), 1f));
        rear.addView(pair);
        root.addView(rear);

        // 04 · rear permissions
        LinearLayout perms = card("04", "背屏权限", "跑一次即可：display 列表与超时设置");
        perms.addView(divider());
        perms.addView(action("读取背屏信息", false, v -> probe()));
        root.addView(perms);

        // log
        LinearLayout logs = card("—", "日志", "最近的放置与读取结果");
        logView = text("还没有记录", 12, INK_3);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(5), 1f);
        logView.setPadding(dp(26), dp(12), 0, dp(2));
        logs.addView(logView);
        root.addView(logs);

        TextView foot = text("v" + Version.CODE, 12, INK_3);
        foot.setPadding(dp(4), dp(30), 0, 0);
        foot.setLetterSpacing(0.08f);
        root.addView(foot);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(BG);
        sc.setVerticalScrollBarEnabled(false);
        sc.setFillViewport(true);
        sc.setOnApplyWindowInsetsListener((v, insets) -> {
            final android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        sc.setFocusable(true);
        sc.setFocusableInTouchMode(true);
        sc.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        sc.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll = sc;
        sc.post(() -> sc.scrollTo(0, 0));
        return sc;
    }

    /** The one thing from the panel this page keeps: a big reading with its coloured seam. */
    private View statusBlock() {
        LinearLayout box = row();
        box.setGravity(Gravity.TOP);
        box.setPadding(dp(4), 0, dp(4), dp(2));

        statusSeam = new View(this);
        LinearLayout.LayoutParams seamLp = new LinearLayout.LayoutParams(dp(4),
                ViewGroup.LayoutParams.MATCH_PARENT);
        seamLp.rightMargin = dp(16);
        statusSeam.setLayoutParams(seamLp);
        statusSeam.setBackground(round(INK, dp(2)));
        box.addView(statusSeam);

        LinearLayout body = column();
        statusWord = text("还没取过", 34, INK);
        statusWord.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        body.addView(statusWord);

        statusMeta = text("", 14, INK_2);
        statusMeta.setPadding(0, dp(4), 0, 0);
        body.addView(statusMeta);

        statusAgents = text("", 13, INK_3);
        statusAgents.setPadding(0, dp(6), 0, 0);
        body.addView(statusAgents);

        box.addView(body);
        return box;
    }

    private View healthRow() {
        LinearLayout row = row();
        row.setPadding(dp(4), dp(16), dp(4), 0);
        healthRear = text("", 12, INK_2);
        healthPanel = text("", 12, INK_2);
        healthShizuku = text("", 12, INK_2);
        row.addView(healthRear, weight());
        row.addView(healthPanel, weight());
        row.addView(healthShizuku, weight());
        return row;
    }

    /** A group of controls on its own surface — the divider exists so rows do not run together. */
    private LinearLayout card(String number, String title, String hint) {
        LinearLayout card = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), CARD_EDGE);
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);

        LinearLayout head = row();
        TextView n = text(number, 12, INK_3);
        n.setTypeface(Typeface.MONOSPACE);
        head.addView(n, new LinearLayout.LayoutParams(dp(26),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(text(title, 15, INK), weight());
        card.addView(head);

        if (hint != null && !hint.isEmpty()) {
            TextView h = text(hint, 13, INK_3);
            h.setPadding(dp(26), dp(6), 0, 0);
            card.addView(h);
        }
        return card;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private View sourceField() {
        LinearLayout box = column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.leftMargin = dp(26);
        box.setLayoutParams(lp);

        sourceView = new EditText(this);
        sourceView.setSingleLine(true);
        sourceView.setText(StateStore.source(this));
        sourceView.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        sourceView.setTextSize(14);
        sourceView.setTextColor(INK);
        sourceView.setHintTextColor(INK_3);
        sourceView.setBackground(null);
        sourceView.setPadding(0, dp(8), 0, dp(10));
        box.addView(sourceView);

        View line = new View(this);
        line.setBackgroundColor(LINE);
        box.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return box;
    }

    private TextView action(String label, boolean primary, View.OnClickListener click) {
        TextView b = text(label, 14, primary ? BG : INK);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackground(primary
                ? state(round(INK, dp(12)), round(0xFFCFCFD6, dp(12)))
                : state(outline(), round(0xFF30303A, dp(12))));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        b.setLayoutParams(lp);
        return b;
    }

    // ── drawing helpers ──────────────────────────────────────────────────────────────────

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable outline() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), 0xFF3C3C47);
        return g;
    }

    private StateListDrawable state(android.graphics.drawable.Drawable normal,
                                    android.graphics.drawable.Drawable pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    /** Colours only the leading glyph: a check mark carries meaning, the sentence stays quiet. */
    private CharSequence markFirst(int color, String s) {
        android.text.SpannableString sp = new android.text.SpannableString(s);
        if (s.length() > 0) {
            sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    private CharSequence healthText(boolean on, String label) {
        android.text.SpannableString s =
                new android.text.SpannableString((on ? "● " : "○ ") + label);
        s.setSpan(new android.text.style.ForegroundColorSpan(on ? GOOD : INK_3), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    // ── behaviour ───────────────────────────────────────────────────────────────────────

    private void saveSource() {
        String v = sourceView.getText().toString().trim();
        if (v.isEmpty()) {
            toast("状态源不能为空");
            return;
        }
        StateStore.setSource(this, v);
        Executors.newSingleThreadExecutor().execute(() -> {
            AgentState s = StatusSource.fetch(v, 4000);
            ui.post(() -> {
                StateStore.publish(MainActivity.this, s);
                toast(s.ok ? "取到了：" + s.word() : "取不到：" + s.error);
                log((s.ok ? "OK  " : "ERR ") + v + (s.ok ? "" : " — " + s.error));
            });
        });
    }

    private void togglePanel() {
        boolean on = !StateStore.enabled(this);
        StateStore.setEnabled(this, on);
        if (on) {
            int d = StateStore.displayId(this);
            if (d < 0) d = RearController.findRearDisplay(this);
            if (d >= 0) StateStore.setDisplayId(this, d);
            StatusService.start(this);
            final int dd = d;
            Executors.newSingleThreadExecutor().execute(() -> {
                String msg = RearController.place(MainActivity.this, dd);
                ui.post(() -> { toast(msg); log(msg); });
            });
        } else {
            StatusService.stop(this);
            finishRear();
        }
        updatePanelButton();
        updateHealth();
    }

    private void finishRear() {
        Intent close = new Intent(RearActivity.ACTION_CLOSE).setPackage(getPackageName());
        sendBroadcast(close);
        toast("已关闭背屏面板");
    }

    private void probe() {
        Executors.newSingleThreadExecutor().execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("display list: ").append(String.join(" | ", Shell.displays())).append("\n");
            sb.append("rear timeout: ").append(RearController.rearTimeout()).append("\n");
            sb.append("strategy: ").append(StateStore.strategy(this));
            final String out = sb.toString();
            ui.post(() -> log(out));
        });
    }

    private void refreshShizuku() {
        String s;
        if (!Shell.available()) s = "✗ Shizuku 没在运行";
        else if (!Shell.granted()) s = "✗ 已连接，但本应用没有授权";
        else s = "✓ 已连接（uid " + safeUid() + "）";
        shizukuView.setText(markFirst(s.startsWith("✓") ? GOOD : BAD, s));
        shizukuView.setTextColor(INK_2);
        updateHealth();
    }

    private String safeUid() {
        try {
            return String.valueOf(rikka.shizuku.Shizuku.getUid());
        } catch (Throwable t) {
            return "?";
        }
    }

    private void refreshDisplay() {
        displayIds.clear();
        int chosen = StateStore.displayId(this);
        int rear = RearController.findRearDisplay(this);
        displayRows.removeAllViews();
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm != null) {
            for (android.view.Display d : dm.getDisplays()) {
                int id = d.getDisplayId();
                if (displayRows.getChildCount() > 0) displayRows.addView(rowDivider());
                displayIds.add(id);
                displayRows.addView(displayRow(id, rear, chosen));
            }
        }
        if (displayIds.isEmpty()) {
            TextView none = text("没有可用的 display", 13, INK_3);
            none.setPadding(0, dp(12), 0, dp(12));
            displayRows.addView(none);
        }
        displayView.setText("当前：" + RearController.describe(this, chosen >= 0 ? chosen : rear));
        updatePanelButton();
        updateHealth();
    }

    /** A hairline between two rows of a card — the reading aid the first cut was missing. */
    private View rowDivider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        v.setLayoutParams(lp);
        return v;
    }

    private View displayRow(int id, int rear, int chosen) {
        final boolean selected = id == chosen;
        LinearLayout row = row();
        row.setPadding(0, dp(13), 0, dp(13));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            if (id == android.view.Display.DEFAULT_DISPLAY) {
                toast("背屏面板不能放在主屏；请选择背屏那一条");
                return;
            }
            StateStore.setDisplayId(MainActivity.this, id);
            refreshDisplay();
        });

        TextView label = text("display " + id + (id == rear ? "  ·  背屏" : "  ·  主屏"),
                14, selected ? INK : INK_3);
        row.addView(label, weight());
        if (selected) {
            TextView check = text("✓", 15, GOOD);
            check.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(check);
        }
        return row;
    }

    private void updatePanelButton() {
        boolean on = StateStore.enabled(this);
        panelButton.setText(on ? "停止背屏显示" : "开始背屏显示");
        panelButton.setTextColor(BG);
        panelButton.setBackground(state(round(INK, dp(12)), round(0xFFCFCFD6, dp(12))));
    }

    private void updateHealth() {
        if (healthRear == null) return;
        boolean enabled = StateStore.enabled(this);
        boolean up = RearActivity.alive;
        boolean granted = Shell.available() && Shell.granted();
        healthRear.setText(healthText(enabled, "背屏" + (enabled ? "已开" : "已关")));
        healthPanel.setText(healthText(up, "面板" + (up ? "在屏" : "未上屏")));
        healthShizuku.setText(healthText(granted, "Shizuku" + (granted ? "已授权" : "未授权")));
    }

    private void log(String s) {
        if (logView == null) return;
        String cur = logView.getText().toString();
        if (cur.startsWith("还没有记录")) cur = "";
        String next = s + "\n" + cur;
        String[] lines = next.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 10); i++) b.append(lines[i]).append('\n');
        logView.setText(b.toString());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    public void onState(AgentState s) { ui.post(() -> render(s)); }

    private void render(AgentState s) {
        if (statusWord == null) return;
        final int accent = s.accent();
        final boolean idleState = s.ok && AgentState.IDLE.equals(s.status);
        statusWord.setText(s.word());
        statusWord.setTextColor(!s.ok ? 0xFFFCA5A5 : (idleState ? INK_2 : INK));
        statusSeam.setBackground(round(accent, dp(2)));

        String meta;
        if (!s.ok) {
            meta = s.error.isEmpty() ? "状态源没有响应" : s.error;
        } else {
            meta = s.active + " 会话" + (s.detail.isEmpty() ? "" : "  ·  " + s.detail);
            if (s.fetchedMs > 0) {
                long age = Math.max(0, (System.currentTimeMillis() - s.fetchedMs) / 1000L);
                meta += "  ·  " + (age < 5 ? "刚刚" : (age < 60 ? age + " 秒前" : (age / 60) + " 分钟前"));
            }
        }
        statusMeta.setText(meta);
        statusMeta.setTextColor(s.ok ? INK_2 : BAD);
        statusAgents.setText(agentLine(s));
        updateHealth();
    }

    private String agentLine(AgentState s) {
        if (!s.ok || s.agents.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String a : s.agents) {
            if (b.length() > 0) b.append("  ·  ");
            if ("dsh".equals(a)) b.append("DSH");
            else if ("claude".equals(a)) b.append("Claude Code");
            else if ("codex".equals(a)) b.append("Codex");
            else b.append(a);
        }
        return b.append(" 在跑").toString();
    }
}
