package com.dsh.rearscreen.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single reading the whole app shares: the poller writes it, the rear panel and the
 * settings screen read it. Persisted so a restarted panel paints the last known state before
 * the first poll comes back.
 */
public final class StateStore {

    public interface Listener { void onState(AgentState s); }

    private static final String PREFS = "rearscreen";
    private static final String K_STATE = "last_state";
    private static final String K_SOURCE = "source";
    private static final String K_INTERVAL = "interval_sec";
    private static final String K_ENABLED = "panel_enabled";
    private static final String K_DISPLAY = "display_id";
    private static final String K_STRATEGY = "launch_strategy";
    private static final String SEP = "\u0001";

    /** Fields per record: fetched, ok, active, status, detail, updated. */
    private static final int FIELDS = 6;

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile AgentState current = AgentState.offline("还没取过");

    private StateStore() { }

    public static AgentState current() { return current; }

    public static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    public static void publish(Context ctx, AgentState s) {
        current = s;
        if (ctx != null) prefs(ctx).edit().putString(K_STATE, encode(s)).apply();
        for (Listener l : LISTENERS) {
            try { l.onState(s); } catch (Throwable ignored) { }
        }
    }

    public static AgentState load(Context ctx) {
        String raw = prefs(ctx).getString(K_STATE, null);
        if (raw == null) return current;
        String[] p = raw.split(SEP, -1);
        if (p.length < FIELDS) return current;
        // A record written before the "waiting" state was removed has one extra field in the
        // middle. Reading by offset from the end keeps a live install from painting garbage on
        // its first frame after the update.
        final int s = p.length - 3;   // status, detail, updated are the last three
        try {
            boolean ok = Boolean.parseBoolean(p[1]);
            // Agents are not persisted: they describe what is running right now, and a stale
            // mark on the first frame would be a claim we cannot back. The first poll fills it.
            return new AgentState(ok, ok ? "" : "断线", Integer.parseInt(p[2]), p[s], null,
                    p[s + 1], Long.parseLong(p[s + 2]), Long.parseLong(p[0]), "");
        } catch (Throwable t) {
            return current;
        }
    }

    private static String encode(AgentState s) {
        return s.fetchedMs + SEP + s.ok + SEP + s.active + SEP
                + s.status + SEP + s.detail + SEP + s.updatedMs;
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String source(Context c) {
        return prefs(c).getString(K_SOURCE, StatusSource.DEFAULT_URL);
    }
    public static void setSource(Context c, String v) {
        prefs(c).edit().putString(K_SOURCE, v).apply();
    }

    public static int intervalSec(Context c) {
        return prefs(c).getInt(K_INTERVAL, 10);
    }
    public static void setIntervalSec(Context c, int v) {
        prefs(c).edit().putInt(K_INTERVAL, Math.max(3, Math.min(120, v))).apply();
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, false);
    }
    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_ENABLED, v).apply();
    }

    /**
     * The display the panel is meant for, or -1 when it has not been decided yet.
     *
     * {@link android.view.Display#DEFAULT_DISPLAY} is never a valid answer here. A plain
     * spinner set its adapter fires onItemSelected(0), which once stored "display 0" — and the
     * panel then drew across the front screen while reporting success. The main display is
     * filtered on both the way in and the way out, so a stale 0 in preferences cannot survive.
     */
    public static int displayId(Context c) {
        int v = prefs(c).getInt(K_DISPLAY, -1);
        return v == android.view.Display.DEFAULT_DISPLAY ? -1 : v;
    }

    public static void setDisplayId(Context c, int v) {
        if (v == android.view.Display.DEFAULT_DISPLAY) return;
        prefs(c).edit().putInt(K_DISPLAY, v).apply();
    }

    public static String strategy(Context c) {
        return prefs(c).getString(K_STRATEGY, "auto");
    }
    public static void setStrategy(Context c, String v) {
        prefs(c).edit().putString(K_STRATEGY, v).apply();
    }
}
