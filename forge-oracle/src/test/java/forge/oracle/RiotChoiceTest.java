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

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.zone.ZoneType;

/**
 * The xproof Forge decision_shape cluster for Riot (xpt-cbd8207b, Spider-Punk).
 * CR 702.136a: the permanent enters with its controller's choice of a +1/+1
 * counter or haste. gorge asks once as a choose_n whose two options have pick
 * kind "riot" ("Enter with a +1/+1 counter", "Gain haste"); Forge builds Riot
 * as an ETB replacement whose Animate grants haste UnlessCost$
 * AddCounter<1/P1P1>, asked through payCostToPreventEffect, which only read
 * unless_pay answers ("Missing unless-pay answer ... for Riot" plus a
 * leftover). Each fixture is the forge-export request of
 * xs:XP:spider-punk:cast:s1 with gorge's answer: the counter, and (edited)
 * haste.
 */
public class RiotChoiceTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(RiotChoiceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("riot").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("riot").resolve(name);
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
        out[0] = row;
        return m;
    }

    @Test
    public void counterPickEntersWithACounter() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = replay("spider-punk-counter.jsonl", row);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.startsWith("riot step 4: Enter with a +1/+1 counter paid=true")),
                "the riot pick pays the counter, notes: " + row[0].notes);
        Card c = m.card("p0:Spider-Punk");
        assertTrue(c.isInZone(ZoneType.Battlefield));
        assertEquals(c.getCounters(CounterEnumType.P1P1), 1, "Spider-Punk entered with a +1/+1 counter");
        assertFalse(c.hasKeyword(Keyword.HASTE), "the counter pick grants no haste");
    }

    @Test
    public void hastePickEntersWithHaste() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = replay("spider-punk-haste.jsonl", row);
        assertTrue(row[0].notes.contains("riot step 4: Gain haste"),
                "the riot pick declines the counter, notes: " + row[0].notes);
        Card c = m.card("p0:Spider-Punk");
        assertTrue(c.isInZone(ZoneType.Battlefield));
        assertEquals(c.getCounters(CounterEnumType.P1P1), 0, "the haste pick puts no counter");
        assertTrue(c.hasKeyword(Keyword.HASTE), "Spider-Punk has haste");
    }
}
