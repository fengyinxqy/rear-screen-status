package com.dsh.rearscreen.shizuku;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the primary per-display activity list, not the repeated supervisor dump. */
public final class ActivityDump {
    private static final Pattern DISPLAY = Pattern.compile("^Display #?(\\d+) \\(activities from top to bottom\\):$");
    private static final Pattern TASK = Pattern.compile("^\\* Task\\{\\S+ #(\\d+)\\s+(.*)$");
    private static final Pattern INTENT = Pattern.compile("(?:^|\\s)I=([^\\s}]+)");
    private static final Pattern ROOT = Pattern.compile("^\\* Hist\\s+#0: ActivityRecord\\{\\S+ u\\d+ (\\S+) t(\\d+)[ }]");

    private ActivityDump() { }

    public static int displayOf(String dump, String pkg, int taskId) {
        Integer display = panelDisplays(dump, pkg).get(taskId);
        return display == null ? -1 : display;
    }

    /** Accept only the exact panel component in the task intent or its own root activity. */
    public static Map<Integer, Integer> panelDisplays(String dump, String pkg) {
        Map<Integer, Integer> panels = new LinkedHashMap<>();
        if (dump == null || pkg == null || pkg.isEmpty()) return panels;
        int display = -1;
        int task = -1;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.equals("ActivityTaskSupervisor state:")
                    || line.equals("Display areas in top down Z order:")
                    || line.equals("Task display areas in top down Z order:")) break;
            if (line.startsWith("Display ")) {
                Matcher header = DISPLAY.matcher(line);
                display = header.matches() ? number(header.group(1)) : -1;
                task = -1;
                continue;
            }
            if (display < 0) continue;
            if (line.startsWith("* Task{")) {
                Matcher row = TASK.matcher(line);
                task = -1;
                if (!row.matches()) continue;
                task = number(row.group(1));
                Matcher intent = INTENT.matcher(row.group(2));
                if (task > 0 && intent.find() && isPanel(pkg, intent.group(1))) {
                    panels.putIfAbsent(task, display);
                }
            } else if (task > 0) {
                Matcher root = ROOT.matcher(line);
                if (root.find() && number(root.group(2)) == task && isPanel(pkg, root.group(1))) {
                    panels.putIfAbsent(task, display);
                }
            }
        }
        return panels;
    }

    private static boolean isPanel(String pkg, String component) {
        return component.equals(pkg + "/.RearActivity")
                || component.equals(pkg + "/" + pkg + ".RearActivity");
    }

    private static int number(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return -1; }
    }
}
