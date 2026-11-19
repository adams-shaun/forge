// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** A modal DFC whose front face is a sorcery enters on its back-face Land:
 * the back face must be resolved before the setup move, or GameAction refuses
 * the front-face sorcery (CR 307.4) and the card stays in hand. */
public class SetupBackFaceBeforeMoveTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Player place(String card) {
        String line = "{\"abilities\":{},\"gorge_decisions\":[],\"id\":\"setup/back-face\","
                + "\"item\":{\"id\":\"setup/back-face\",\"card\":\"\",\"template\":\"setup\","
                + "\"name\":\"setup-back-face\",\"setup\":{\"p0\":{\"battlefield\":[\"" + card + "\"],"
                + "\"back_face\":[\"" + card + "\"],\"hand\":[\"Grizzly Bears\"]},"
                + "\"p1\":{\"battlefield\":[\"Grizzly Bears\"]}},\"steps\":[]}}";
        StepMachine m = new StepMachine(Request.parse(line), true);
        m.build();
        SetupBuilder.place(m);
        return m.seats[0];
    }

    @Test
    public void TestSetupBackFaceBeforeMove() {
        Player p0 = place("Sea Gate Restoration");
        var hand = p0.getCardsIn(ZoneType.Hand);
        assertEquals(hand.size(), 1,
                "p0's hand holds only the request's hand; the back-face card is not left there");
        assertEquals(hand.get(0).getName(), "Grizzly Bears");
        Card permanent = p0.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> "Sea Gate, Reborn".equals(c.getName())).findFirst().orElse(null);
        assertNotNull(permanent, "p0's battlefield holds the back-face Land \"Sea Gate, Reborn\"");
        assertTrue(permanent.getType().isLand(), "the permanent stands on the back-face Land");
        assertEquals(permanent.getCounters().size(), 0, "no entry counter was invented");
    }

    @Test
    public void backFaceSagaEntersWithItsOwnLore() {
        Player p0 = place("Jecht, Reluctant Guardian");
        Card saga = p0.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> "Braska's Final Aeon".equals(c.getName())).findFirst().orElse(null);
        assertNotNull(saga, "the back-face Saga is on the battlefield");
        assertTrue(saga.getType().hasSubtype("Saga"), "the entering face is the Saga");
        assertEquals(saga.getCounters(CounterEnumType.LORE), 1,
                "the entering face's own lore counter replacement applies");
    }
}
