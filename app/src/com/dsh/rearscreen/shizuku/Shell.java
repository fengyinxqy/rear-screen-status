package com.dsh.rearscreen.shizuku;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Runs shell-level commands through Shizuku.
 *
 * HyperOS does not let an ordinary app draw on the rear display or move a task there, so the
 * caller has to borrow the shell user. Shizuku hands that to apps the user has approved,
 * which is the whole reason this app needs no root.
 *
 * Arguments are passed as a string array, not a shell line, so nothing can be injected and no
 * quoting rules apply. Every call has a time limit: the poller runs commands one at a time and
 * a hung one would stall every command after it.
 */
public final class Shell {

    public static final int REQ_PERMISSION = 4001;

    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "shizuku-io");
        t.setDaemon(true);
        return t;
    });

    private Shell() { }

    public static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void request() {
        try {
            Shizuku.requestPermission(REQ_PERMISSION);
        } catch (Throwable ignored) { }
    }

    public static final class Result {
        public final int code;
        public final String out;
        public final String err;
        public final boolean timeout;

        Result(int code, String out, String err, boolean timeout) {
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
            this.timeout = timeout;
        }

        public boolean ok() { return !timeout && code == 0; }

        @Override
        public String toString() {
            if (timeout) return "timeout";
            return "exit " + code + (err.isEmpty() ? "" : " / " + err.trim());
        }
    }

    public static Result exec(long timeoutMs, String... argv) {
        if (!available()) return new Result(-1, "", "Shizuku 没在运行", false);
        if (!granted()) return new Result(-1, "", "没有 Shizuku 授权", false);
        IRemoteProcess p = null;
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) return new Result(-1, "", "Shizuku binder 为空", false);
            IShizukuService svc = IShizukuService.Stub.asInterface(binder);
            p = svc.newProcess(argv, null, null);

            final IRemoteProcess rp = p;
            Future<String> outF = IO.submit(readAll(rp, true));
            Future<String> errF = IO.submit(readAll(rp, false));
            Future<Integer> codeF = IO.submit((Callable<Integer>) rp::waitFor);

            int code;
            try {
                code = codeF.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                try { rp.destroy(); } catch (Throwable ignored) { }
                return new Result(-1, safe(outF), safe(errF), true);
            }
            return new Result(code, safe(outF), safe(errF), false);
        } catch (Throwable t) {
            String m = t.getMessage();
            return new Result(-1, "", m == null ? t.getClass().getSimpleName() : m, false);
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Convenience for a command whose exit codes matter; logs nothing itself. */
    public static boolean run(long timeoutMs, String... argv) {
        return exec(timeoutMs, argv).ok();
    }

    private static Callable<String> readAll(IRemoteProcess p, boolean out) {
        return () -> {
            ParcelFileDescriptor fd = out ? p.getInputStream() : p.getErrorStream();
            if (fd == null) return "";
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return new String(buf.toByteArray(), "UTF-8");
            }
        };
    }

    private static String safe(Future<String> f) {
        try {
            return f.get(500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Every display Android reports, for the settings screen's picker. */
    public static List<String> displays() {
        Result r = exec(4000, "/system/bin/cmd", "display", "list");
        List<String> out = new ArrayList<>();
        if (r.ok()) {
            for (String line : r.out.split("\n")) {
                String s = line.trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }
}
