// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.player.Player;

/**
 * xproof cluster xpt-6e2fff25: Kitsune, Dragon's Daughter's attack trigger
 * ("you may exchange control of two other target creatures controlled by
 * different players", TargetMin$ 2 | TargetMax$ 2 |
 * TargetsWithDifferentControllers) fires while only one other creature
 * exists, so the required number of targets cannot be chosen and the ability
 * is removed without going on the stack (CR 603.3d with 601.2c). gorge logs
 * no target ask; Forge's TargetRestrictions.hasCandidates is true with one
 * candidate, so Forge still asks. The driver used to strict-miss with
 * "Missing target answer at step 9"; it now mirrors TargetSelection's gate
 * (the same gate Forge's own targeting cancels the ability on): when even the
 * largest legal set cannot reach min, decline without a miss. With enough
 * candidates an unanswered ask stays a strict miss (the same request with a
 * second other creature on the battlefield).
 */
public class TargetsBelowMinTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(TargetsBelowMinTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("targets-below-min").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures")
                .resolve("targets-below-min").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        return new StepMachine(req, true).run();
    }

    @Test
    public void oneOtherCreatureDropsTheTriggerWithoutAMiss() throws Exception {
        ResultRow row = replay("kitsune-fewer-targets.jsonl");
        assertTrue(row.strict, "the replay must be strict");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("target step 9: fewer candidates than min")),
                "the trigger was not declined for a below-min candidate count, notes: " + row.notes);
        assertTrue(row.notes.stream().noneMatch(n -> n.startsWith("loose:")),
                "the target ask fell through to the AI, notes: " + row.notes);
    }

    @Test
    public void oneOtherCreatureExchangesNothing() throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture("kitsune-fewer-targets.jsonl"), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        m.run();
        Player p0 = m.seats[0];
        Player p1 = m.seats[1];
        assertEquals(m.card("p0:Kitsune, Dragon's Daughter").getController(), p0,
                "Kitsune must stay with its controller");
        assertEquals(m.card("p1:Grizzly Bears").getController(), p1,
                "Grizzly Bears must stay with its controller");
        assertTrue(m.card("p1:Grizzly Bears").getController() != m.card("p0:Kitsune, Dragon's Daughter").getController(),
                "no control exchange may happen");
    }

    @Test
    public void twoCreaturesKeepsTheStrictMiss() throws Exception {
        ResultRow row = replay("kitsune-two-creatures.jsonl");
        assertTrue(row.strict, "the replay must be strict");
        String miss = row.strictMiss != null ? row.strictMiss : row.harness;
        assertNotNull(miss, "an ask with enough candidates and no gorge answer must strict-miss");
        assertTrue(miss.contains("Missing target answer"), "want a target strict miss, got: " + miss);
        assertTrue(row.notes.stream().noneMatch(n -> n.startsWith("target step 9: fewer candidates than min")),
                "a two-candidate ask must not take the below-min path, notes: " + row.notes);
    }
}
