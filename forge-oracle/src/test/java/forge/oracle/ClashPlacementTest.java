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
 * An xproof Forge decision_shape cluster (Adder-Staff Boggart, Release the
 * Ants, Titan's Revenge, Gilt-Leaf Ambush, Recross the Paths, Woodland
 * Guidance: every clash card). CR 701.30a: each clashing player reveals the
 * top card and may put it on the bottom. gorge asks the revealer once, as a
 * 1-of-2 pick (resume clash_placement, option kinds "top" / "bottom"); Forge's
 * ClashEffect asks willPutCardOnTop (true means top) of each player's
 * controller. The fixture is the forge-export request of the real find's item
 * with gorge's decisions; the second one answers "Keep it on top" for p0.
 */
public class ClashPlacementTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ClashPlacementTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("clash").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("clash").resolve(name);
    }

    private static StepMachine replay(String name) throws Exception {
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
        assertFalse(row.notes.stream().anyMatch(n -> n.startsWith("loose: AI answered top or bottom")),
                "the clash ask is not a loose miss, notes: " + row.notes);
        lastNotes = row.notes;
        return m;
    }

    private static java.util.List<String> lastNotes;

    @Test
    public void bothSeatsAnswerBottomFromTheirOwnDecision() throws Exception {
        StepMachine m = replay("adder-staff-boggart-bottom-bottom.jsonl");

        assertTrue(lastNotes.contains("clash step 6: bottom [Put it on the bottom]"),
                "the bottom answers are read from gorge's picks, notes: " + lastNotes);
        assertEquals(lastNotes.stream().filter(n -> n.startsWith("clash step 6:")).count(), 2,
                "one answer per clashing seat, notes: " + lastNotes);
        // Both revealed cards went to the bottom of their library.
        assertEquals(topsKept(m), 0, "no revealed card stays on top");
    }

    @Test
    public void oneSeatKeepsTopTheOtherPutsBottom() throws Exception {
        StepMachine m = replay("adder-staff-boggart-top-bottom.jsonl");

        assertTrue(lastNotes.contains("clash step 6: top [Keep it on top]"),
                "p0's top answer is read from its pick, notes: " + lastNotes);
        assertTrue(lastNotes.contains("clash step 6: bottom [Put it on the bottom]"),
                "p1's bottom answer is read from its pick, notes: " + lastNotes);
        assertEquals(topsKept(m), 1, "only p0's revealed card stays on top");
        assertTrue(m.seats[0].getCardsIn(ZoneType.Library).size() > 1, "p0 still has a library");
    }

    /** How many seats still have their original (lowest-id) library card on
     * top: a clash that kept the revealed card on top leaves it there, a
     * bottom answer moves it to the end. */
    private static int topsKept(StepMachine m) {
        int kept = 0;
        for (int i = 0; i < 2; i++) {
            forge.game.card.CardCollectionView lib = m.seats[i].getCardsIn(ZoneType.Library);
            int minId = Integer.MAX_VALUE;
            for (forge.game.card.Card c : lib) {
                minId = Math.min(minId, c.getId());
            }
            if (lib.get(0).getId() == minId) {
                kept++;
            }
        }
        return kept;
    }
}
