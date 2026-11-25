// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.game.player.Player;

/** A seat's setup "life" is a state, not an event (xproof cluster
 * xpt-57a0a28c, Angel of Vitality). The driver wrote it with Player.setLife
 * after the battlefield was placed, and Forge turns a raise into gainLife
 * (CR 119.5): the battlefield's "if you would gain life, you gain that much
 * life plus 1 instead" (CR 614) made a setup life of 25 read 26, and a
 * "whenever an opponent loses life" trigger fired on a setup life below 20.
 * The starting life is now written before any permanent exists. */
public class SetupLifeReplacementTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static StepMachine place(String p0, String p1) {
        String line = "{\"abilities\":{},\"gorge_decisions\":[],\"id\":\"setup/life\","
                + "\"item\":{\"id\":\"setup/life\",\"card\":\"\",\"template\":\"setup\","
                + "\"name\":\"setup-life\",\"setup\":{\"p0\":{" + p0 + "},\"p1\":{" + p1 + "}},\"steps\":[]}}";
        StepMachine m = new StepMachine(Request.parse(line), true);
        m.build();
        SetupBuilder.place(m);
        return m;
    }

    private static int snapshotLife(StepMachine m, int seat) {
        JsonObject snap = SnapshotWriter.snapshot("setup", m.game, m.seats);
        return snap.getAsJsonArray("players").get(seat).getAsJsonObject().get("life").getAsInt();
    }

    @Test
    public void aLifeGainReplacementOnTheBattlefieldDoesNotChangeTheSetupLife() {
        StepMachine m = place("\"battlefield\":[\"Angel of Vitality\"],\"life\":25",
                "\"battlefield\":[\"Plains\"]");
        assertEquals(m.seats[0].getLife(), 25, "Angel of Vitality's replacement must not touch the setup life");
        assertEquals(snapshotLife(m, 0), 25, "the setup snapshot reports life 25");
        assertEquals(snapshotLife(m, 1), 20, "an unset seat keeps the default life");
    }

    @Test
    public void theReplacementStillAppliesToALaterRealGain() {
        StepMachine m = place("\"battlefield\":[\"Angel of Vitality\"],\"life\":25",
                "\"battlefield\":[\"Plains\"]");
        Player p0 = m.seats[0];
        assertTrue(p0.gainLife(2, null, null), "a real life gain still happens");
        assertEquals(p0.getLife(), 28, "the replacement is live after setup: 25 + 2 + 1");
    }

    @Test
    public void aLifeLossTriggerDoesNotSeeTheSetupLife() {
        // p1 starts at 10: p0's Exquisite Blood ("whenever an opponent loses
        // life, you gain that much life") must see no loss for it.
        StepMachine m = place("\"battlefield\":[\"Exquisite Blood\"],\"life\":15",
                "\"battlefield\":[\"Plains\"],\"life\":10");
        assertEquals(m.seats[0].getLife(), 15, "p0 keeps its setup life");
        assertEquals(m.seats[1].getLife(), 10, "p1 keeps its setup life");
        assertTrue(m.game.getStack().isEmpty(), "no life-loss trigger waits on the stack after setup");
    }

    @Test
    public void aSecondSeatsReplacementDoesNotChangeItsSetupLife() {
        // The second seat's own Angel: every seat's life is written before
        // any seat's battlefield is placed, not seat by seat.
        StepMachine m = place("\"life\":22", "\"battlefield\":[\"Angel of Vitality\"],\"life\":30");
        assertEquals(m.seats[0].getLife(), 22);
        assertEquals(m.seats[1].getLife(), 30);
    }
}
