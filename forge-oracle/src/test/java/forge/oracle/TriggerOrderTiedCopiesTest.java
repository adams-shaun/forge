// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.List;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/**
 * CR 603.3b: the controller orders simultaneous triggers. Dromoka, the
 * Eternal triggers twice (Dromoka and Nadaar are both Dragons) beside Nadaar's
 * venture. The two Dromoka triggers tie on the label, and they are copies of
 * one trigger, so which copy goes first is unobservable. Only the triggers
 * tied on a label compete for it: Nadaar's different trigger must not turn the
 * copy tie into a "binds no unique Forge trigger" miss, while a tie between
 * different effects still must.
 */
public class TriggerOrderTiedCopiesTest {
    private static final String DROMOKA = "Dromoka, the Eternal: Whenever a Dragon you control attacks, bolster 2. "
            + "(Choose a creature with the least toughness among creatures you control and put two +1/+1 counters on it.)";
    private static final String NADAAR = "Nadaar, Selfless Paladin: Whenever Nadaar, Selfless Paladin enters or attacks, "
            + "venture into the dungeon. (Enter the first room or advance to the next room.)";

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static StepMachine machine(String picksJson) {
        String decisions = "[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"trigger_order\","
                + "\"picks\":" + picksJson + ",\"pick_kinds\":[\"trigger\",\"trigger\",\"trigger\"],"
                + "\"resume\":\"trigger_order\",\"min\":3,\"max\":3}]";
        Request req = Request.parse("{\"id\":\"order\",\"item\":{\"name\":\"order\",\"steps\":[{\"op\":\"pass\"}]},"
                + "\"gorge_decisions\":" + decisions + "}");
        StepMachine m = new StepMachine(req, true);
        m.build();
        m.stepIdx = 0;
        return m;
    }

    private static Card card(StepMachine m, String name) {
        Card c = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), m.seats[0], m.game);
        c.setGameTimestamp(m.game.getNextTimestamp());
        return c;
    }

    private static SpellAbility sa(Card c, int idx) {
        Trigger t = c.getTriggers().get(idx);
        SpellAbility sa = t.ensureAbility();
        assertNotNull(sa, c.getName() + " trigger " + idx + " has an ability");
        return sa;
    }

    private static String json(String... picks) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < picks.length; i++) {
            b.append(i > 0 ? "," : "").append('"').append(picks[i].replace("\"", "\\\"")).append('"');
        }
        return b.append(']').toString();
    }

    @Test
    public void twoCopiesBesideADifferentTriggerBindWithoutAMiss() {
        StepMachine m = machine(json(DROMOKA, DROMOKA, NADAAR));
        Card dromoka = card(m, "Dromoka, the Eternal");
        Card nadaar = card(m, "Nadaar, Selfless Paladin");
        SpellAbility d1 = sa(dromoka, 0);
        SpellAbility d2 = d1.copy(dromoka, m.seats[0], false);
        SpellAbility n = sa(nadaar, 1);
        List<SpellAbility> got = m.orderTriggers(0, List.of(n, d1, d2));
        assertNotNull(got);
        assertEquals(got.size(), 3);
        // picks[0] goes on the stack first and resolves last: the list is resolution order, reversed.
        assertSame(got.get(0), n, "Nadaar's venture goes on the stack last, so it resolves first");
        assertTrue(m.notes.toString().contains("trigger order step 0: 3 bound"), m.notes.toString());
    }

    @Test
    public void aTieBetweenDifferentEffectsStillMisses() {
        // "Whenever" names no one trigger: Dromoka's and Nadaar's tie on it and are different effects.
        StepMachine m = machine(json("Whenever", DROMOKA, NADAAR));
        Card dromoka = card(m, "Dromoka, the Eternal");
        Card nadaar = card(m, "Nadaar, Selfless Paladin");
        SpellAbility d1 = sa(dromoka, 0);
        SpellAbility d2 = d1.copy(dromoka, m.seats[0], false);
        SpellAbility n = sa(nadaar, 1);
        try {
            m.orderTriggers(0, List.of(d1, d2, n));
            fail("Dromoka and Nadaar are different effects, so the tie is not an answer");
        } catch (StrictMiss e) {
            assertTrue(e.getMessage().contains("binds no unique Forge trigger"), e.getMessage());
        }
    }
}
