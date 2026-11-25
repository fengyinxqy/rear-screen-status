package com.dsh.rearscreen.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

import rikka.shizuku.Shizuku;

/**
 * The app-side half of the shell helper.
 *
 * Holds one bound Shizuku user service and forwards the calls placement needs. Binds lazily:
 * nothing happens until the first call, and a dead binder is simply rebound on the next one, so
 * a Shizuku restart heals without the app being touched.
 *
 * Every method returns a plain Java value and never throws — callers decide what a failure
 * means. If Shizuku is unavailable the calls fall back to {@link Shell}, so the app still works
 * (just more slowly) rather than going dark.
 */
public final class RearService {

    /**
     * Bumped whenever the parcel layout or the shell-side behaviour changes.
     *
     * Shizuku keys the user service on (component, version): the process is only replaced when
     * this changes. A stale value silently keeps the OLD code running, which looks exactly like
     * "my fix did nothing".
     *
     * Read from {@link com.dsh.rearscreen.Version}, whose value build.sh writes from the same
     * variable it passes to aapt2 — one source of truth, so the two cannot drift.
     */
    public static final int VERSION = com.dsh.rearscreen.Version.CODE;

    private static final Object LOCK = new Object();

    private static IBinder binder;
    private static boolean binding;
    private static Shizuku.UserServiceArgs args;
    private static Context appContext;

    private RearService() { }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) { binder = service; binding = false; LOCK.notifyAll(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) { binder = null; binding = false; }
        }
    };

    /** Warms the binding up so the first placement is not the one that pays for it. */
    public static void warmUp(Context ctx) {
        ensure(ctx, 1200);
    }

    /** Drops the binding; the next call rebinds. */
    public static void reset() {
        synchronized (LOCK) { binder = null; binding = false; }
    }

    private static IBinder ensure(Context ctx, long timeoutMs) {
        if (ctx == null) return null;
        appContext = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (binder != null && binder.pingBinder()) return binder;
            binder = null;
            if (binding) {
                // Another caller is already binding; wait for it rather than starting a second.
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (binding && System.currentTimeMillis() < deadline) {
                    try { LOCK.wait(Math.max(20, deadline - System.currentTimeMillis())); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
                }
                return binder;
            }
            if (!Shell.available() || !Shell.granted()) return null;
            try {
                if (args == null) {
                    args = new Shizuku.UserServiceArgs(
                            new ComponentName(appContext, RearMover.class))
                            .processNameSuffix("rear_mover")
                            .daemon(false)
                            .version(VERSION)
                            .debuggable(false);
                }
                binding = true;
                Shizuku.bindUserService(args, CONNECTION);
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (binder == null && binding && System.currentTimeMillis() < deadline) {
                    try { LOCK.wait(Math.max(20, deadline - System.currentTimeMillis())); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Throwable t) {
                binding = false;
                binder = null;
            }
            return binder;
        }
    }

    /**
     * Which display the task is on, or -1.
     *
     * Read on THIS side, through {@link Shell}, rather than through the bound helper. The helper
     * runs in the shell process and its own `dumpsys` reads came back empty for a task this side
     * could see plainly, so the reliable direction is: ask the local process (verified working),
     * and only use the helper to act on the answer.
     */
    public static int taskDisplay(int taskId) {
        if (taskId <= 0) return -1;
        Shell.Result r = Shell.exec(9000, "/system/bin/dumpsys", "activity", "activities");
        if (!r.ok()) return -1;
        String pkg = appContext == null ? "" : appContext.getPackageName();
        return ActivityDump.displayOf(r.out, pkg, taskId);
    }

    /** Asks the shell to move the task. Returns true only when it reports success. */
    public static boolean move(int taskId, int displayId) {
        if (taskId <= 0) return false;
        IBinder b = ensure(appContext, 1500);
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(taskId);
            data.writeInt(displayId);
            if (!b.transact(RearMover.MOVE, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() == 1;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Lights just that display. */
    public static void wake(int displayId) {
        IBinder b = ensure(appContext, 800);
        if (b == null) {
            Shell.run(4000, "/system/bin/input", "-d", String.valueOf(displayId),
                    "keyevent", "KEYCODE_WAKEUP");
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(displayId);
            if (b.transact(RearMover.WAKE, data, reply, 0)) reply.readException();
        } catch (Throwable ignored) {
            Shell.run(4000, "/system/bin/input", "-d", String.valueOf(displayId),
                    "keyevent", "KEYCODE_WAKEUP");
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Raises the display's brightness when it is below {@code min}. */
    public static void brighten(int displayId, float min) {
        IBinder b = ensure(appContext, 800);
        if (b == null) {
            RearController.nudgeBrightnessViaShell(displayId, min);
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(displayId);
            data.writeFloat(min);
            if (b.transact(RearMover.BRIGHTEN, data, reply, 0)) reply.readException();
        } catch (Throwable ignored) {
            RearController.nudgeBrightnessViaShell(displayId, min);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
