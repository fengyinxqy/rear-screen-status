package com.dsh.rearscreen.shizuku;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

import com.dsh.rearscreen.RearActivity;
import com.dsh.rearscreen.data.StateStore;

/**
 * Puts {@link RearActivity} on the rear display and keeps it there.
 *
 * Placement itself runs in the shell process ({@link RearMover}) through a bound Shizuku user
 * service, so the app makes one IPC call instead of spawning {@code am}/{@code dumpsys} for
 * every question it asks. The plain {@link Shell} path stays as a fallback for the moments when
 * the service is not up yet.
 *
 * The exit code of {@code am start --display N} does NOT say where the activity landed — on the
 * 18 Pro Max it returns 0 and opens the task on the main display anyway — so nothing here
 * trusts a command's exit code. Placement is only reported after the shell helper confirms the
 * task's display.
 */
public final class RearController {

    /** HyperOS's setting for how long the rear screen stays lit after the last touch. */
    private static final String REAR_TIMEOUT_KEY = "subscreen_display_time";

    /** Below this, a freshly woken rear panel is drawn but effectively invisible. */
    private static final float MIN_READABLE_BRIGHTNESS = 0.35f;

    private RearController() { }

    /** The rear display's id, or -1 when this phone has no second display. */
    public static int findRearDisplay(Context ctx) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return -1;
        int fallback = -1;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            int flags = d.getFlags();
            boolean privateDisplay = (flags & Display.FLAG_PRIVATE) != 0;
            if (privateDisplay) return d.getDisplayId();
            if (fallback < 0) fallback = d.getDisplayId();
        }
        return fallback;
    }

    public static String describe(Context ctx, int displayId) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return "无 DisplayManager";
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != displayId) continue;
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            return "display " + displayId + " · " + m.widthPixels + "×" + m.heightPixels
                    + (d.getState() == Display.STATE_ON ? " · 亮" : " · 灭");
        }
        return "display " + displayId + " 不存在";
    }

    /** Lights the rear panel without touching the main screen. */
    public static void wake(Context ctx, int displayId) {
        RearService.warmUp(ctx);
        RearService.wake(displayId);
    }

    /** Raises the panel's brightness if it came up nearly black. Never dims. */
    public static void brighten(Context ctx, int displayId) {
        RearService.warmUp(ctx);
        RearService.brighten(displayId, MIN_READABLE_BRIGHTNESS);
    }

    /**
     * Lights the rear panel: wake it, then raise it out of a near-black brightness.
     *
     * Placement used to lean on the panel activity's own FLAG_TURN_SCREEN_ON. That flag lights
     * whichever display the window is added to, and the window is added to the FRONT screen
     * first — so every placement woke the glass the user was looking at, with the panel's black
     * card on it until the move finished. Waking the intended display explicitly is narrower,
     * and it happens after the task has landed.
     */
    private static void light(Context ctx, int displayId) {
        // Only a display that is really off needs the wake key event. The rear panel usually
        // stays on — it does through doze — and a key event sent to a display that is already on
        // is not free: Xiaomi answers one on the rear screen with its anti-mistouch overlay when
        // the proximity sensor is covered, which is exactly how this phone is used face down.
        if (displayState(ctx, displayId) == Display.STATE_OFF) wake(ctx, displayId);
        brighten(ctx, displayId);
    }

    private static int displayState(Context ctx, int displayId) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(displayId);
            return d == null ? Display.STATE_UNKNOWN : d.getState();
        } catch (Throwable t) {
            return Display.STATE_UNKNOWN;
        }
    }

    /** Slow path, used when the shell service is not available. */
    public static void nudgeBrightnessViaShell(int displayId, float min) {
        Shell.Result r = Shell.exec(3000, "/system/bin/cmd", "display",
                "get-brightness", "--id", String.valueOf(displayId));
        try {
            float v = Float.parseFloat(r.out.trim());
            if (v < min) {
                Shell.run(3000, "/system/bin/cmd", "display", "set-brightness", "0.75",
                        "--id", String.valueOf(displayId));
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Which display the panel task is on, or -1 when it is not running.
     *
     * Needs a task id, which only the running panel knows — see {@link RearActivity#taskId}.
     */
    public static int panelDisplay(Context ctx) {
        int task = RearActivity.taskId;
        if (task <= 0) return -1;
        return RearService.taskDisplay(task);
    }

    /** Removes a task by id, whichever {@code am} spelling this build understands. */
    public static boolean removeTask(int taskId) {
        if (taskId <= 0) return false;
        // "am task remove" does not exist on this platform, and neither does "am stack remove
        // <taskId>" in the sense the old code assumed: the sub-command takes a ROOT task id. A
        // panel owns its own root task, so the ids coincide, but the first call always failed
        // after burning its 4 s timeout and the real failure was hidden behind it. Panels then
        // accumulated one per placement.
        return Shell.run(4000, "/system/bin/am", "stack", "remove", String.valueOf(taskId));
    }

    /**
     * Places the panel. Serialised: the watchdog and a button press can ask at the same time,
     * and two placements running together delete each other's task.
     */
    private static final Object PLACE_LOCK = new Object();

    public static String place(Context ctx, int displayId) {
        synchronized (PLACE_LOCK) {
            return placeLocked(ctx, displayId);
        }
    }

    private static String placeLocked(Context ctx, int displayId) {
        if (displayId <= Display.DEFAULT_DISPLAY) return "没找到背屏";
        RearService.warmUp(ctx);

        Shell.Result snapshot = Shell.exec(9000, "/system/bin/dumpsys", "activity", "activities");
        if (!snapshot.ok()) return "上屏失败：无法读取面板位置";
        java.util.Map<Integer, Integer> panels = ActivityDump.panelDisplays(
                snapshot.out, ctx.getPackageName());
        int task = -1;
        for (java.util.Map.Entry<Integer, Integer> panel : panels.entrySet()) {
            if (panel.getValue() == displayId) {
                task = panel.getKey();
                break;
            }
        }
        if (task > 0) {
            for (int id : panels.keySet()) if (id != task) removeTask(id);
            StateStore.setStrategy(ctx, "kept");
            wakeForPlacement(ctx, displayId);
            brighten(ctx, displayId);
            return awaitPanelWindow(ctx, task, displayId) ? "已在背屏" : "面板任务在背屏，但尚未显示";
        }

        // Reuse a misplaced panel. HyperOS only allows a new panel to start on the main
        // display, so launching a replacement would repeat that transition unnecessarily.
        if (!panels.isEmpty()) {
            task = panels.containsKey(RearActivity.taskId) ? RearActivity.taskId
                    : panels.keySet().iterator().next();
            for (int id : panels.keySet()) if (id != task) removeTask(id);
        } else {
            Shell.Result started = Shell.exec(8000, "/system/bin/am", "start", "-W",
                    "-n", ctx.getPackageName() + "/" + RearActivity.class.getName(),
                    "-f", "0x18000000");
            if (!started.ok()) return "启动面板失败：" + started;
            // -W waits for the first frame, not the end of the HyperOS launch transition.
            // Moving during that transition can leave rear-sized pixels on the front display.
            // This interval was validated by a dual-screen capture on the device.
            sleep(1500);
            for (int attempt = 0; attempt < 16 && task <= 0; attempt++) {
                sleep(250);
                task = RearActivity.taskId;
            }
        }

        if (task > 0) {
            wakeForPlacement(ctx, displayId);
            if (RearService.taskDisplay(task) != displayId && !RearService.move(task, displayId)) {
                Shell.run(6000, "/system/bin/am", "display", "move-stack",
                        String.valueOf(task), String.valueOf(displayId));
            }
            // A successful shell exit only means the request was accepted. Check actual placement.
            for (int attempt = 0; attempt < 4; attempt++) {
                if (RearService.taskDisplay(task) == displayId) {
                    StateStore.setStrategy(ctx, "move");
                    brighten(ctx, displayId);
                    return awaitPanelWindow(ctx, task, displayId)
                            ? "已搬到背屏" : "面板任务已搬到背屏，但尚未显示";
                }
                if (attempt < 3) sleep(150);
            }
            removeTask(task);
        }
        ctx.sendBroadcast(new android.content.Intent(RearActivity.ACTION_CLOSE)
                .setPackage(ctx.getPackageName()));
        return "上屏失败：没能确认面板在 display " + displayId;
    }

    private static void wakeForPlacement(Context ctx, int displayId) {
        int state = displayState(ctx, displayId);
        if (state == Display.STATE_OFF || state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND) wake(ctx, displayId);
    }

    private static boolean awaitPanelWindow(Context ctx, int task, int displayId) {
        for (int attempt = 0; attempt < 4; attempt++) {
            Shell.Result windows = Shell.exec(1500, "/system/bin/dumpsys", "window", "windows");
            if (windows.ok()) {
                boolean panel = false, onTarget = false, ready = false;
                String component = ctx.getPackageName() + "/" + RearActivity.class.getName();
                for (String raw : windows.out.split("\n")) {
                    String line = raw.trim();
                    if (line.startsWith("Window #")) {
                        panel = line.contains(" " + component + "}:");
                        onTarget = ready = false;
                    } else if (panel) {
                        if (line.startsWith("mDisplayId=")) {
                            onTarget = line.startsWith("mDisplayId=" + displayId + " taskId=" + task + " ");
                        } else if (line.startsWith("mHasSurface=true isReadyForDisplay()=true")) {
                            ready = true;
                        } else if (onTarget && ready && line.equals("isOnScreen=true")) {
                            return true;
                        }
                    }
                }
            }
            if (attempt < 3) sleep(150);
        }
        return false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Xiaomi's own rear-screen launcher, restarted so it stops taking the panel back. */
    public static void restartXiaomiRear(Context ctx) {
        for (String comp : new String[]{
                "com.xiaomi.subscreencenter/.SubScreenLauncher",
                "com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity",
                "com.xiaomi.mirror/.SubscreenLauncher"}) {
            if (Shell.run(4000, "/system/bin/am", "start", "-n", comp)) return;
        }
    }

    public static String rearTimeout() {
        Shell.Result r = Shell.exec(3000, "/system/bin/cmd", "settings", "get", "system", REAR_TIMEOUT_KEY);
        return r.out.trim();
    }

    public static boolean setRearTimeout(int ms) {
        return Shell.run(3000, "/system/bin/cmd", "settings", "put", "system",
                REAR_TIMEOUT_KEY, String.valueOf(ms));
    }
}
