// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * xproof Forge decision_shape cluster xpt-36fc0406 (Aetherborn Marauder:
 * MoveCounter | ValidSource$ Permanent.YouCtrl+Other+counters_GE1_P1P1 |
 * CounterNum$ Any). Forge asks which sources to take counters from, then an
 * amount per source. gorge logs the same election as a move_counter amount
 * (one origin), or move_counter_origin picks each followed by a move_counter
 * amount (several origins). The driver must answer the source ask from them
 * and hand the amounts to the per-source number ask.
 */
public class MoveCounterValidSourceTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(MoveCounterValidSourceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("movecounter").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("movecounter").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        return new StepMachine(req, true).run();
    }

    private static int marauderCounters(ResultRow row) {
        JsonObject last = row.snapshots.get(row.snapshots.size() - 1);
        for (JsonElement e : last.getAsJsonArray("permanents")) {
            JsonObject p = e.getAsJsonObject();
            if ("Aetherborn Marauder".equals(p.get("name").getAsString())) {
                return p.has("counters") ? p.getAsJsonObject("counters").get("P1P1").getAsInt() : 0;
            }
        }
        return -1;
    }

    /** One origin (Wall of Stone, one counter): gorge logs only move_counter 1. */
    @Test
    public void oneOriginAmountAnswersSourceAsk() throws Exception {
        ResultRow row = replay("one-origin.jsonl");
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertEquals(marauderCounters(row), 1, "the one counter moved onto the Marauder");
    }

    /** Two origins: origin pick (Grizzly Bears), its amount 2, then the lone
     * remaining origin's amount 1 with no origin pick. */
    @Test
    public void severalOriginsElectionAnswersSourceAsk() throws Exception {
        ResultRow row = replay("two-origins.jsonl");
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("move counter sources:")), "notes: " + row.notes);
        assertEquals(marauderCounters(row), 3, "2 from Grizzly Bears and 1 from Wall of Stone");
    }
}
