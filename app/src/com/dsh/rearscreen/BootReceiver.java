package com.dsh.rearscreen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.dsh.rearscreen.data.StateStore;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Resumes the panel after a reboot.
 *
 * Shizuku does not survive a reboot on an unrooted phone, so the panel is placed late — better
 * to wait and land than to try at boot and fail. Three attempts spread over a minute and a half
 * covers the usual gap between boot completing and the user getting Shizuku running again.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        if (!StateStore.enabled(app)) return;
        StateStore.setEnabled(app, true);
        StatusService.start(app);
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "boot-retry");
            t.setDaemon(true);
            return t;
        }).schedule(() -> {
            if (!com.dsh.rearscreen.shizuku.Shell.available()) return;
            int d = StateStore.displayId(app);
            if (d < 0) d = com.dsh.rearscreen.shizuku.RearController.findRearDisplay(app);
            if (d >= 0) com.dsh.rearscreen.shizuku.RearController.place(app, d);
        }, 90, TimeUnit.SECONDS);
    }
}
