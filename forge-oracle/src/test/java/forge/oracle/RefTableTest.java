// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.List;

import org.testng.annotations.Test;

/** Scenario refs as gorge's runner reads them (rules/oracle_run.go splitRef,
 * resolve, build), with no Game (P1-2). */
public class RefTableTest {
    private static RefTable.Obj card(int id, int owner, String name) {
        return new RefTable.Obj(id, owner, owner, List.of(name), false, false);
    }

    private static RefTable.Obj token(int id, int controller, String name, boolean onBattlefield) {
        return new RefTable.Obj(id, controller, controller, List.of(name), true, onBattlefield);
    }

    @Test
    public void parsesEveryRefShape() {
        RefTable.Ref p = RefTable.parse("p1");
        assertTrue(p.player);
        assertEquals(p.seat, 1);
        RefTable.Ref c = RefTable.parse("p0:Grizzly Bears");
        assertFalse(c.player);
        assertEquals(c.seat, 0);
        assertEquals(c.name, "Grizzly Bears");
        assertEquals(c.nth, 1);
        assertFalse(c.token);
        RefTable.Ref n = RefTable.parse("p1:Grizzly Bears#2");
        assertEquals(n.name, "Grizzly Bears");
        assertEquals(n.nth, 2);
        RefTable.Ref t = RefTable.parse("p0:token:Goblin#3");
        assertTrue(t.token);
        assertEquals(t.name, "Goblin");
        assertEquals(t.nth, 3);
        // A name with a colon after the seat stays whole.
        assertEquals(RefTable.parse("p0:Borrowed Time: Reprise").name, "Borrowed Time: Reprise");
    }

    @Test
    public void rejectsMalformedRefs() {
        for (String bad : new String[] {"", "q0:X", "p:X", "px:X", "Grizzly Bears", "p0:X#0", "p0:X#", "p0:X#a", "p0:X#-1"}) {
            try {
                RefTable.parse(bad);
                fail("accepted " + bad);
            } catch (HarnessError expected) {
                assertTrue(expected.getMessage().startsWith("harness: "), expected.getMessage());
            }
        }
    }

    @Test
    public void bindsSetupOrdinalsPerSeatAndNameAcrossZones() {
        RefTable r = new RefTable();
        assertEquals(r.bind(0, "Grizzly Bears", 1), "p0:Grizzly Bears");
        assertEquals(r.bind(0, "Shock", 2), "p0:Shock");
        // A second placement of the name, in any zone, is #2; the other seat restarts at 1.
        assertEquals(r.bind(0, "Grizzly Bears", 3), "p0:Grizzly Bears#2");
        assertEquals(r.bind(1, "Grizzly Bears", 41), "p1:Grizzly Bears");
        assertEquals(r.boundId("p0:Grizzly Bears#2"), Integer.valueOf(3));
        // "#1" is never bound (gorge binds the first as the bare name).
        assertEquals(r.boundId("p0:Grizzly Bears#1"), null);
    }

    @Test
    public void boundRefsWinThenOwnerAndIdOrder() {
        RefTable r = new RefTable();
        r.bind(0, "Wastes", 7);
        List<RefTable.Obj> live = List.of(card(9, 0, "Wastes"), card(3, 0, "Wastes"), card(7, 0, "Wastes"),
                card(5, 1, "Wastes"), card(4, 0, "Forest"));
        assertEquals(r.resolveCard("p0:Wastes", live), 7, "the bound ref follows its card");
        // Unbound: the k-th object in id order owned by the seat with that name.
        assertEquals(r.resolveCard("p0:Wastes#1", live), 3);
        assertEquals(r.resolveCard("p0:Wastes#2", live), 7);
        assertEquals(r.resolveCard("p0:Wastes#3", live), 9);
        assertEquals(r.resolveCard("p1:Wastes", live), 5);
        try {
            r.resolveCard("p0:Wastes#4", live);
            fail("resolved past the last object");
        } catch (HarnessError expected) {
            assertTrue(expected.getMessage().contains("names no object"));
        }
    }

    @Test
    public void tokensMatchByControllerSubstringOnTheBattlefield() {
        RefTable r = new RefTable();
        List<RefTable.Obj> live = List.of(token(20, 0, "Goblin Token", true), token(21, 1, "Goblin Token", true),
                token(22, 0, "Faerie Rogue Token", true), token(23, 0, "Goblin Token", false), token(24, 0, "Goblin Token", true));
        assertEquals(r.resolveCard("p0:token:goblin", live), 20, "case-insensitive substring");
        assertEquals(r.resolveCard("p0:token:Goblin#2", live), 24, "a token off the battlefield is not counted");
        assertEquals(r.resolveCard("p1:token:Goblin", live), 21);
        assertEquals(r.resolveCard("p0:token:Rogue", live), 22);
    }

    @Test
    public void playerRefIsNotACard() {
        try {
            new RefTable().resolveCard("p1", List.of());
            fail("a player ref resolved as a card");
        } catch (HarnessError expected) {
            assertTrue(expected.getMessage().contains("names a player"));
        }
        assertEquals(RefTable.parseSeat("p12"), 12);
        assertEquals(RefTable.parseSeat("p"), -1);
        assertEquals(RefTable.parseSeat("p1a"), -1);
    }
}
