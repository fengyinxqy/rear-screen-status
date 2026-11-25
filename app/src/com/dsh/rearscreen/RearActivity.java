package com.dsh.rearscreen;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.dsh.rearscreen.data.AgentState;
import com.dsh.rearscreen.data.StateStore;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the back of the phone shows.
 *
 * A plain canvas view rather than a view hierarchy: the panel is one screen of a few words,
 * it is redrawn whenever a reading arrives, and it is killed and relaunched constantly — the
 * less it owns, the less there is to restore.
 *
 * The panel is 976x596 with the left 296 px behind the camera island, so every element is laid
 * out inside the part that can actually be seen.
 *
 * Design notes, since a rear screen is a strange medium:
 *  - It is OLED, so black pixels are off pixels: the background stays black and only thin
 *    shapes are lit.
 *  - It is read at arm's length, often at an angle, often while the phone lies face down.
 *    One word has to carry the message; everything else is supporting detail and is kept small.
 *  - "Is anything running?" is the question that can actually be answered, so that state gets
 *    motion; an idle panel stays perfectly still, which is what makes the motion mean something.
 *
 * The layout is a fixed grid, not a stack of centred lines (see the tokens below). Four bands —
 * the word, the counts, the task, the age — sit on the same baselines in every state, so the
 * panel never jumps when the reading changes, and only the amount of lit type changes with the
 * state's importance. The one piece of furniture is a lit seam along the camera island's edge:
 * it carries the state colour, and drawing it against the hardware is what gives the panel its
 * axis — which is why nothing else needs a frame, a divider or a centred column.
 */
public class RearActivity extends Activity {

    /** Read by the service's watchdog to tell "up" from "killed". */
    public static volatile boolean alive = false;

    /**
     * This panel's own task id, recorded as soon as we are created.
     *
     * It is the one identifier that is always correct: the shell helper needs a task id to ask
     * "where is this?" and to move it, and deriving it by scanning a task listing was the source
     * of a long run of wrong answers (the settings screen's task was matched instead, and the
     * listing itself omitted freshly started tasks).
     */
    public static volatile int taskId = -1;

    /** Sent by the settings screen when the panel is switched off. */
    public static final String ACTION_CLOSE = "com.dsh.rearscreen.CLOSE_REAR";

    /**
     * Pins one state on the panel for inspection from adb:
     *
     *   adb shell am broadcast -a com.dsh.rearscreen.PREVIEW --es kind developing -p com.dsh.rearscreen
     *   adb shell am broadcast -a com.dsh.rearscreen.PREVIEW --esn kind        -p com.dsh.rearscreen
     *
     * The four states are otherwise only reachable by driving a real agent, and the one that
     * needs the most care — the disconnected one — needs the PC side taken down to see. While a
     * state is pinned, live readings are ignored so a photograph can be taken without the poller
     * overwriting it; --esn kind (a null extra) returns the panel to live data.
     *
     * Broadcast by package: targeting -n com.dsh.rearscreen/.MainActivity does not deliver,
     * because that component resolves to the settings Activity and not to the receiver.
     */
    public static final String ACTION_PREVIEW = "com.dsh.rearscreen.PREVIEW";

    private static final CopyOnWriteArrayList<PanelView> PANELS = new CopyOnWriteArrayList<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ── the grid, in the rear screen's own pixels (976 x 596) ──────────────────────────────
    // Everything below is expressed once, here, and scaled by h/596 at draw time. A state
    // change never moves a baseline; it only changes how much type is lit.
    private static final float DESIGN_H = 596f;
    /** Clear of the camera island (its cutout is 296 px wide). */
    private static final float CONTENT_LEFT = 356f;
    private static final float CONTENT_RIGHT = 932f;
    /** Sits on the island's seam, so the lit bar belongs to the hardware, not to a box. */
    private static final float SPINE_X = 330f;
    private static final float SPINE_W = 8f;
    private static final float WORD_BASELINE = 240f;
    private static final float DATA_BASELINE = 322f;
    private static final float DETAIL_BASELINE = 380f;
    /** The reading's age closes the column, where the eye already is — not floating alone. */
    private static final float META_BASELINE = 430f;
    private static final float WORD_SIZE = 124f;
    private static final float NUM_SIZE = 52f;
    /** Read at arm's length from an angle: the unit has to survive, not just the number. */
    private static final float UNIT_SIZE = 28f;
    private static final float DETAIL_SIZE = 25f;
    private static final float LABEL_SIZE = 18f;
    private static final float META_SIZE = 19f;
    private static final float ACTION_SIZE = 22f;

    // Colours are the state accent plus three steps of neutral; nothing is tinted to add
    // information the type already carries.
    private static final int COLOR_TEXT = 0xFFF3F3F6;
    private static final int COLOR_DIM = 0xFF9A9AA2;
    private static final int COLOR_FAINT = 0xFF6E6E78;
    private static final int COLOR_ERROR = 0xFFF87171;
    private static final int COLOR_ERROR_SOFT = 0xFFFCA5A5;
    private static final int COLOR_WARN = 0xFFF59E0B;
    private static final int COLOR_OFFLINE = 0xFFEF4444;
    /** Idle is the one state whose word steps down from white: nothing is happening. */
    private static final int COLOR_IDLE_WORD = 0xFF9A9AA2;

    private static final Typeface FACE_WORD = Typeface.create("sans-serif-black", Typeface.NORMAL);
    private static final Typeface FACE_NUM = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface FACE_UNIT = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface FACE_TEXT = Typeface.create("sans-serif", Typeface.NORMAL);

    /** One travel cycle of the working beam. */
    private static final long CYCLE_MS = 2200L;
    private static final long TICK_MS = 40L;

    // ── agent marks ───────────────────────────────────────────────────────────────────────
    /** The column of marks on the right, in a fixed order so their place is learnable. */
    private static final String[] MARK_ORDER = {"dsh", "claude", "codex"};
    private static final float MARK_BOX = 80f;
    private static final float MARK_GAP = 26f;

    /** Non-null while a state is pinned for inspection; see {@link #ACTION_PREVIEW}. */
    private static volatile String preview = null;

    /** Called from the poller thread; posts to the panels. */
    public static void push(final AgentState s) {
        if (preview != null) return;
        MAIN.post(() -> {
            for (PanelView v : PANELS) {
                try { v.set(s); } catch (Throwable ignored) { }
            }
        });
    }

    /** Pins {@code kind} (developing|idle|offline), or a null/empty kind to unpin. */
    public static void showPreview(String kind) {
        final String pin = kind == null || kind.trim().isEmpty() ? null : kind.trim();
        preview = pin;
        MAIN.post(() -> {
            for (PanelView v : PANELS) {
                try { v.setPreview(pin); } catch (Throwable ignored) { }
            }
        });
    }

    /**
     * A synthetic reading for {@link #ACTION_PREVIEW}: the shapes real sessions produce, with
     * counts that differ between states so the hierarchy can actually be checked.
     */
    private static AgentState previewState(String kind) {
        long now = System.currentTimeMillis();
        String origin = "http://192.0.2.23:8770/status.json";
        switch (kind) {
            case "idle":
                return new AgentState(true, "", 0, AgentState.IDLE, null, "", now, now - 12_000L, origin);
            case "offline":
                return new AgentState(false, "Connect timed out (192.0.2.23:8770)", 0, "", null,
                        "", 0L, now, origin);
            case "claude":
                return new AgentState(true, "", 2, AgentState.DEV, marks("claude"),
                        "fix-login-retry", now, now - 2_000L, origin);
            case "codex":
                return new AgentState(true, "", 1, AgentState.DEV, marks("codex"),
                        "fix-login-retry", now, now - 2_000L, origin);
            case "mixed":
                return new AgentState(true, "", 3, AgentState.DEV, marks("dsh", "claude", "codex"),
                        "fix-login-retry", now, now - 2_000L, origin);
            default:
                return new AgentState(true, "", 2, AgentState.DEV, marks("dsh"),
                        "fix-login-retry", now, now - 2_000L, origin);
        }
    }

    private static java.util.Set<String> marks(String... names) {
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(names));
    }

    private PanelView panel;

    /**
     * Fallback poller.
     *
     * The panel normally just listens: StatusService does the fetching, so one request serves
     * the panel and the settings screen. But a panel can outlive the service (it is a different
     * process state, and HyperOS kills them independently), and a panel that shows a stale
     * reading forever is worse than one that fetches its own. This only runs when the service
     * is not up, so the two never poll at once.
     */
    private final Runnable fallbackPoll = new Runnable() {
        @Override public void run() {
            if (StatusService.isRunning()) {
                MAIN.postDelayed(this, 10_000);
                return;
            }
            final String source = StateStore.source(RearActivity.this);
            new Thread(() -> {
                final AgentState s = com.dsh.rearscreen.data.StatusSource.fetch(source, 4000);
                MAIN.post(() -> {
                    StateStore.publish(RearActivity.this, s);
                    if (!isFinishing()) MAIN.postDelayed(fallbackPoll, Math.max(3, StateStore.intervalSec(RearActivity.this)) * 1000L);
                });
            }, "panel-fallback-poll").start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Deliberately NOT FLAG_TURN_SCREEN_ON: placement starts this activity on the FRONT
        // display and moves the task afterwards, so a turn-on request lights the front screen —
        // the glass wakes up with the panel on it for the length of the move. The rear display is
        // lit explicitly by the placement instead (input -d <rear> KEYCODE_WAKEUP).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        taskId = getTaskId();
        panel = new PanelView(this);
        setContentView(panel);
        panel.set(StateStore.load(this));
        // Hidden and unpainted from the very first frame; see the guard below.
        applyHidden(true);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeReceiver, new android.content.IntentFilter(ACTION_CLOSE),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, new android.content.IntentFilter(ACTION_CLOSE));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        PANELS.add(panel);
        panel.attach();
        if (preview != null) panel.setPreview(preview);
        MAIN.removeCallbacks(fallbackPoll);
        MAIN.postDelayed(fallbackPoll, 300);
        guardTicks = 0;
        MAIN.removeCallbacks(displayGuard);
        MAIN.post(displayGuard);
    }

    /**
     * The panel must stay invisible and untouchable until it is really on the rear display.
     *
     * HyperOS will not start an activity straight onto the rear panel: the launcher there has a
     * whitelist a sideloaded app is not on, and `am start --display 1` is refused with result
     * code 102. Placement therefore starts the panel on the FRONT display and moves the task
     * afterwards. During that gap this activity used to sit full screen on top of the lock
     * screen, with FLAG_SHOW_WHEN_LOCKED and a live touch surface, so the phone looked frozen:
     * the panel covered the top of the front screen and swallowed every tap. When the move
     * failed, it stayed there.
     *
     * So the window now starts transparent and non-interactive and only becomes real once the
     * display it sits on matches the configured rear display. The tick limit exists so a
     * placement that never lands leaves instead of loitering invisibly over the front screen.
     */
    private static final long GUARD_INTERVAL_MS = 150;
    private static final int GUARD_MAX_TICKS = 80;   // ~12 s, past place() own wait
    /** While on the rear display the check keeps running at this pace, so a move back is caught. */
    private static final long GUARD_VISIBLE_INTERVAL_MS = 500;
    private int guardTicks = 0;

    private void applyHidden(boolean hidden) {
        // The window-level alpha is what the guard uses, but it takes a window-manager round trip
        // to land and the first buffer can beat it. Painting is switched off here as well, so a
        // frame that slips through that gap is empty rather than a black card on the front screen.
        if (panel != null) panel.setPaintable(!hidden);
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.alpha = hidden ? 0f : 1f;
            if (hidden) {
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {
            // A window that is already gone is not worth failing a redraw over.
        }
    }

    private final Runnable displayGuard = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;

            // No rear display chosen (yet) counts as "not on target": this activity would be on
            // the MAIN screen, where a full-screen black card that eats every tap once froze the
            // phone. An unplaced panel must never be what the display shows.
            final int target = StateStore.displayId(RearActivity.this);
            final Display d = getDisplay();
            final int here = d == null ? android.view.Display.DEFAULT_DISPLAY : d.getDisplayId();
            final boolean onTarget = target > android.view.Display.DEFAULT_DISPLAY && here == target;

            if (onTarget) {
                guardTicks = 0;
                applyHidden(false);
                // Keep ticking. A task can be reparented back to the front display — placement
                // does exactly that on the way in — and a window that was already visible on the
                // rear must not stay painted on the front when that happens.
                MAIN.postDelayed(this, GUARD_VISIBLE_INTERVAL_MS);
                return;
            }

            applyHidden(true);
            if (++guardTicks > GUARD_MAX_TICKS) {
                // Never landed: leave rather than loiter invisibly over the front screen.
                finish();
                return;
            }
            MAIN.postDelayed(this, GUARD_INTERVAL_MS);
        }
    };

    @Override
    protected void onPause() {
        MAIN.removeCallbacks(displayGuard);
        alive = false;
        PANELS.remove(panel);
        panel.detach();
        MAIN.removeCallbacks(fallbackPoll);
        super.onPause();
    }

    private final android.content.BroadcastReceiver closeReceiver =
            new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, android.content.Intent i) {
                    finishAndRemoveTask();
                }
            };

    @Override
    protected void onDestroy() {
        if (getTaskId() == taskId) taskId = -1;
        try { unregisterReceiver(closeReceiver); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private final class PanelView extends View implements StateStore.Listener {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final GestureDetector gestures;

        private AgentState state = AgentState.offline("还没取过");
        /** 0..1 over {@link #CYCLE_MS}; drives every animation so they share one rhythm. */
        private float phase;
        private boolean ticking;
        private long tickEpoch;

        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (!ticking) return;
                long since = android.os.SystemClock.uptimeMillis() - tickEpoch;
                phase = (since % CYCLE_MS) / (float) CYCLE_MS;
                invalidate();
                postDelayed(this, TICK_MS);
            }
        };

        /** False until the guard has confirmed this window is really on the rear display. */
        private boolean paintable;

        PanelView(Context ctx) {
            super(ctx);
            // Transparent, not black: this view is created while the activity is still on the
            // front display, and its black background is exactly what must never be painted there.
            setBackgroundColor(Color.TRANSPARENT);
            gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    // A tap asks for a fresh reading without waiting for the next tick.
                    StatusService.start(getContext());
                    return true;
                }
            });
        }

        void set(AgentState s) {
            if (preview != null) return;   // a pinned state wins until it is cleared
            apply(s);
        }

        /** Shows a pinned state, or the live reading when {@code kind} is null. */
        void setPreview(String kind) {
            apply(kind == null ? StateStore.current() : previewState(kind));
        }

        private void apply(AgentState s) {
            this.state = s;
            // Motion only while work is actually happening.
            final boolean wantTick = s.ok && AgentState.DEV.equals(s.status);
            if (wantTick && !ticking) {
                ticking = true;
                tickEpoch = android.os.SystemClock.uptimeMillis();
                post(ticker);
            } else if (!wantTick && ticking) {
                ticking = false;
                removeCallbacks(ticker);
            }
            invalidate();
        }

        void attach() { StateStore.addListener(this); invalidate(); }
        void detach() {
            StateStore.removeListener(this);
            ticking = false;
            removeCallbacks(ticker);
        }

        @Override public void onState(AgentState s) {
            // StateStore publishes from whichever thread fetched (StatusService polls on its own
            // loop). Touching a view from there throws CalledFromWrongThreadException on the
            // calling thread and takes the whole process — panel included — down with it.
            if (Looper.myLooper() == Looper.getMainLooper()) set(s);
            else MAIN.post(() -> set(s));
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            gestures.onTouchEvent(e);
            return true;
        }

        /** Paints only when the panel is known to be on the rear display; see {@link #applyHidden}. */
        void setPaintable(boolean value) {
            if (paintable == value) return;
            paintable = value;
            setBackgroundColor(value ? Color.BLACK : Color.TRANSPARENT);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!paintable) return;
            final int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            c.drawColor(Color.BLACK);

            final float k = h / DESIGN_H;
            final float left = CONTENT_LEFT * k;
            final float right = Math.min(w - 30f * k, CONTENT_RIGHT * k);

            final boolean off = !state.ok;
            final boolean working = !off && AgentState.DEV.equals(state.status);

            final boolean idle = !off && AgentState.IDLE.equals(state.status);
            // Text stops short of the mark column instead of running underneath it.
            final float textRight = right - (MARK_BOX + 26f) * k;

            // The four bands sit on the same baselines in every state, and the seam spans the
            // same two points: what changes between states is how much of the skeleton is lit,
            // never where it is. That is what makes the layout learnable from the corner of an
            // eye — you know where to look without reading anything.
            drawSpine(c, k, off, working);
            drawWord(c, state.word(), left, WORD_BASELINE * k, WORD_SIZE * k,
                    (textRight - left) * 0.96f,
                    off ? COLOR_ERROR_SOFT : (idle ? COLOR_IDLE_WORD : COLOR_TEXT));
            drawReadout(c, k, left, textRight, DATA_BASELINE * k);
            drawDetail(c, k, left, textRight, DETAIL_BASELINE * k);
            drawAge(c, k, left, META_BASELINE * k);
            drawMarks(c, k, right);
        }

        /**
         * The panel's only piece of furniture: a lit seam along the camera island's edge.
         *
         * It carries the state's colour, and because it sits against the hardware rather than
         * floating in the middle it gives the whole layout its axis — no frame, no divider, no
         * centred column needed. It moves while work is in progress and is still otherwise.
         */
        private void drawSpine(Canvas c, float k, boolean off, boolean working) {
            final float x = SPINE_X * k;
            final float width = SPINE_W * k;
            final float top = (WORD_BASELINE - WORD_SIZE * 0.80f) * k;
            final float bottom = (META_BASELINE + 14f) * k;
            final int color = off ? COLOR_OFFLINE : state.accent();

            final int alpha = off ? 165 : (working ? 70 : 55);
            seam(c, x, top, bottom, width, color, alpha);

            if (!working) return;
            // A bright segment travelling up and down: "still running", readable across a desk.
            final float span = bottom - top;
            final float beam = span * 0.5f;
            float t = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
            t = (float) (0.5 - 0.5 * Math.cos(t * Math.PI));
            final float y0 = top - beam + (span + beam) * t;

            final int solid = Color.argb(235, Color.red(color), Color.green(color), Color.blue(color));
            final int clear = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
            c.save();
            c.clipRect(x, top, x + width, bottom);
            p.setShader(new LinearGradient(0f, y0, 0f, y0 + beam,
                    new int[]{clear, solid, clear}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            r.set(x, y0, x + width, y0 + beam);
            c.drawRect(r, p);
            p.setShader(null);
            c.restore();
        }

        /** A soft vertical bar: invisible at both ends, so it never reads as a cut-off frame. */
        private void seam(Canvas c, float x, float top, float bottom, float width, int color, int alpha) {
            final int clear = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
            final int solid = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
            p.setShader(new LinearGradient(x, top, x, bottom, new int[]{clear, solid, solid, clear},
                    new float[]{0f, 0.18f, 0.82f, 1f}, Shader.TileMode.CLAMP));
            r.set(x, top, x + width, bottom);
            c.drawRoundRect(r, width / 2f, width / 2f, p);
            p.setShader(null);
        }

        /**
         * The right column: a mark for each agent that is running right now, and nothing else.
         *
         * An empty slot for an agent that is installed but idle would say "something could run
         * here", which is not what the panel is for. The stack is centred on the same axis the
         * full column would occupy, so one mark, two marks and three marks all sit as a block
         * rather than sliding around.
         */
        private void drawMarks(Canvas c, float k, float right) {
            if (!state.ok) return;
            final String[] running = new String[MARK_ORDER.length];
            int count = 0;
            for (String name : MARK_ORDER) {
                if (state.agents.contains(name)) running[count++] = name;
            }
            if (count == 0) return;

            final float size = MARK_BOX * k;
            final float gap = MARK_GAP * k;
            final float total = count * size + (count - 1) * gap;
            // The centre of the full three-slot column: what the eye already knows as "the marks".
            final float columnTop = (META_BASELINE + 12f) * k
                    - (MARK_ORDER.length * size + (MARK_ORDER.length - 1) * gap);
            final float columnCentre = columnTop + (MARK_ORDER.length * size
                    + (MARK_ORDER.length - 1) * gap) / 2f;
            final float top = columnCentre - total / 2f;

            for (int i = 0; i < count; i++) {
                drawMark(c, running[i], right - size, top + i * (size + gap), size, 255);
            }
        }

        /** @param alpha 255 for a running agent, a low value for one that is merely present. */
        private void drawMark(Canvas c, String name, float x, float y, float s, int alpha) {
            p.setTypeface(FACE_TEXT);
            p.setColor(0xffffffff);
            p.setAlpha(alpha);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(s * 0.72f);
            String label = "dsh".equals(name) ? "D" : "claude".equals(name) ? "C" : "X";
            c.drawText(label, x + s / 2, y + (s - p.ascent() - p.descent()) / 2, p);
            p.setAlpha(255);
        }

        /** One word, sized to the panel and shrinking only if a longer state word needs it. */
        private void drawWord(Canvas c, String word, float x, float baseline, float size,
                              float maxWidth, int color) {
            p.setTypeface(FACE_WORD);
            p.setTextSize(size);
            p.setLetterSpacing(0.02f);
            p.setTextAlign(Paint.Align.LEFT);
            float measured = p.measureText(word);
            if (measured > maxWidth && measured > 0f) {
                p.setTextSize(size * maxWidth / measured);
            }
            p.setColor(color);
            c.drawText(word, x, baseline, p);
            p.setLetterSpacing(0f);
        }

        /**
         * The counts, as numbers with a unit.
         *
         * The panel used to write a sentence — "会话 2 · 等你 1" — which has to be read. A number
         * with a unit is a value you can spot, and size does the separating.
         */
        private void drawReadout(Canvas c, float k, float left, float right, float baseline) {
            final boolean off = !state.ok;

            if (off) {
                // The panel says the human thing; the exact cause belongs on the settings screen,
                // which already prints it. An English socket message here read as a log line on a
                // surface that has to be understood at a glance.
                p.setTypeface(FACE_TEXT);
                p.setTextSize(DETAIL_SIZE * k);
                p.setTextAlign(Paint.Align.LEFT);
                p.setColor(COLOR_ERROR);
                c.drawText(fit(offlineLine(), right - left), left, baseline, p);
            } else if (state.active <= 0) {
                p.setTypeface(FACE_TEXT);
                p.setTextSize(DETAIL_SIZE * k);
                p.setTextAlign(Paint.Align.LEFT);
                p.setColor(COLOR_DIM);
                c.drawText("没有在跑的会话", left, baseline, p);
            } else {
                countPair(c, left, baseline, k, state.active, "会话");
            }
        }

        /** One short Chinese line for the disconnected state. */
        private String offlineLine() {
            final String e = state.error == null ? "" : state.error;
            if (e.contains("没配置") || e.contains("未配置")) return "还没有配置状态源";
            if (e.contains("JSON") || e.contains("json")) return "状态源返回的数据看不懂";
            return "状态源没有响应";
        }

        /** Host part of the configured source, for when the source is the thing at fault. */
        private String host() {
            String s = state.origin == null ? "" : state.origin.trim();
            if (s.isEmpty()) return "";
            s = s.replace("http://", "").replace("https://", "").replace("file:", "");
            final int slash = s.indexOf('/');
            if (slash > 0) s = s.substring(0, slash);
            return s.length() > 40 ? s.substring(0, 39) + "…" : s;
        }

        /**
         * One "2 会话" pair: the number to spot, the unit to know what it counts.
         *
         * Tabular figures keep the unit from shifting when the count goes 1 -> 2, so the word
         * beside it stays put between readings.
         */
        private void countPair(Canvas c, float x, float baseline, float k, int n, String unit) {
            p.setFontFeatureSettings("tnum");
            p.setTypeface(FACE_NUM);
            p.setTextSize(NUM_SIZE * k);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(COLOR_TEXT);
            final String num = String.valueOf(n);
            c.drawText(num, x, baseline, p);
            x += p.measureText(num) + 12f * k;
            p.setTypeface(FACE_UNIT);
            p.setTextSize(UNIT_SIZE * k);
            p.setColor(COLOR_DIM);
            c.drawText(unit, x, baseline, p);
            p.setFontFeatureSettings(null);
        }

        /**
         * How old the reading is, as the last line of the column.
         *
         * It used to sit alone in the far corner, which read as a stray label; ending the same
         * column the text runs down makes it the reading's footnote instead. Offline, this line
         * is where the panel says that a tap retries — the only action it has.
         */
        private void drawAge(Canvas c, float k, float left, float baseline) {
            final boolean off = !state.ok;
            final long ageSec = state.fetchedMs <= 0 ? Long.MAX_VALUE
                    : (System.currentTimeMillis() - state.fetchedMs) / 1000L;
            final boolean stale = ageSec > Math.max(30, StateStore.intervalSec(getContext()) * 3L);

            p.setTypeface(FACE_TEXT);
            p.setTextAlign(Paint.Align.LEFT);

            if (off) {
                // The panel's only action, so it gets action-size type rather than caption size.
                p.setTextSize(ACTION_SIZE * k);
                p.setColor(COLOR_ERROR_SOFT);
                c.drawText("点按重试", left, baseline, p);
                return;
            }
            p.setTextSize(META_SIZE * k);

            final String when = ageText(ageSec, stale);
            p.setColor(stale ? COLOR_WARN : COLOR_FAINT);
            c.drawText(when, left + 15f * k, baseline, p);
            p.setColor(stale ? COLOR_WARN : state.accent());
            c.drawCircle(left + 3.5f * k, baseline - 6.5f * k, 3.4f * k, p);
        }

        /** Age in words that get coarser as they grow: "128 秒前" is harder to place than "2 分钟前". */
        private String ageText(long ageSec, boolean stale) {
            final String tail = stale ? "未更新" : "前";
            if (ageSec < 5) return "刚刚";
            if (ageSec < 60) return ageSec + " 秒" + tail;
            final long min = ageSec / 60L;
            if (min < 60) return min + " 分钟" + tail;
            return (min / 60L) + " 小时" + tail;
        }

        /**
         * The task name, labelled so it is not mistaken for more status chrome.
         *
         * Nothing is invented here: with no task name the line stays empty, because the word and
         * the counts have already said everything that is true.
         */
        private boolean hasDetail() {
            return state.ok && state.detail != null && !state.detail.trim().isEmpty();
        }

        private void drawDetail(Canvas c, float k, float left, float right, float baseline) {
            if (!state.ok) {
                // Which host it cannot reach is the one useful fact left when the source is
                // down; it goes here, quiet, under the plain-language line.
                final String where = host();
                if (where.isEmpty()) return;
                p.setTypeface(FACE_TEXT);
                p.setTextSize(META_SIZE * k);
                p.setTextAlign(Paint.Align.LEFT);
                p.setColor(COLOR_FAINT);
                c.drawText(fit(where, right - left), left, baseline, p);
                return;
            }
            if (!hasDetail()) return;
            final String detail = state.detail.trim();

            float x = left;
            p.setTypeface(FACE_UNIT);
            p.setTextSize(LABEL_SIZE * k);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(COLOR_FAINT);
            c.drawText("任务", x, baseline - 2f * k, p);
            x += p.measureText("任务") + 14f * k;

            p.setTypeface(FACE_TEXT);
            p.setTextSize(DETAIL_SIZE * k);
            p.setColor(COLOR_DIM);
            c.drawText(fit(detail, right - x), x, baseline, p);
        }

        /** Shortens text until it fits, with an ellipsis; uses the paint's current size. */
        private String fit(String text, float maxWidth) {
            if (maxWidth <= 0f) return "";
            if (p.measureText(text) <= maxWidth) return text;
            int n = text.length();
            while (n > 1 && p.measureText(text.substring(0, n) + "…") > maxWidth) n--;
            return text.substring(0, n) + "…";
        }
    }
}
