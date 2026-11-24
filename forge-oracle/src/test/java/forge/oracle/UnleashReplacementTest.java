// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import forge.game.card.CounterEnumType;
import forge.game.zone.ZoneType;

/**
 * An xproof Forge decision_shape cluster (Dead Reveler, one per Unleash card).
 * CR 702.98a: the controller may have the permanent enter with a +1/+1 counter.
 * gorge asks once as a choose_n whose two options both have pick kind
 * "unleash" ("Enter with a +1/+1 counter", "Enter without a counter"); Forge
 * builds Unleash as an Optional$ ETB replacement and asks
 * confirmReplacementEffect. Each fixture is the forge-export request of the
 * find's item with gorge's answer: the counter, and (edited) no counter.
 */
public class UnleashReplacementTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(UnleashReplacementTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("unleash").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("unleash").resolve(name);
    }

    private static StepMachine replay(String name, ResultRow[] out) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertFalse(row.notes.stream().anyMatch(n -> n.startsWith("loose: AI answered")),
                "the unleash ask is not a loose miss, notes: " + row.notes);
        out[0] = row;
        return m;
    }

    @Test
    public void counterPickAnswersYes() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = replay("dead-reveler-counter.jsonl", row);

        assertTrue(row[0].notes.contains("unleash-replacement step 5: yes Enter with a +1/+1 counter"),
                "the confirm answers yes from gorge's unleash pick, notes: " + row[0].notes);
        assertEquals(m.card("p0:Dead Reveler").getCounters(CounterEnumType.P1P1), 1,
                "Dead Reveler entered with a +1/+1 counter");
        assertTrue(m.card("p0:Dead Reveler").isInZone(ZoneType.Battlefield));
    }

    @Test
    public void noCounterPickAnswersNo() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = replay("dead-reveler-nocounter.jsonl", row);

        assertTrue(row[0].notes.contains("unleash-replacement step 5: no Enter without a counter"),
                "the confirm answers no from gorge's unleash pick, notes: " + row[0].notes);
        assertEquals(m.card("p0:Dead Reveler").getCounters(CounterEnumType.P1P1), 0,
                "Dead Reveler entered without a counter");
        assertTrue(m.card("p0:Dead Reveler").isInZone(ZoneType.Battlefield));
    }
}
