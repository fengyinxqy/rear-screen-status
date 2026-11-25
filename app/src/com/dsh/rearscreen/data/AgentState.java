package com.dsh.rearscreen.data;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One reading of what the coding agents on the PC are doing.
 *
 * Wire format (the shape the community status daemons publish):
 * {"active":1,"status":"developing","detail":"...","updated":"2026-10-18T19:46:35+08:00"}
 * status: developing | idle
 *
 * There used to be a third state, "waiting for a human". It is gone on purpose: nothing on
 * this PC can tell "the agent finished its turn" apart from "the agent stopped to ask
 * something", and every attempt to guess produced 等你回答 on a desk where nothing was
 * running. Anything that is not developing is idle.
 */
public final class AgentState {

    public static final String DEV = "developing";
    public static final String IDLE = "idle";

    public final boolean ok;
    public final String error;
    public final int active;
    public final String status;
    /** Which programs the running sessions belong to ("dsh", "claude", "codex"); may be empty. */
    public final java.util.Set<String> agents;
    public final String detail;
    public final long updatedMs;
    public final long fetchedMs;
    public final String origin;

    public AgentState(boolean ok, String error, int active, String status,
                      java.util.Set<String> agents,
                      String detail, long updatedMs, long fetchedMs, String origin) {
        this.ok = ok;
        this.error = error == null ? "" : error;
        this.active = active;
        this.status = status == null ? IDLE : status;
        this.agents = agents == null ? java.util.Collections.emptySet() : agents;
        this.detail = detail == null ? "" : detail;
        this.updatedMs = updatedMs;
        this.fetchedMs = fetchedMs;
        this.origin = origin == null ? "" : origin;
    }

    public static AgentState offline(String why) {
        return new AgentState(false, why, 0, "", null, "", 0L, System.currentTimeMillis(), "");
    }

    public static AgentState fromJson(String json, String origin) throws Exception {
        JSONObject o = new JSONObject(json);
        // Only "developing" is a claim we display; everything else — idle, the retired
        // "waiting", a typo — is the quiet state.
        String status = o.optString("status", IDLE).trim().toLowerCase(Locale.ROOT);
        if (!DEV.equals(status)) status = IDLE;
        int active = Math.max(0, o.optInt("active", 0));
        String detail = o.optString("detail", o.optString("task", ""));
        long updated = parseTime(o.opt("updated"));

        // Agents: the daemon sends an array, single producers send one name. Unknown names are
        // kept as they come; the panel simply draws marks for the ones it knows.
        java.util.Set<String> agents = new java.util.LinkedHashSet<>();
        org.json.JSONArray arr = o.optJSONArray("agents");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) agents.add(v);
            }
        }
        String one = o.optString("agent", "").trim().toLowerCase(Locale.ROOT);
        if (!one.isEmpty()) agents.add(one);

        return new AgentState(true, "", active, status, agents, detail, updated,
                System.currentTimeMillis(), origin);
    }

    private static long parseTime(Object v) {
        if (v == null || v == JSONObject.NULL) return 0L;
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            return n < 100000000000L ? n * 1000L : n;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) { }
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                if (p.endsWith("'Z'")) f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) { }
        }
        return 0L;
    }

    /** The one word the rear panel shows large. */
    public String word() {
        if (!ok) return "未连接";
        return DEV.equals(status) ? "开发中" : "空闲";
    }

    public String countsLine() {
        if (!ok) return error;
        return "会话 " + active;
    }

    public String detailLine() {
        if (!ok) return "";
        String d = detail.trim();
        if (d.isEmpty()) {
            return DEV.equals(status) ? "正在跑" : "没有在跑的会话";
        }
        return d.length() > 48 ? d.substring(0, 47) + "…" : d;
    }

    public int accent() {
        if (!ok) return 0xFF6B7280;
        return DEV.equals(status) ? 0xFF3DDC84 : 0xFF60A5FA;
    }
}
