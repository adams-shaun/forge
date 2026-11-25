// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertFalse;
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
 * The xproof harness cluster for cast_mode "foretell" (xpt-42b07d82): p0 has
 * five Mountains and Sozin's Comet (Foretell {2}{R}); steps 0-1 tap two
 * Mountains and step 2 is a cast with cast_mode foretell. Foretelling is a
 * special action (CR 702.143a, 116.2h): pay {2}, exile the card face down; it
 * uses no stack. The driver used to pick the ordinary cast and then insist
 * the card be on the stack ("was not put on the stack"). The fixture is the
 * forge-export request of xs:XP:sozin-s-comet:cast:s1.
 */
public class ForetellSpecialActionTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ForetellSpecialActionTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("foretell").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("foretell").resolve(name);
    }

    @Test
    public void foretellExilesFaceDownWithoutTheStack() throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture("sozins-comet.jsonl"), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        Card c = m.card("p0:Sozin's Comet");
        assertTrue(c.isInZone(ZoneType.Exile), "Sozin's Comet is exiled");
        assertTrue(c.isForetold(), "Sozin's Comet is foretold");
        assertTrue(c.isFaceDown(), "Sozin's Comet is exiled face down");
        assertTrue(m.game.getStack().isEmpty(), "foretell uses no stack");
        assertFalse(m.seats[0].getCardsIn(ZoneType.Hand).contains(c), "the card left the hand");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("foretell step 2: p0:Sozin's Comet exiled face down")),
                "the foretell is noted, notes: " + m.notes);
    }
}
