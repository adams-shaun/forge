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

import forge.game.zone.ZoneType;

/**
 * An xproof Forge decision_shape cluster (Grave-Shell Scarab). The scenario
 * sacrifices the Scarab to draw a card; the draw then meets its own Dredge 1.
 * CR 702.52a: the player may mill N and return the dredger to hand instead of
 * drawing. gorge asks once, as a mode decision over every legal dredger plus
 * the ordinary draw (resume "dredge"); Forge builds Dredge as an Optional$
 * replacement and asks chooseSingleReplacementEffect (several dredgers) and
 * confirmReplacementEffect. Each fixture is the forge-export request of the
 * find's item, with gorge's answers: the Scarab dredged, the ordinary draw,
 * and two dredgers where the pick names Golgari Thug.
 */
public class DredgeReplacementTest {

    private static final class Replay {
        final StepMachine machine;
        final ResultRow row;

        Replay(StepMachine machine, ResultRow row) {
            this.machine = machine;
            this.row = row;
        }
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(DredgeReplacementTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("dredge").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("dredge").resolve(name);
    }

    private static Replay replay(String name) throws Exception {
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
                "the dredge ask is not a loose miss, notes: " + row.notes);
        return new Replay(m, row);
    }

    @Test
    public void dredgeAnswersYesFromThePick() throws Exception {
        Replay r = replay("grave-shell-scarab-dredge.jsonl");

        assertTrue(r.row.notes.contains(
                        "dredge-replacement step 3: yes Dredge 1 (mill, then return Grave-Shell Scarab to hand)"),
                "the confirm answers yes from gorge's dredge pick, notes: " + r.row.notes);
        assertTrue(r.machine.card("p0:Grave-Shell Scarab").isInZone(ZoneType.Hand),
                "the dredged Scarab returned from the graveyard to hand");
    }

    @Test
    public void ordinaryDrawDeclinesDredge() throws Exception {
        Replay r = replay("grave-shell-scarab-draw.jsonl");

        assertTrue(r.row.notes.contains("dredge-replacement step 3: no"),
                "the confirm answers no for gorge's ordinary draw, notes: " + r.row.notes);
        assertTrue(r.machine.card("p0:Grave-Shell Scarab").isInZone(ZoneType.Graveyard),
                "a declined dredge leaves the Scarab in the graveyard");
        assertEquals(r.machine.seats[0].getCardsIn(ZoneType.Hand).size(), 1,
                "the ordinary draw still draws a card");
    }

    @Test
    public void twoDredgersChoosesTheNamedHost() throws Exception {
        Replay r = replay("two-dredgers-thug.jsonl");

        assertTrue(r.row.notes.contains("replacement order step 3: dredge Golgari Thug"),
                "chooseSingleReplacementEffect hands Forge the dredger gorge named, notes: " + r.row.notes);
        assertTrue(r.row.notes.contains(
                        "dredge-replacement step 3: yes Dredge 4 (mill, then return Golgari Thug to hand)"),
                "that host's confirm answers yes, notes: " + r.row.notes);
        assertTrue(r.machine.card("p0:Golgari Thug").isInZone(ZoneType.Hand),
                "the named dredger returned to hand");
        assertTrue(r.machine.card("p0:Grave-Shell Scarab").isInZone(ZoneType.Graveyard),
                "the other dredger was not applied");
    }

    @Test
    public void ordinaryDrawDeclinesEveryDredger() throws Exception {
        Replay r = replay("two-dredgers-draw.jsonl");

        // Forge asks one confirm per offered dredger; the ordinary draw must
        // answer no to each, not only to the first, or a later one falls back
        // to the AI and applies a replacement gorge declined.
        assertEquals(r.row.notes.stream().filter(n -> n.startsWith("dredge-replacement step 3:")).count(), 2,
                "one no per dredger, notes: " + r.row.notes);
        assertTrue(r.row.notes.contains("dredge-replacement step 3: no (another dredger is queued)"),
                "the first confirm is declined but keeps the pick for the next, notes: " + r.row.notes);
        assertTrue(r.machine.card("p0:Golgari Thug").isInZone(ZoneType.Graveyard),
                "the first dredger was not applied");
        assertTrue(r.machine.card("p0:Grave-Shell Scarab").isInZone(ZoneType.Graveyard),
                "the second dredger was not applied");
        assertEquals(r.machine.seats[0].getCardsIn(ZoneType.Hand).size(), 1,
                "the ordinary draw still draws a card");
    }
}
