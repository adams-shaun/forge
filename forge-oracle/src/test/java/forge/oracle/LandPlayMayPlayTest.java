// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

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
 * A land played from outside the hand. Ka-Zar of the Savage Land: "You may
 * play lands from the top of your library." Irascible Wolverine: "When this
 * creature enters, exile the top card of your library. Until end of turn, you
 * may play that card." CR 305.1: an effect may let a player play a land from
 * another zone (still one land a turn, CR 305.2).
 *
 * <p>Forge offers such a play as the copy of the land ability a MayPlay static
 * grants (Card.getAllPossibleAbilities, getMayPlay set). landFor took the
 * card's own land ability, which only plays from the hand, so the play did
 * nothing and checkActed threw "step N (play) p0:&lt;land&gt; was not played"
 * (xproof harness clusters, Ka-Zar f0t0 s2 and Irascible Wolverine f0t0 s2).
 */
public class LandPlayMayPlayTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(LandPlayMayPlayTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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

    private static void playsFromElsewhere(String fixture, String land, String note) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(fixture), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }

        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();

        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith(note)), "notes: " + row.notes);

        Card c = (Card) m.entity(land);
        assertNotNull(c, land);
        assertTrue(c.isInZone(ZoneType.Battlefield), land + " is on the battlefield, in " + c.getZone());
    }

    @Test
    public void kaZarPlaysTheLibraryTopForest() throws Exception {
        playsFromElsewhere("ka-zar-library-top-forest.jsonl", "p0:Forest#8", "play: from Library by Ka-Zar of the Savage Land");
    }

    @Test
    public void irascibleWolverinePlaysTheExiledMountain() throws Exception {
        playsFromElsewhere("irascible-wolverine-exiled-mountain.jsonl", "p0:Mountain#4", "play: from Exile by Irascible Wolverine");
    }
}
