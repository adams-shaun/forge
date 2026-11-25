// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.game.card.CounterEnumType;
import forge.game.player.Player;

/** A seat's setup "player_counters" reach the player (xproof cluster
 * xpt-a32b5388, Demon of Dark Schemes): the driver read life, speed and the
 * zones but never player_counters, so the {2}{B}, Pay {E}{E}{E}{E} ability
 * could not start although gorge's setup holds the 4 energy. Each entry is
 * the COMPLETE count of its kind (also 0), with no event and no trigger. */
public class PlayerCountersSetupTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static StepMachine place(String p0Extra) {
        String line = "{\"abilities\":{},\"gorge_decisions\":[],\"id\":\"setup/player-counters\","
                + "\"item\":{\"id\":\"setup/player-counters\",\"card\":\"\",\"template\":\"setup\","
                + "\"name\":\"setup-player-counters\",\"setup\":{\"p0\":{\"battlefield\":[\"Swamp\"]"
                + p0Extra + "},\"p1\":{\"battlefield\":[\"Plains\"]}},\"steps\":[]}}";
        StepMachine m = new StepMachine(Request.parse(line), true);
        m.build();
        SetupBuilder.place(m);
        return m;
    }

    @Test
    public void energyReachesThePlayerAndTheSetupSnapshot() {
        StepMachine m = place(",\"player_counters\":[{\"kind\":\"ENERGY\",\"n\":4}]");
        Player p0 = m.seats[0];
        Player p1 = m.seats[1];
        assertEquals(p0.getCounters(CounterEnumType.ENERGY), 4, "p0 starts with the setup's 4 energy");
        assertEquals(p1.getCounters(CounterEnumType.ENERGY), 0, "the entry names one seat, not every seat");
        JsonObject snap = SnapshotWriter.snapshot("setup", m.game, m.seats);
        JsonObject counters = snap.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonObject("counters");
        assertEquals(counters.get("ENERGY").getAsInt(), 4, "the setup snapshot reports counters ENERGY=4");
        assertNull(snap.getAsJsonArray("players").get(1).getAsJsonObject().get("counters"),
                "p1 holds no player counter");
    }

    @Test
    public void everyKindIsTheCompleteCount() {
        StepMachine m = place(",\"player_counters\":[{\"kind\":\"ENERGY\",\"n\":8},"
                + "{\"kind\":\"EXPERIENCE\",\"n\":3},{\"kind\":\"POISON\",\"n\":2},{\"kind\":\"RAD\",\"n\":0}]");
        Player p0 = m.seats[0];
        assertEquals(p0.getCounters(CounterEnumType.ENERGY), 8);
        assertEquals(p0.getCounters(CounterEnumType.EXPERIENCE), 3);
        assertEquals(p0.getCounters(CounterEnumType.POISON), 2);
        assertEquals(p0.getCounters(CounterEnumType.RAD), 0);
        assertFalse(p0.getCounters().elementSet().contains(CounterEnumType.RAD), "a zero entry holds no counter");
    }

    @Test
    public void anUnknownKindFailsLoudly() {
        assertThrows(HarnessError.class, () -> place(",\"player_counters\":[{\"kind\":\"NOT_A_COUNTER\",\"n\":1}]"));
    }

    @Test
    public void aKeywordCounterIsNotAPlayerCounter() {
        assertThrows(HarnessError.class, () -> place(",\"player_counters\":[{\"kind\":\"LIFELINK\",\"n\":1}]"));
    }

    @Test
    public void aNegativeCountFailsLoudly() {
        assertThrows(HarnessError.class, () -> place(",\"player_counters\":[{\"kind\":\"ENERGY\",\"n\":-1}]"));
    }
}
