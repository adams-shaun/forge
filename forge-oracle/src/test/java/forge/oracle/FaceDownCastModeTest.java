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

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * The face-down casts (CR 702.37c, 702.168a, 702.169a): gorge's cast_mode
 * "morphed", "megamorphed" and "disguised" each pay the family's {3}, not
 * the printed cost. StepMachine.spellForMode had no case for them, so the
 * driver took the card's printed spell and Forge refused the cast where the
 * printed cost exceeds the mana gorge paid (Belltoll Dragon megamorphed for
 * {3}: "was not put on the stack"; xproof cluster xpt-716677db) or, where the
 * printed cost is cheaper (Sleuth Instructor {1}{W}), cast face up instead.
 * The driver now takes the face-down spell Forge builds for the card
 * (CardFactoryUtil.abilityCastFaceDown, a Spell with
 * {@code setCastFaceDown(true)} and Cost {3}).
 */
public class FaceDownCastModeTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(FaceDownCastModeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("morph").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("morph").resolve(name);
    }

    /** Replays one fixture cut after the face-down cast and returns the
     * machine at its end state (the row is the run's result). */
    private static StepMachine machineOf(String fixture, ResultRow[] row) throws Exception {
        Bootstrap.init(TestRes.res());
        String line;
        try (BufferedReader in = Files.newBufferedReader(fixture(fixture), StandardCharsets.UTF_8)) {
            line = in.readLine();
        }
        StepMachine m = new StepMachine(Request.parse(line), true);
        row[0] = m.run();
        return m;
    }

    private static void assertClean(ResultRow row, String mode) {
        assertNull(row.harness, "harness row: " + row.harness);
        assertTrue(row.notes.stream().noneMatch(n -> n.startsWith("loose:")), "notes: " + row.notes);
        assertTrue(row.notes.stream().anyMatch(n -> n.contains("cast_mode " + mode + " took")),
                "notes: " + row.notes);
    }

    private static void assertFaceDownTwoTwoOnStack(StepMachine m, String ref, ResultRow row) {
        Card c = (Card) m.entity(ref);
        assertNotNull(c, ref + " is not in the game; harness " + row.harness);
        assertTrue(c.isInZone(ZoneType.Stack), ref + " is not on the stack");
        assertTrue(c.isFaceDown(), ref + " is not face down on the stack");
        assertEquals(c.getNetPower(), 2, ref + " power");
        assertEquals(c.getNetToughness(), 2, ref + " toughness");
        assertNotNull(c.getController(), ref + " has no controller");
    }

    private static void assertManaAllSpent(StepMachine m, String land, ResultRow row) {
        Player p = (Player) m.entity("p0");
        assertEquals(p.getManaPool().totalMana(), 0, "mana left in the pool; harness " + row.harness);
        int untapped = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(land) && !c.isTapped()) {
                untapped++;
            }
        }
        assertEquals(untapped, 0, "the {3} face-down cost left an untapped land");
    }

    /** The find (xproof xs:XP:belltoll-dragon:cast:s1): three Islands and a
     * megamorphed Belltoll Dragon cast for {3} puts the face-down 2/2 on the
     * stack. */
    @Test
    public void megamorphedCastIsAFaceDownTwoTwo() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = machineOf("belltoll-dragon-megamorphed.jsonl", row);
        assertClean(row[0], "megamorphed");
        assertFaceDownTwoTwoOnStack(m, "p0:Belltoll Dragon", row[0]);
        assertManaAllSpent(m, "Island", row[0]);
        Card dragon = (Card) m.entity("p0:Belltoll Dragon");
        assertTrue(dragon.getOwner().getCardsIn(ZoneType.Hand).stream().noneMatch(c -> c.getName().equals("Belltoll Dragon")),
                "Belltoll Dragon is still in hand");
    }

    /** The same family's plain morph: a morphed Weaver of Lies for {3}. */
    @Test
    public void morphedCastIsAFaceDownTwoTwo() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = machineOf("weaver-of-lies-morphed.jsonl", row);
        assertClean(row[0], "morphed");
        assertFaceDownTwoTwoOnStack(m, "p0:Weaver of Lies", row[0]);
        assertManaAllSpent(m, "Plains", row[0]);
    }

    /** Disguise: a disguised Sleuth Instructor for {3}. */
    @Test
    public void disguisedCastIsAFaceDownTwoTwo() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = machineOf("sleuth-instructor-disguised.jsonl", row);
        assertClean(row[0], "disguised");
        assertFaceDownTwoTwoOnStack(m, "p0:Sleuth Instructor", row[0]);
        assertManaAllSpent(m, "Plains", row[0]);
    }
}
