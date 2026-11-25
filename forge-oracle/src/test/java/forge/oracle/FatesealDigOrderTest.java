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
import forge.game.zone.ZoneType;

/**
 * Spin into Myth's fateseal (CR 701.29): Dig with ChangeNum$ Any,
 * LibraryPosition$ -1 and LibraryPosition2$ 0. gorge logs one choose_n
 * "dig" pick naming the cards that go to the bottom, in pick order; it poses
 * no order decision. Forge's DigEffect still asks orderMoveToZoneList for the
 * moved cards, which the driver answers from the pick.
 */
public class FatesealDigOrderTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(FatesealDigOrderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("unmapped").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("unmapped").resolve(name);
    }

    private static StepMachine replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        return m;
    }

    @Test
    public void fatesealPicksToBottomAnswerTheOrderAsk() throws Exception {
        StepMachine m = replay("fateseal-dig-order.jsonl");
        Card orni = (Card) m.entity("p1:Ornithopter");
        assertTrue(orni.isInZone(ZoneType.Library), "Ornithopter went to the library: " + m.notes);
        // gorge picked Ornithopter then Plains for the bottom: the first pick
        // is moved first, so it ends one above the last card (CR 401.4).
        java.util.List<Card> lib = new java.util.ArrayList<>(orni.getOwner().getCardsIn(ZoneType.Library));
        assertEquals(lib.indexOf(orni), lib.size() - 2, "Ornithopter sits second from the bottom");
        assertEquals(lib.get(lib.size() - 1).getName(), "Plains");
        assertEquals(lib.get(0).getName(), "Plains", "the fateseal left no Ornithopter on top");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("order step 7: order to Library")),
                "the dig pick answers Forge's order ask, notes: " + m.notes);
    }
}
