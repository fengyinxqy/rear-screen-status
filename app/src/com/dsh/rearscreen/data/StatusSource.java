package com.dsh.rearscreen.data;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Where a reading comes from. Two shapes, chosen by the configured source string:
 *   http(s)://...    fetched over HTTP (the PC daemon publishes /status.json)
 *   /abs/or/rel/path read from disk (adb push, no daemon needed)
 */
public final class StatusSource {

    public static final String DEFAULT_URL = "http://127.0.0.1:8770/status.json";

    private StatusSource() { }

    public static AgentState fetch(String source, int timeoutMs) {
        return fetch(source, timeoutMs, 0);
    }

    /**
     * @param waitSec how long the daemon may hold the request open waiting for a change, or 0
     *                for a plain request. This is what keeps the panel current: on a timer it
     *                was on average five seconds behind the agents and up to ten, which on the
     *                back of a phone reads as a lagging screen.
     */
    public static AgentState fetch(String source, int timeoutMs, int waitSec) {
        return fetch(source, timeoutMs, waitSec, null);
    }

    /**
     * @param waitSec how long the daemon may hold the request open, or 0 for a plain request.
     * @param since   what the caller already shows, so a daemon that changed while no request
     *                was in flight answers at once instead of holding the request for its full
     *                wait. That gap is precisely where a long poll silently turns back into a
     *                slow timer.
     */
    public static AgentState fetch(String source, int timeoutMs, int waitSec, String since) {
        String src = source == null ? "" : source.trim();
        if (src.isEmpty()) return AgentState.offline("没配置状态源");
        final boolean remote = src.startsWith("http://") || src.startsWith("https://");
        try {
            String body = remote
                    ? http(withQuery(src, waitSec, since), timeoutMs,
                            timeoutMs + Math.max(0, waitSec) * 1000)
                    : file(src.startsWith("file:") ? src.substring(5) : src);
            return AgentState.fromJson(body, src);
        } catch (Throwable t) {
            String m = t.getMessage();
            return AgentState.offline(m == null ? t.getClass().getSimpleName() : m);
        }
    }

    /** Adds the long-poll parameters, keeping any query the configured source already carries. */
    private static String withQuery(String url, int waitSec, String since) {
        final StringBuilder q = new StringBuilder();
        if (waitSec > 0) q.append("wait=").append(waitSec);
        if (since != null && !since.isEmpty()) {
            if (q.length() > 0) q.append('&');
            q.append("since=").append(encode(since));
        }
        if (q.length() == 0) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + q;
    }

    private static String encode(String s) {
        try {
            // The daemon unquotes with %20 semantics, so the form-encoding '+' would come back
            // as a literal plus and never match.
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String http(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        // Generous on purpose: the daemon holds the response until the reading changes.
        c.setReadTimeout(readMs);
        c.setRequestProperty("Cache-Control", "no-cache");
        // No keep-alive: after the daemon restarts, a pooled socket can look open and block the
        // read past its timeout. One request, one connection, no shared state to go stale.
        c.setRequestProperty("Connection", "close");
        c.setUseCaches(false);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String file(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) throw new IllegalStateException("文件不存在");
        try (InputStream in = new FileInputStream(f)) {
            return read(in);
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 256 * 1024) break;
        }
        return new String(out.toByteArray(), "UTF-8");
    }
}
