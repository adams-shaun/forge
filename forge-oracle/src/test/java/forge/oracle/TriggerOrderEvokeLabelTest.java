// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.keyword.Keyword;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/**
 * CR 702.74a + 603.3b: an evoked Emptiness enters with two triggers, the Evoke
 * sacrifice and its own "if {W}{W} was spent" ETB. gorge names the first with a
 * fixed label ("Emptiness: sacrifice it (evoked)"), which no Forge trigger text
 * starts with, so the text-prefix score was 0 and the order missed. The label
 * binds by role to the host's Evoke keyword trigger.
 */
public class TriggerOrderEvokeLabelTest {
    private static final String EVOKE = "Emptiness: sacrifice it (evoked)";
    private static final String ETB = "Emptiness: When Emptiness enters, if {W}{W} was spent to cast it, "
            + "return target creature card from your graveyard to the battlefield.";

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static StepMachine machine(String first, String second) {
        String decisions = "[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"trigger_order\","
                + "\"picks\":[\"" + first + "\",\"" + second + "\"],\"pick_kinds\":[\"trigger\",\"trigger\"],"
                + "\"resume\":\"trigger_order\",\"min\":2,\"max\":2}]";
        Request req = Request.parse("{\"id\":\"order\",\"item\":{\"name\":\"order\",\"steps\":[{\"op\":\"pass\"}]},"
                + "\"gorge_decisions\":" + decisions + "}");
        StepMachine m = new StepMachine(req, true);
        m.build();
        m.stepIdx = 0;
        return m;
    }

    private static SpellAbility sa(Card c, boolean evoke) {
        for (Trigger t : c.getTriggers()) {
            boolean isEvoke = t.getKeyword() != null && t.getKeyword().getKeyword() == Keyword.EVOKE;
            if (isEvoke == evoke) {
                SpellAbility sa = t.ensureAbility();
                assertNotNull(sa);
                return sa;
            }
        }
        throw new AssertionError("no " + (evoke ? "Evoke" : "non-Evoke") + " trigger on " + c.getName());
    }

    private void check(String first, String second, boolean evokeFirst) {
        StepMachine m = machine(first, second);
        Card c = CardFactory.getCard(StaticData.instance().getCommonCards().getCard("Emptiness"), m.seats[0], m.game);
        c.setGameTimestamp(m.game.getNextTimestamp());
        SpellAbility ev = sa(c, true);
        SpellAbility etb = sa(c, false);
        List<SpellAbility> got = m.orderTriggers(0, List.of(etb, ev));
        assertNotNull(got);
        assertEquals(got.size(), 2);
        // picks[0] goes on the stack first and resolves last: the list is resolution order, reversed.
        assertSame(got.get(0), evokeFirst ? etb : ev);
        assertSame(got.get(1), evokeFirst ? ev : etb);
        assertTrue(m.notes.toString().contains("trigger order step 0: 2 bound"), m.notes.toString());
    }

    @Test
    public void evokeSacrificeLabelBindsTheEvokeTrigger() {
        check(EVOKE, ETB, true);
    }

    @Test
    public void evokeSacrificeLabelBindsInEitherOrder() {
        check(ETB, EVOKE, false);
    }
}
