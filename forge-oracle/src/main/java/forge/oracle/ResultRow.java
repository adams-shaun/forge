// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * One output row, in the XMage driver's result shape so gorge's
 * oraclediff.XResult decodes it unchanged (compliance/oraclediff/compare.go:17-25):
 * name, id, harness, strict, strict_miss, leftover, ms, snapshots. It adds
 * engine "forge", forge_ref (DESIGN section 6.8) and a notes array, which
 * XResult ignores and which makes a run checkable from its output alone.
 */
public final class ResultRow {
    public static final String ENGINE = "forge";
    static final int HARNESS_MAX = 800;
    static final int SHORT_MAX = 300;

    public String name = "";
    public String id;
    public String harness;
    public boolean strict = true;
    public String strictMiss;
    public String leftover;
    public long ms;
    public final List<JsonObject> snapshots = new ArrayList<>();
    public String forgeRef = "";
    public String requestSha;
    public final List<String> notes = new ArrayList<>();

    /** The row a scenario gets when handling it throws, before or during the
     * replay. Same shape as an ordinary harness error. */
    public static ResultRow harnessRow(String id, String name, boolean strict, Throwable t, String forgeRef) {
        ResultRow r = new ResultRow();
        r.id = id;
        r.name = name == null ? "" : name;
        r.strict = strict;
        r.harness = message(t);
        r.forgeRef = forgeRef;
        r.notes.add("trace: " + trace(t, 8));
        return r;
    }

    /** "Class: message", capped like the XMage driver's harness text. */
    public static String message(Throwable t) {
        String msg = t instanceof StrictMiss || t instanceof HarnessError
                ? t.getMessage()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
        return cap(msg, HARNESS_MAX);
    }

    static String cap(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    static String trace(Throwable t, int lines) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] tr = sw.toString().split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(lines, tr.length); i++) {
            if (i > 0) {
                b.append(" | ");
            }
            b.append(tr[i].trim());
        }
        return b.toString();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        if (id != null) {
            o.addProperty("id", id);
        }
        if (harness != null) {
            o.addProperty("harness", cap(harness, HARNESS_MAX));
        }
        o.addProperty("strict", strict);
        if (strictMiss != null) {
            o.addProperty("strict_miss", cap(strictMiss, SHORT_MAX));
        }
        if (leftover != null) {
            o.addProperty("leftover", cap(leftover, SHORT_MAX));
        }
        o.addProperty("ms", ms);
        JsonArray s = new JsonArray();
        snapshots.forEach(s::add);
        o.add("snapshots", s);
        o.addProperty("engine", ENGINE);
        o.addProperty("forge_ref", forgeRef);
        if (requestSha != null) {
            o.addProperty("request_sha", requestSha);
        }
        JsonArray n = new JsonArray();
        notes.forEach(n::add);
        o.add("notes", n);
        return o;
    }
}
