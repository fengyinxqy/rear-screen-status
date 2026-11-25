import com.dsh.rearscreen.data.AgentState;

public class ParserTest {
    static int fails = 0;
    static void check(String name, boolean ok, String got) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + " -> " + got);
        if (!ok) fails++;
    }
    public static void main(String[] a) throws Exception {
        AgentState s1 = AgentState.fromJson("{\"active\":2,\"status\":\"developing\",\"detail\":\"backpanel\",\"updated\":\"2026-10-18T19:46:35+08:00\"}", "http://pc:8770/status.json");
        check("developing", s1.ok && AgentState.DEV.equals(s1.status) && s1.active == 2, s1.word() + " / " + s1.countsLine() + " / " + s1.detailLine());
        check("time parsed", s1.updatedMs > 1700000000000L, String.valueOf(s1.updatedMs));

        // "waiting" was retired: nothing can tell a finished turn from a question, and the
        // panel showing a wrong urgent state is worse than showing the quiet one.
        AgentState s2 = AgentState.fromJson("{\"active\":1,\"waiting\":1,\"status\":\"waiting\",\"updated\":1764000000}", "file:/sdcard/rear/status.json");
        check("waiting retired -> idle", AgentState.IDLE.equals(s2.status) && "空闲".equals(s2.word()), s2.word() + " / " + s2.detailLine());
        check("epoch seconds scaled", s2.updatedMs == 1764000000000L, String.valueOf(s2.updatedMs));

        AgentState s3 = AgentState.fromJson("{\"status\":\"weird\",\"active\":3,\"detail\":\"x\"}", "http://x");
        check("unknown status -> idle", AgentState.IDLE.equals(s3.status) && s3.active == 3, s3.word() + " / " + s3.countsLine());

        AgentState s4 = AgentState.offline("HTTP 500");
        check("offline", !s4.ok && !s4.word().isEmpty() && "HTTP 500".equals(s4.countsLine()), s4.word() + " / " + s4.countsLine());

        try {
            AgentState.fromJson("not json", "x");
            check("bad json throws", false, "no throw");
        } catch (Exception e) {
            check("bad json throws", true, e.getClass().getSimpleName());
        }

        AgentState s6 = AgentState.fromJson("{\"status\":\"developing\",\"agents\":[\"DSH\",\"claude\"]}", "x");
        check("agents parsed", s6.agents.contains("dsh") && s6.agents.contains("claude"),
                s6.agents.toString());
        AgentState s7 = AgentState.fromJson("{\"status\":\"developing\",\"agent\":\"codex\"}", "x");
        check("single agent parsed", s7.agents.contains("codex"), s7.agents.toString());

        AgentState s5 = AgentState.fromJson("{\"status\":\"idle\"}", "x");
        check("minimal body", s5.ok && s5.active == 0 && s5.detailLine().length() > 0, s5.word() + " / " + s5.detailLine());

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
