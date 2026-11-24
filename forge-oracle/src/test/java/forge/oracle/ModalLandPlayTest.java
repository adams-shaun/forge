// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
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
 * A modal double-faced card played as its land back face. CR 712.12: a player
 * playing a modal double-faced card as a land chooses one of its faces that's a
 * land, and it enters the battlefield with that face up. Glasswing Grace's
 * front is a sorcery-speed creature spell and Age-Graced Chapel its land back;
 * Turntimber Symbiosis // Turntimber, Serpentine Wood asks "you may pay 3
 * life" as the land back enters.
 *
 * <p>landFor scanned only Card.getSpellAbilities(), the front state's, so these
 * plays threw "has no land play" (22 xproof harness clusters, every modal DFC
 * with a land back face).
 */
public class ModalLandPlayTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ModalLandPlayTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("play").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("play").resolve(name);
    }

    private static StepMachine play(String fixture) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(fixture), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        return m;
    }

    @Test
    public void glasswingGracePlaysAsAgeGracedChapel() throws Exception {
        StepMachine m = play("glasswing-grace-age-graced-chapel.jsonl");
        Card c = (Card) m.entity("p0:Glasswing Grace");
        assertNotNull(c);
        assertTrue(c.isInZone(ZoneType.Battlefield), "on the battlefield, in " + c.getZone());
        assertEquals(c.getName(), "Age-Graced Chapel");
        assertTrue(c.isLand(), "enters as the land face");
    }

    @Test
    public void turntimberSymbiosisPaysThreeLifeAsTurntimberSerpentineWood() throws Exception {
        StepMachine m = play("turntimber-symbiosis-pay-life.jsonl");
        Card c = (Card) m.entity("p0:Turntimber Symbiosis");
        assertNotNull(c);
        assertTrue(c.isInZone(ZoneType.Battlefield), "on the battlefield, in " + c.getZone());
        assertEquals(c.getName(), "Turntimber, Serpentine Wood");
        assertEquals(m.seats[0].getLife(), 17, "the entry choice paid 3 life");
        assertFalse(c.isTapped(), "paid, so it enters untapped");
    }
}
