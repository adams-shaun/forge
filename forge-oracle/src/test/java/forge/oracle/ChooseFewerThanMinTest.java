// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * xproof cluster xpt-8184f8d0: Foreboding Steamboat's enters trigger, "each
 * player chooses two nontoken, non-Vehicle creatures they control", with the
 * opponent controlling one creature (Phyrexian Walker). ChooseCardEffect asks
 * chooseCardsForEffect(min 2, max 2) over that one card, and a player told to
 * choose two among one chooses the one (CR 609.3: do as much as possible);
 * Forge's InputSelectEntitiesFromList clamps both bounds to the number of
 * options, and gorge records the single pick. The driver checked the pick
 * against the unclamped min and strict-missed "gorge picked 1, Forge wants
 * 2..2". It now checks against min(min, options); a pick short of that, with
 * two candidates and one pick, stays a strict miss.
 */
public class ChooseFewerThanMinTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ChooseFewerThanMinTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("choose-fewer-than-min").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures")
                .resolve("choose-fewer-than-min").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        return new StepMachine(req, true).run();
    }

    private static void assertTakesTheLonePick(ResultRow row) {
        assertTrue(row.strict, "the replay must be strict, strict_miss: " + row.strictMiss);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.equals(
                "pick step 9 card (Choose two nontoken, non-Vehicle creatures you control): [p1:Phyrexian Walker]")),
                "gorge's single pick must be taken, notes: " + row.notes);
    }

    @Test
    public void chooseTwoOverOneCreatureTakesGorgesSinglePick() throws Exception {
        assertTakesTheLonePick(replay("foreboding-steamboat-cast-s1.jsonl"));
    }

    @Test
    public void sameChoiceOnTheFirstTurnPathTakesGorgesSinglePick() throws Exception {
        assertTakesTheLonePick(replay("foreboding-steamboat-f0t0-s1.jsonl"));
    }

    @Test
    public void onePickOverTwoCandidatesStaysAStrictMiss() throws Exception {
        ResultRow row = replay("foreboding-steamboat-two-candidates.jsonl");
        assertTrue(row.strict, "the replay must be strict");
        String miss = row.strictMiss != null ? row.strictMiss : row.harness;
        assertNotNull(miss, "one pick over two candidates where two are required must strict-miss");
        assertTrue(miss.contains("gorge picked 1, Forge wants 2..2"), "want the min..max miss, got: " + miss);
    }
}
