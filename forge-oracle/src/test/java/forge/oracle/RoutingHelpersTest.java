// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.card.MagicColor;
import forge.oracle.DecisionQueue.Decision;

/** P2 routing helpers that need no Game: gorge's label shapes from real
 * forge-export rows. */
public class RoutingHelpersTest {
    private static Decision d(String json) {
        List<JsonObject> raw = new ArrayList<>();
        raw.add(JsonParser.parseString(json).getAsJsonObject());
        return new DecisionQueue(raw).all().get(0);
    }

    @Test
    public void manaPickLabelsGiveOneColour() {
        assertEquals(StepMachine.manaColor("Add R"), MagicColor.RED);
        assertEquals(StepMachine.manaColor("Add {G}"), MagicColor.GREEN);
        assertEquals(StepMachine.manaColor("Pay 1: Add W"), MagicColor.WHITE, "filter-land stage label");
        assertEquals(StepMachine.manaColor("Add C"), (byte) 0, "colourless is not a colour pick");
        assertEquals(StepMachine.manaColor("Add RR"), (byte) 0);
        assertEquals(StepMachine.manaColor("Pay 1: Add any color"), (byte) 0);
    }

    @Test
    public void triggerLabelsNormalizeTheHostName() {
        assertEquals(StepMachine.norm("When Bria, Riptide Rogue enters, draw.", "Bria, Riptide Rogue"),
                StepMachine.norm("When CARDNAME enters, draw.", "Bria, Riptide Rogue"));
        assertTrue(StepMachine.norm("Prowess (Whenever you cast...)", "X").startsWith(StepMachine.norm("Prowess", "X")));
    }

    @Test
    public void playDeclineAndTapCostAreYesNo() {
        Decision decline = d("{\"step\":1,\"seat\":0,\"kind\":\"mode\",\"picks\":[],\"pick_refs\":[],\"resume\":\"play\",\"gorge_kind\":\"modes\",\"min\":0,\"max\":1}");
        assertTrue(DecisionQueue.isYesNo(decline));
        assertFalse(DecisionQueue.yes(decline));
        Decision tap = d("{\"step\":1,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Spider-Man, To the Rescue\"],"
                + "\"pick_refs\":[\"p0:Spider-Man, To the Rescue\"],\"pick_kinds\":[\"trigger_cost_tap\"],\"gorge_kind\":\"choose\"}");
        assertTrue(DecisionQueue.isYesNo(tap));
        assertTrue(DecisionQueue.yes(tap));
    }
}
