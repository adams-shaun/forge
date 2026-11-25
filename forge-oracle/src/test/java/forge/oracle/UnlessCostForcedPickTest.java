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

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.zone.ZoneType;

/**
 * xproof decision_shape cluster xpt-4561863a: a paid {@code UnlessCost$} card
 * cost whose option list is exactly the amount. Forge hands
 * chooseCardsForCost isOptional = true for an unless payment ("you may
 * pay"), so the driver asked for 0..amount and missed; but gorge's unless_pay
 * "Pay the cost" answer already elected the payment, and an elected payment
 * pays the whole cost (CR 118.12, 601.2h), so amount cards of amount options
 * is forced and gorge logs no card pick.
 *
 * <ul>
 * <li>Ill-Timed Explosion: Discard&lt;2/Card&gt; from the two drawn cards.</li>
 * <li>Ghostly Dancers + Rent Is Due: Rent Is Due's "tap two untapped creatures
 *     and/or Treasures" with exactly two untapped creatures.</li>
 * <li>Ill-Timed Explosion from a three-card hand: gorge did log the two-card
 *     discard pick, which is consumed first (the forced arm is only the
 *     fallback).</li>
 * </ul>
 */
public class UnlessCostForcedPickTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(UnlessCostForcedPickTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("unlesscost").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures")
                .resolve("unlesscost").resolve(name);
    }

    private static StepMachine replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(m.notes.stream().noneMatch(n -> n.startsWith("loose:")), "no AI-answered ask, notes: " + m.notes);
        return m;
    }

    @Test
    public void illTimedExplosionDiscardsItsTwoCardHandWithoutAGorgePick() throws Exception {
        StepMachine m = replay("ill-timed-explosion-forced-discard.jsonl");
        CardCollectionView grave = m.seats[0].getCardsIn(ZoneType.Graveyard);
        assertEquals(grave.size(), 3, "the two drawn cards and the spell: " + grave);
        assertEquals(m.seats[0].getCardsIn(ZoneType.Hand).size(), 0, "the whole two-card hand was discarded");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("unlesspay step ") || n.startsWith("unless-pay step ")),
                "the unless_pay answer elected the payment, notes: " + m.notes);
    }

    @Test
    public void ghostlyDancersTapsItsTwoUntappedCreaturesForRentIsDue() throws Exception {
        StepMachine m = replay("ghostly-dancers-forced-tap.jsonl");
        for (String name : new String[] {"p0:Ghostly Dancers", "p0:Nyxborn Courser"}) {
            Card c = m.card(name);
            assertTrue(c.isTapped(), name + " is tapped to pay Rent Is Due");
        }
    }

    @Test
    public void aLoggedDiscardPickIsStillConsumedFromAThreeCardHand() throws Exception {
        StepMachine m = replay("ill-timed-explosion-logged-discard-pick.jsonl");
        CardCollectionView hand = m.seats[0].getCardsIn(ZoneType.Hand);
        assertEquals(hand.size(), 1, "one of the three cards is kept: " + hand);
        assertEquals(hand.get(0).getName(), "Mountain", "gorge's pick (Forest, Island) is discarded, not the AI's");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("pick step ") && n.contains("cost card")),
                "the logged pick answered the cost card ask, notes: " + m.notes);
    }
}
