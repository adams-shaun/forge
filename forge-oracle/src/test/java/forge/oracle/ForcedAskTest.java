// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertEquals;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.zone.ZoneType;

/**
 * Three xproof Forge decision_shape clusters whose ask is forced, or arrives
 * after the item's last step, so gorge logs no decision and is right. Each
 * fixture is one forge-export request with gorge's answers:
 *
 * <ul>
 * <li>Alpharael, Dreaming Acolyte: "discard two cards unless you discard an
 *     artifact card" over a two-card hand with no artifact. The election can
 *     only discard the whole hand, so gorge skips the pick and Forge must ask
 *     min..min, not 1..min.</li>
 * <li>Death Cloud cast with X = 0: Forge offers its three Swamps to
 *     choosePermanentsToSacrifice(0, 0); the empty pick is the only legal
 *     answer, so 0..0 is forced, not an unmapped ask.</li>
 * <li>Marang River Regent // Coil and Catch: the six-step item ends on p0's
 *     pass with the Omen spell on the stack. The pass that follows resolves it
 *     and its "discard a card" reaches the controller with stepIdx ==
 *     steps.size(), after the final compared priority. Nothing after that
 *     window is compared, so the ask is noted and answered, never a strict
 *     miss.</li>
 * </ul>
 */
public class ForcedAskTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ForcedAskTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("forced").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("forced").resolve(name);
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
        return m;
    }

    @Test
    public void discardUnlessTypeWithNoTypedCardTakesTheForcedHand() throws Exception {
        StepMachine m = replay("discard-unless-forced-hand.jsonl");
        // The setup Islands stay on the battlefield; the two the ETB draws are
        // new cards, so count and name the graveyard rather than a bound ref.
        CardCollectionView grave = m.seats[0].getCardsIn(ZoneType.Graveyard);
        assertEquals(grave.size(), 2, "both drawn Islands are discarded");
        for (Card c : grave) {
            assertEquals(c.getName(), "Island", "the forced discard is the two drawn Islands");
        }
        assertEquals(m.seats[0].getCardsIn(ZoneType.Hand).size(), 0, "the whole two-card hand was discarded");
        assertTrue(m.notes.stream().noneMatch(n -> n.startsWith("loose: AI answered discard unless type")),
                "the forced discard is taken, not missed, notes: " + m.notes);
    }

    @Test
    public void zeroSacrificeIsAnsweredWithTheEmptyPick() throws Exception {
        StepMachine m = replay("sacrifice-x0.jsonl");
        assertTrue(m.card("p0:Swamp").isInZone(ZoneType.Battlefield), "X = 0 sacrifices no Swamp (first)");
        assertTrue(m.card("p0:Swamp#2").isInZone(ZoneType.Battlefield), "X = 0 sacrifices no Swamp (second)");
        assertTrue(m.card("p0:Swamp#3").isInZone(ZoneType.Battlefield), "X = 0 sacrifices no Swamp (third)");
        assertEquals(m.seats[0].getLife(), 20, "X = 0 loses no life");
        assertTrue(m.notes.stream().noneMatch(n -> n.startsWith("loose: AI answered sacrifice")),
                "the 0..0 sacrifice is forced, not missed, notes: " + m.notes);
    }

    @Test
    public void askAfterTheLastStepIsNotedAndAnsweredNotStrict() throws Exception {
        StepMachine m = replay("ask-after-last-step.jsonl");
        assertEquals(m.stepIdx, m.steps.size(), "the item ran every step; the ask lands past the window");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("ask after the last step: discard at step 6")),
                "the post-window discard is noted, notes: " + m.notes);
        assertTrue(m.notes.stream().noneMatch(n -> n.startsWith("loose: AI answered discard")),
                "the post-window ask is not reported as an in-window loose miss, notes: " + m.notes);
    }
}
