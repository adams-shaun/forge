// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.zone.ZoneType;

/**
 * xproof xs:XP:quirion-ranger:cast:s1. Quirion Ranger's "Return a Forest you
 * control to its owner's hand: Untap target creature" (Cost$ Return<1/Forest>).
 * gorge pays the cost while activating (CR 601.2h, 602.2b) and logs the pick
 * as one choose_n with pick kind "returncost". The driver's CostReturn visit
 * fell to choice(), which records a strict miss for every cost not paid from
 * its source, so the pick stayed unconsumed: "Missing cost CostReturn answer
 * at step 4" and leftover "step 4 p0 choose_n[returncost] [Forest]". The same
 * held for every card-choosing cost on choice() (untap, reveal, put to
 * library, gain control, move exiled to graveyard, behold, collect evidence,
 * exert, enlist, remove counters among permanents, gift).
 */
public class CostReturnPickTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(CostReturnPickTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("costreturn").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("costreturn").resolve(name);
    }

    private static StepMachine run(String name) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        return new StepMachine(req, true);
    }

    /** The real find: the lone Forest is returned from gorge's pick. */
    @Test
    public void loneForestReturnConsumesGorgesPick() throws Exception {
        StepMachine m = run("quirion-ranger-lone-forest.jsonl");
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.equals("pick step 4 cost CostReturn: [p0:Forest]")),
                "the returncost pick must be consumed, notes: " + row.notes);
    }

    /** Two Forests: the one gorge picked goes back to hand, the other stays. */
    @Test
    public void choiceOfTwoReturnsThePickedForest() throws Exception {
        StepMachine m = run("quirion-ranger-two-forests.jsonl");
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        Card snow = null;
        for (Card c : m.seats[0].getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals("Snow-Covered Forest")) {
                snow = c;
            }
        }
        assertTrue(snow != null, "Snow-Covered Forest returned to hand: " + m.seats[0].getCardsIn(ZoneType.Hand));
        assertEquals(m.seats[0].getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Forest")).count(), 1L,
                "the unpicked Forest stays on the battlefield");
    }
}
