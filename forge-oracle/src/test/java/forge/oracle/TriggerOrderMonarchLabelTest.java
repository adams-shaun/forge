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
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/**
 * CR 603.3b + 725.2: at the monarch's end step Grave Venerations' own end-step trigger and the monarch draw
 * trigger are simultaneous. gorge names the draw with fixed text ("The monarch: draw a card"); Forge hosts it
 * on the "The Monarch" effect card worded "At the beginning of your end step, draw a card.", so no text prefix
 * matched and the order answer missed. The label binds by role to the effect host's Phase trigger.
 */
public class TriggerOrderMonarchLabelTest {
    private static final String DRAW = "The monarch: draw a card";
    private static final String OWN = "Grave Venerations: At the beginning of your end step, if you're the monarch, "
            + "return up to one target creature card from your graveyard to your hand.";
    private static final String DAMAGE = "The monarch: that creature's controller becomes the monarch";

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static StepMachine machine(String first, String second) {
        String decisions = "[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"trigger_order\","
                + "\"picks\":[\"" + first + "\",\"" + second + "\"],"
                + "\"pick_kinds\":[\"trigger\",\"trigger\"],\"resume\":\"trigger_order\",\"min\":2,\"max\":2}]";
        Request req = Request.parse("{\"id\":\"order\",\"item\":{\"name\":\"order\",\"steps\":[{\"op\":\"pass\"}]},"
                + "\"gorge_decisions\":" + decisions + "}");
        StepMachine m = new StepMachine(req, true);
        m.build();
        m.stepIdx = 0;
        return m;
    }

    private static SpellAbility sa(Card c, TriggerType mode) {
        for (Trigger t : c.getTriggers()) {
            if (t.getMode() == mode) {
                SpellAbility sa = t.ensureAbility();
                assertNotNull(sa);
                return sa;
            }
        }
        throw new AssertionError("no " + mode + " trigger on " + c.getName());
    }

    private static Card monarch(StepMachine m) {
        m.seats[0].createMonarchEffect(null);
        for (Card c : m.seats[0].getZone(forge.game.zone.ZoneType.Command)) {
            if (c.getName().equals("The Monarch")) {
                return c;
            }
        }
        throw new AssertionError("no monarch effect card");
    }

    private void check(String first, String second, boolean monarchFirst) {
        StepMachine m = machine(first, second);
        Card eff = monarch(m);
        Card gv = CardFactory.getCard(StaticData.instance().getCommonCards().getCard("Grave Venerations"), m.seats[0], m.game);
        gv.setGameTimestamp(m.game.getNextTimestamp());
        SpellAbility draw = sa(eff, TriggerType.Phase);
        SpellAbility own = sa(gv, TriggerType.Phase);
        List<SpellAbility> got = m.orderTriggers(0, List.of(own, draw));
        assertNotNull(got);
        assertEquals(got.size(), 2);
        // picks[0] goes on the stack first and resolves last: the list is resolution order, reversed.
        assertSame(got.get(0), monarchFirst ? own : draw);
        assertSame(got.get(1), monarchFirst ? draw : own);
        assertTrue(m.notes.toString().contains("trigger order step 0: 2 bound"), m.notes.toString());
    }

    @Test
    public void monarchDrawLabelBindsTheEffectPhaseTrigger() {
        check(OWN, DRAW, false);
    }

    @Test
    public void monarchDrawLabelBindsInEitherOrder() {
        check(DRAW, OWN, true);
    }

    @Test
    public void monarchDamageLabelBindsTheEffectDamageTrigger() {
        StepMachine m = machine(DAMAGE, DRAW);
        Card eff = monarch(m);
        SpellAbility dmg = sa(eff, TriggerType.DamageDone);
        SpellAbility draw = sa(eff, TriggerType.Phase);
        List<SpellAbility> got = m.orderTriggers(0, List.of(draw, dmg));
        assertNotNull(got);
        assertSame(got.get(0), draw);
        assertSame(got.get(1), dmg);
    }
}
