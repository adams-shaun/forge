// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Setup re-runs a card's printed R:Event$ Moved entry counter (Tonberry's
 * stun counter), and the scenario's counters field is ADDED to it. */
public class SetupEntryCounterTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Card place(String countersJson) {
        String line = "{\"abilities\":{},\"gorge_decisions\":[],\"id\":\"setup/entry-counter\","
                + "\"item\":{\"id\":\"setup/entry-counter\",\"card\":\"\",\"template\":\"setup\","
                + "\"name\":\"setup-entry-counter\",\"setup\":{\"p0\":{\"battlefield\":[\"Tonberry\"]"
                + (countersJson == null ? "" : ",\"counters\":" + countersJson)
                + "},\"p1\":{\"battlefield\":[\"Grizzly Bears\"]}},\"steps\":[]}}";
        StepMachine m = new StepMachine(Request.parse(line), true);
        m.build();
        SetupBuilder.place(m);
        Player p0 = m.seats[0];
        return p0.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> "Tonberry".equals(c.getName())).findFirst().orElseThrow();
    }

    @Test
    public void printedMovedBodyCounterStays() {
        Card tonberry = place(null);
        assertFalse(tonberry.isTapped(),
                "the entry tap is undone: the scenario's tapped field re-states it");
        assertEquals(tonberry.getCounters(CounterEnumType.STUN), 1,
                "the printed R:Event$ Moved stun counter is an entry grant setup re-runs");
    }

    @Test
    public void scenarioCountersAddOnTopOfTheEntryGrant() {
        Card tonberry = place("{\"Tonberry\":{\"STUN\":1}}");
        assertEquals(tonberry.getCounters(CounterEnumType.STUN), 2,
                "the counters field is counters ADDED to what the card enters with");
    }
}
