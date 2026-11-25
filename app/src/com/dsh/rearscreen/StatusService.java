package com.dsh.rearscreen;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.dsh.rearscreen.data.AgentState;
import com.dsh.rearscreen.data.StateStore;
import com.dsh.rearscreen.data.StatusSource;
import com.dsh.rearscreen.shizuku.RearController;
import com.dsh.rearscreen.shizuku.Shell;



/**
 * The engine.
 *
 * Two jobs on one loop:
 *  - fetch the state source and publish the reading (the panel and the settings screen both
 *    listen, so one fetch serves both);
 *  - make sure the rear panel is alive: a killed activity runs no code and cannot revive
 *    itself, so something outside it has to check. This service is that something.
 *
 * The loop is paced by the request itself rather than by a timer (see runLoop).
 *
 * It is a foreground service because HyperOS freezes or clears ordinary background processes
 * (notably while the Camera runs) and a frozen poller looks exactly like a dead one.
 */
public class StatusService extends Service implements StateStore.Listener {

    private static final String TAG = "RearStatus";
    private static final String CHANNEL = "rearscreen";
    private static final int NOTE_ID = 41;
    private static final String ACTION_STOP = "com.dsh.rearscreen.STOP";

    private static volatile boolean running = false;

    private volatile boolean stopped;
    private Thread loop;
    /** Last time an iteration finished, used to notice a wedged poll loop. */
    private volatile long lastBeat;
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    /** Last reading applied, so the loop can tell "woken by a change" from "answered at once". */
    private String lastSignature;
    /** Whether the last fetch succeeded; one failure is retried before it is reported. */
    private boolean wasOk;
    private int tick;
    private long lastPlaceAttempt;

    public static boolean isRunning() { return running; }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, StatusService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, StatusService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        StateStore.addListener(this);
        createChannel();
        startForeground(NOTE_ID, buildNotification(StateStore.load(this)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startLoop();
        return START_STICKY;
    }

    private void startLoop() {
        if (loop != null && loop.isAlive()) return;
        stopped = false;
        loop = new Thread(this::runLoop, "status-loop");
        loop.setDaemon(true);
        loop.start();
        watchdog.removeCallbacks(checkLoop);
        watchdog.postDelayed(checkLoop, LOOP_CHECK_MS);
    }

    /**
     * Restarts a poll loop that stopped making progress.
     *
     * Observed once in the field: the thread was alive, the service was foreground, and the phone
     * had simply stopped asking — silently, until the panel went stale. A background thread that
     * can wedge must be watched by something that is not itself that thread.
     */
    private final Runnable checkLoop = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            final long idle = System.currentTimeMillis() - lastBeat;
            if (lastBeat > 0 && idle > LOOP_STALL_MS) {
                Log.w(TAG, "poll loop made no progress for " + idle / 1000 + "s, restarting it");
                Thread dead = loop;
                loop = null;
                if (dead != null) dead.interrupt();
                startLoop();
            }
            watchdog.postDelayed(this, LOOP_CHECK_MS);
        }
    };

    /**
     * One loop, paced by the long-poll request instead of by a timer.
     *
     * A fixed tick meant a reading was on average five seconds old and up to ten — exactly the
     * lag the panel showed. Now each request asks the daemon to hold it until something
     * changes, and only the remainder of the interval is slept off: the same traffic, the same
     * ten-second cadence, but a change lands in a fraction of a second. A daemon that answers
     * immediately (or not at all) simply spends the rest of the interval asleep, so this cannot
     * busy-loop against an older or dead server.
     */
    private static final long LOOP_CHECK_MS = 30_000L;
    private static final long LOOP_STALL_MS = 45_000L;

    private void runLoop() {
        while (!stopped && running) {
            lastBeat = System.currentTimeMillis();
            final long started = System.currentTimeMillis();
            final int sec = Math.max(3, StateStore.intervalSec(this));
            final int waitSec = Math.max(0, sec - 2);
            AgentState fetched = null;
            try {
                final String source = StateStore.source(this);
                // Nothing seen yet: ask once without a hold so the first paint is immediate.
                final int wait = lastSignature == null ? 0 : waitSec;
                fetched = StatusSource.fetch(source, 4000, wait, lastSignature);
                // A held connection can be dropped by the phone's Wi-Fi going to sleep, and one
                // dropped read is not "the source is down". Retry once, briefly, before painting
                // the panel red for what is usually a reconnect.
                if (!fetched.ok && wasOk) fetched = StatusSource.fetch(source, 4000, 0, null);
                wasOk = fetched.ok;
                applyReading(fetched);
            } catch (Throwable t) {
                Log.w(TAG, "tick failed", t);
            }

            final long took = System.currentTimeMillis() - started;
            final long rest = pauseAfter(fetched, took, sec, waitSec);
            if (rest > 0) {
                try {
                    Thread.sleep(rest);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /**
     * How long to wait before asking again.
     *
     * A held request that came back early came back because the reading changed — so ask again
     * at once and go back to being held. Sleeping a full interval there would leave the loop
     * deaf to whatever happens next, which is exactly how a long poll ends up as slow as the
     * timer it replaced. A request that used its whole wait was a quiet interval: also re-arm
     * promptly, the daemon is doing the pacing. Only a daemon that answers immediately (an
     * older build, or one that cannot hold) and a dead source are paced by the interval, so
     * nothing can busy-loop.
     */
    private long pauseAfter(AgentState fetched, long tookMs, int sec, int waitSec) {
        final long interval = sec * 1000L;
        if (fetched == null || !fetched.ok) return Math.max(2000L, interval - tookMs);
        if (waitSec <= 0) return Math.max(0L, interval - tookMs);

        final String sig = fetched.status + "|" + fetched.active + "|" + fetched.detail;
        final boolean changed = !sig.equals(lastSignature);
        lastSignature = sig;

        final boolean heldWholeWait = tookMs >= waitSec * 1000L - 400L;
        if (changed || heldWholeWait) return 150L;
        return Math.max(0L, interval - tookMs);   // server answers at once: pace it
    }

    // Diagnostic: one line per poll, so a panel that stops updating can be told apart from a
    // service that stopped polling. Left at debug level; it is four short lines a minute.
    private void traceTick(AgentState s) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return;
        Log.d(TAG, "tick #" + tick + " ok=" + s.ok + " status=" + s.status
                + " panel=" + (RearActivity.alive ? "up" : "down"));
    }

    /**
     * Placement runs here, off the poll thread.
     *
     * Placing costs several shell round trips — a `dumpsys activity activities` alone is
     * hundreds of milliseconds, sometimes seconds when the system is busy — and this used to
     * run inline in the tick. On a single-threaded scheduler that stalled the next poll for as
     * long as it took, so the panel could sit on a reading minutes old while the agent had
     * already moved on. Polling and placing are now independent; they never overlap because
     * {@code place()} is serialised internally.
     */
    private final java.util.concurrent.atomic.AtomicBoolean placing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Last time we asked the rear display to light up, so a doze loop cannot hammer it. */
    private long lastWakeAttempt;

    private void wakeAsync(int displayId) {
        lastWakeAttempt = System.currentTimeMillis();
        new Thread(() -> {
            try {
                RearController.wake(this, displayId);
            } catch (Throwable t) {
                Log.w(TAG, "wake failed", t);
            }
        }, "panel-wake").start();
    }

    private int displayState(int displayId) {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display d = dm == null ? null : dm.getDisplay(displayId);
            return d == null ? android.view.Display.STATE_UNKNOWN : d.getState();
        } catch (Throwable t) {
            return android.view.Display.STATE_UNKNOWN;
        }
    }

    private boolean displayIsOn(int displayId) {
        return displayState(displayId) == android.view.Display.STATE_ON;
    }

    private void placeAsync(int displayId) {
        if (!placing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                Log.i(TAG, "placing panel: " + RearController.place(this, displayId));
            } catch (Throwable t) {
                Log.w(TAG, "place failed", t);
            } finally {
                placing.set(false);
            }
        }, "panel-place").start();
    }

    private void applyReading(AgentState fetched) {
        tick++;

        // 1. The reading. Cheap, and the panel repaints itself from the listener.
        StateStore.publish(this, fetched);
        traceTick(fetched);

        if (!StateStore.enabled(this)) return;

        // 2. The panel. Placing it costs a few shell calls, so it is checked on a slower beat
        //    than the reading.
        int displayId = StateStore.displayId(this);
        if (displayId < 0) {
            displayId = RearController.findRearDisplay(this);
            if (displayId >= 0) StateStore.setDisplayId(this, displayId);
        }
        if (displayId < 0) return;

        // "Up" is judged from what the panel reports about itself, which is free, rather than
        // from a dumpsys on this thread. Whether the task ended up on the right display is
        // verified inside place(), which can afford the query.
        boolean panelUp = RearActivity.alive;
        boolean wanted = !panelUp;
        boolean due = System.currentTimeMillis() - lastPlaceAttempt > 30_000L;

        if (wanted && tick % 5 == 0 && due
                && Shell.available() && Shell.granted() && !placing.get()) {
            lastPlaceAttempt = System.currentTimeMillis();
            placeAsync(displayId);
        }

        if (!panelUp && tick % 10 == 0) {
            // Nothing on the back while the phone is unlocked and the panel is allowed: light
            // the display so the next placement lands on a lit panel rather than a dark one.
            wakeAsync(displayId);
        } else if (panelUp && System.currentTimeMillis() - lastWakeAttempt > 30_000L
                && !displayIsOn(displayId)) {
            // The panel can stay "resumed" while the rear display dozes into HyperOS's own
            // always-on layer: the activity is alive, yet the glass shows a wallpaper instead.
            // That is what "背屏掉了" looks like, and nothing else here would notice it.
            Log.i(TAG, "rear display is not on (state " + displayState(displayId) + "), waking it");
            wakeAsync(displayId);
        }
    }

    @Override
    public void onState(AgentState s) {
        updateNotification(s);
        RearActivity.push(s);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "背屏状态", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(AgentState s) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(s.word())
                .setContentText(s.detailLine().isEmpty() ? s.countsLine() : s.detailLine())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open);
        return b.build();
    }

    private void updateNotification(AgentState s) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTE_ID, buildNotification(s));
        } catch (Throwable ignored) { }
    }

    @Override
    public void onDestroy() {
        stopped = true;
        watchdog.removeCallbacksAndMessages(null);
        running = false;
        StateStore.removeListener(this);
        if (loop != null) loop.interrupt();
        loop = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
