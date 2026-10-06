// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The Forge oracle driver's entry point:
 *
 * <pre>
 *   ScenarioReplay &lt;in.jsonl&gt; &lt;out.jsonl&gt; [--res DIR] [--repeat N]
 * </pre>
 *
 * One JVM, one result row per request line, in input order. It mirrors the
 * XMage driver's replayLines (ScenarioReplay.java:467-511): ANY throwable while
 * handling one line -- a malformed line, a bad turn, a missing steps array, a
 * crash inside Forge -- becomes that line's harness row and the loop goes on.
 *
 * Strict first: when Forge asks a question gorge's decision log does not
 * answer, the scenario is replayed loose (the AI answers) and the row says so
 * in strict_miss. Environment: FORGE_RES (the fork's forge-gui/res) and
 * FORGE_ORACLE_REF (the fork commit, echoed as forge_ref).
 */
public final class ScenarioReplay {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** One strict or loose attempt at a parsed request. */
    public interface Engine {
        ResultRow replayOnce(Request req, boolean strict);
    }

    final Engine engine;
    final String forgeRef;

    public ScenarioReplay(Engine engine, String forgeRef) {
        this.engine = engine;
        this.forgeRef = forgeRef == null ? "" : forgeRef;
    }

    /** Strict, then loose on a strict miss. A throw from either attempt is a
     * harness row for this scenario. */
    public ResultRow replay(Request req) {
        ResultRow strict;
        try {
            validate(req);
            strict = engine.replayOnce(req, true);
        } catch (Throwable t) {
            return ResultRow.harnessRow(req.id, req.name, true, t, forgeRef);
        }
        String h = strict.harness == null ? "" : strict.harness;
        if (!h.startsWith("Missing ")) {
            return strict;
        }
        ResultRow loose;
        try {
            loose = engine.replayOnce(req, false);
        } catch (Throwable t) {
            ResultRow r = ResultRow.harnessRow(req.id, req.name, false, t, forgeRef);
            r.strictMiss = h;
            return r;
        }
        loose.strict = false;
        loose.strictMiss = h;
        return loose;
    }

    /** The request-level checks every scenario passes before Forge is
     * touched: a steps array and a valid turn. */
    static void validate(Request req) {
        if (req.item.get("steps") == null || !req.item.get("steps").isJsonArray()) {
            throw new HarnessError("scenario " + req.id + " has no steps array");
        }
        req.turn();
    }

    /** One line in, one row out; never throws. */
    public ResultRow replayLine(String line) {
        Request req;
        try {
            req = Request.parse(line);
        } catch (Throwable t) {
            Matcher m = ID.matcher(line);
            ResultRow r = ResultRow.harnessRow(m.find() ? m.group(1) : null, "", true,
                    new HarnessError("malformed request line: " + t.getClass().getSimpleName() + ": " + t.getMessage()), forgeRef);
            return r;
        }
        ResultRow r = replay(req);
        if (r.id == null) {
            r.id = req.id;
        }
        r.forgeRef = forgeRef;
        return r;
    }

    /** Replays every non-blank line, writing one row each, flushed per row.
     * Returns the row count. */
    public static int replayLines(ScenarioReplay r, BufferedReader in, PrintWriter out) throws IOException {
        int n = 0;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            long t0 = System.nanoTime();
            ResultRow row = r.replayLine(line);
            row.ms = (System.nanoTime() - t0) / 1_000_000;
            out.println(GSON.toJson(row.toJson()));
            out.flush();
            n++;
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ScenarioReplay <in.jsonl> <out.jsonl> [--res DIR] [--repeat N]");
            System.exit(2);
        }
        String res = System.getenv().getOrDefault("FORGE_RES", "forge-gui/res");
        String ref = System.getenv().getOrDefault("FORGE_ORACLE_REF", "");
        int repeat = 1;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--res": res = args[++i]; break;
                case "--repeat": repeat = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("unknown flag " + args[i]);
            }
        }
        long t0 = System.nanoTime();
        Bootstrap.init(res);
        long tBoot = System.nanoTime();
        ScenarioReplay r = new ScenarioReplay(new ForgeEngine(), ref);
        int n;
        List<Long> warm = new ArrayList<>();
        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(w)) {
            try (BufferedReader in = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8)) {
                n = replayLines(r, in, out);
            }
            // --repeat N re-runs the input N-1 more times for timing only;
            // those rows are discarded.
            for (int k = 1; k < repeat; k++) {
                for (String line : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    long s0 = System.nanoTime();
                    r.replayLine(line);
                    warm.add(System.nanoTime() - s0);
                }
            }
        }
        long tEnd = System.nanoTime();
        System.err.printf("ScenarioReplay: %d scenarios bootstrap_ms=%d total_ms=%d%n", n,
                (tBoot - t0) / 1_000_000, (tEnd - t0) / 1_000_000);
        if (!warm.isEmpty()) {
            Collections.sort(warm);
            System.err.printf("warm n=%d median_ms=%.2f p90_ms=%.2f max_ms=%.2f%n", warm.size(),
                    warm.get(warm.size() / 2) / 1e6, warm.get((int) (warm.size() * 0.9)) / 1e6,
                    warm.get(warm.size() - 1) / 1e6);
        }
        System.exit(0);
    }
}
