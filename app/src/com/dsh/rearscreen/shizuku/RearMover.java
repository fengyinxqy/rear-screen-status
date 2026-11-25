package com.dsh.rearscreen.shizuku;

import android.os.Binder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Runs inside the shell process that Shizuku started for this app.
 *
 * It is a Binder, not an Activity: the app calls it over IPC instead of spawning
 * `am`/`dumpsys` through Shizuku for every reading. One long-lived helper beats a process per
 * command, which is what this app did before — and the repeated spawn was both slow and the
 * reason placement had to be re-verified on every tick.
 *
 * Everything here already runs as the shell user, so it calls /system/bin tools directly and
 * needs no Shizuku API of its own.
 *
 * Parcel layout is fixed and matched by {@link RearService}. Changing a code means changing
 * both sides, and bumping the service version so the old process is replaced.
 */
public final class RearMover extends Binder {

    public static final int MOVE = FIRST_CALL_TRANSACTION;      // (taskId, display)  -> int moved
    public static final int TASK_DISPLAY = FIRST_CALL_TRANSACTION + 1; // (taskId)     -> int display
    public static final int WAKE = FIRST_CALL_TRANSACTION + 2;   // (display)         -> void
    public static final int BRIGHTEN = FIRST_CALL_TRANSACTION + 3; // (display, min)  -> void
    public static final int KILL = 16777115;                     // legacy: exit process

    private final String packageName;

    /**
     * Shizuku instantiates this class itself and only knows how to pass a Context. A constructor
     * taking anything else fails at bind time with no useful message — which is exactly how this
     * first went wrong: the service never connected and every placement fell back to the slow
     * shell path.
     */
    public RearMover(android.content.Context context) {
        this.packageName = context.getPackageName();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == KILL) {
            System.exit(0);
            return true;
        }
        try {
            switch (code) {
                case MOVE: {
                    int task = data.readInt();
                    int display = data.readInt();
                    boolean moved = move(task, display);
                    reply.writeNoException();
                    reply.writeInt(moved ? 1 : 0);
                    return true;
                }
                case TASK_DISPLAY: {
                    int task = data.readInt();
                    int display = taskDisplay(task);
                    reply.writeNoException();
                    reply.writeInt(display);
                    return true;
                }
                case WAKE: {
                    int display = data.readInt();
                    run("/system/bin/input", "-d", String.valueOf(display),
                            "keyevent", "KEYCODE_WAKEUP");
                    reply.writeNoException();
                    return true;
                }
                case BRIGHTEN: {
                    int display = data.readInt();
                    float min = data.readFloat();
                    brighten(display, min);
                    reply.writeNoException();
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            reply.writeException(new IllegalStateException(String.valueOf(t)));
            return true;
        }
    }

    /** Moves the task only if it is not already where it belongs. */
    private boolean move(int task, int display) throws Exception {
        if (task <= 0) return false;
        int current = taskDisplay(task);
        if (current == display) return true;
        if (current < 0) return false;
        if (display == 0 && current != 0) {
            // Going back to the main screen first is what makes the return trip reliable on
            // HyperOS: a direct rear->main move is sometimes dropped by the window manager.
            run("/system/bin/am", "display", "move-stack", Integer.toString(task), "0");
        }
        return run("/system/bin/am", "display", "move-stack",
                Integer.toString(task), Integer.toString(display));
    }

    /**
     * Which display the given task is on, or -1.
     *
     * Reads `dumpsys activity activities`, not `am stack list`. On this HyperOS build the
     * stack listing is stale — it has shown a task that had already gone, and has omitted one
     * that had just started — so any decision made from it is a guess. The parsing rule itself
     * lives in {@link ActivityDump}, shared with the app side so the two cannot disagree.
     */
    private int taskDisplay(int taskId) throws Exception {
        if (taskId <= 0) return -1;
        return ActivityDump.displayOf(capture("/system/bin/dumpsys", "activity", "activities"),
                packageName, taskId);
    }

    /**
     * Raises the panel's brightness when it came up near black.
     *
     * A woken rear display starts at whatever the light sensor last reported, which has been
     * measured as low as 0.01 — the panel is drawn but invisible. Only ever raises: Xiaomi owns
     * the brightness in normal use.
     */
    private void brighten(int display, float min) {
        try {
            String value = capture("/system/bin/cmd", "display", "get-brightness",
                    "--id", Integer.toString(display));
            if (value == null) return;
            float current = Float.parseFloat(value.trim());
            if (current < min) {
                run("/system/bin/cmd", "display", "set-brightness", "0.75",
                        "--id", Integer.toString(display));
            }
        } catch (Throwable ignored) { }
    }

    private boolean run(String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        purge(process);
        return process.waitFor() == 0;
    }

    private String capture(String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null; ) {
                text.append(line).append('\n');
                if (text.length() > 2_000_000) break;
            }
        }
        return process.waitFor() == 0 ? text.toString() : null;
    }

    /** Drains a process that produces no output, so it cannot block on a full pipe. */
    private void purge(Process process) {
        try {
            byte[] buffer = new byte[4096];
            while (process.getInputStream().read(buffer) > 0) { }
        } catch (Throwable ignored) { }
    }
}
