// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** A modal DFC whose front face is a sorcery enters on its back-face Land:
 * the back face must be resolved before the setup move, or GameAction refuses
 * the front-face sorcery (CR 307.4) and the card stays in hand. The reverse
 * face layout (a permanent front, a spell back) must also reach the battlefield:
 * setup states a board and gorge's placement fold has no CR 307.4 guard. */
public class SetupBackFaceBeforeMoveTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static String request(String card) {
        return "{\"abilities\":{},\"gorge_decisions\":[],\"id\":\"setup/back-face\","
                + "\"item\":{\"id\":\"setup/back-face\",\"card\":\"\",\"template\":\"setup\","
                + "\"name\":\"setup-back-face\",\"setup\":{\"p0\":{\"battlefield\":[\"" + card + "\"],"
                + "\"back_face\":[\"" + card + "\"],\"hand\":[\"Grizzly Bears\"]},"
                + "\"p1\":{\"battlefield\":[\"Grizzly Bears\"]}},\"steps\":[]}}";
    }

    private static StepMachine placeMachine(String card) {
        StepMachine m = new StepMachine(Request.parse(request(card)), true);
        m.build();
        SetupBuilder.place(m);
        return m;
    }

    private static Player place(String card) {
        return placeMachine(card).seats[0];
    }

    /** The driven setup checkpoint: the first main-1 priority of the scenario
     * turn, where gorge records "setup". */
    private static JsonObject setupSnapshot(StepMachine m) {
        ResultRow row = m.run();
        assertNull(row.harness, "the scenario drives to its setup checkpoint");
        assertEquals(row.snapshots.size(), 1, "one setup snapshot");
        return row.snapshots.get(0);
    }

    private static JsonObject permanent(JsonObject setup, String name) {
        JsonArray perms = setup.getAsJsonArray("permanents");
        for (int i = 0; i < perms.size(); i++) {
            JsonObject p = perms.get(i).getAsJsonObject();
            if (name.equals(p.get("name").getAsString())) {
                return p;
            }
        }
        return null;
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
    public void backFaceSpellEntersDirectly() {
        Player p0 = place("Augmenter Pugilist");
        var hand = p0.getCardsIn(ZoneType.Hand);
        assertEquals(hand.size(), 1,
                "p0's hand holds only the request's hand; the back-face spell is not left there");
        assertEquals(hand.get(0).getName(), "Grizzly Bears");
        Card permanent = p0.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> "Echoing Equation".equals(c.getName())).findFirst().orElse(null);
        assertNotNull(permanent, "p0's battlefield holds the back-face Sorcery \"Echoing Equation\"");
        assertTrue(permanent.isSorcery(), "the permanent stands on the back-face Sorcery");

        JsonObject setup = setupSnapshot(placeMachine("Augmenter Pugilist"));
        JsonObject onBoard = permanent(setup, "Echoing Equation");
        assertNotNull(onBoard, "the back-face Sorcery is still on the battlefield at the setup checkpoint");
        assertEquals(onBoard.getAsJsonArray("types").toString(), "[\"Sorcery\"]");
    }

    @Test
    public void backFaceSagaEntersWithItsOwnLore() {
        JsonObject setup = setupSnapshot(placeMachine("Jecht, Reluctant Guardian"));
        JsonObject saga = permanent(setup, "Braska's Final Aeon");
        assertNotNull(saga, "the back-face Saga is on the battlefield");
        assertEquals(saga.getAsJsonObject("counters").get("LORE").getAsInt(), 2,
                "the entering face's own lore counter applies, then the first main-1 turn-based action");
        JsonArray hand = setup.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("hand");
        assertEquals(hand.size(), 2, "chapter I resolved: Grizzly Bears plus the card it drew");
        assertEquals(hand.get(1).getAsString(), "Wastes", "chapter I's draw is the next library card");
    }
}
