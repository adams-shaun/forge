// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.oracle.DecisionQueue.Decision;

/**
 * The CR 404.3 graveyard order pin. forge-export adds one "order" decision
 * (gorge_kind "graveyard_order", one pick per card) for a simultaneous
 * multi-card move to a graveyard, separate from the selection decision that
 * was already consumed. orderCards consumes the picks instead of strict-
 * missing Forge's own "order to Graveyard" ask.
 */
public class OrderCardsTest {
    private static final String ORDER = "[{\"step\":0,\"seat\":0,\"kind\":\"order\","
            + "\"gorge_kind\":\"graveyard_order\",\"picks\":[\"Poison Dart Frog\",\"Akal Pakal\"]}]";

    private static StepMachine machine(String decisions) {
        Request req = Request.parse("{\"id\":\"order\",\"item\":{\"steps\":[{\"op\":\"pass\"}]},"
                + "\"gorge_decisions\":" + decisions + "}");
        StepMachine m = new StepMachine(req, false);
        m.stepIdx = 0;
        return m;
    }

    @Test
    public void consumesThePinnedOrder() {
        StepMachine m = machine(ORDER);
        List<String> got = m.orderCardsByPicks(List.of("Akal Pakal", "Poison Dart Frog"),
                m.take(0, DecisionQueue::isGraveyardOrder), "order to Graveyard", s -> s);
        assertEquals(got, List.of("Poison Dart Frog", "Akal Pakal"));
        assertTrue(m.notes.toString().contains("order step 0"), m.notes.toString());
        assertNull(m.take(0, DecisionQueue::isGraveyardOrder), "the pin is consumed once");
    }

    @Test
    public void duplicateNamesTakeTheFirstUnusedCopy() {
        StepMachine m = machine("[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"graveyard_order\","
                + "\"picks\":[\"Island\",\"Island\",\"Forest\"]}]");
        List<String> got = m.orderCardsByPicks(List.of("Forest", "Island", "Island"),
                m.take(0, DecisionQueue::isGraveyardOrder), "order to Graveyard", s -> s);
        assertEquals(got, List.of("Island", "Island", "Forest"));
    }

    @Test
    public void aPickListThatMissesAnOfferedCardIsNotAnAnswer() {
        StepMachine m = machine("[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"graveyard_order\","
                + "\"picks\":[\"Akal Pakal\"]}]");
        assertNull(m.orderCardsByPicks(List.of("Akal Pakal", "Poison Dart Frog"),
                m.take(0, DecisionQueue::isGraveyardOrder), "order to Graveyard", s -> s));
        assertTrue(m.notes.toString().contains("does not name every card"), m.notes.toString());
    }

    @Test
    public void extraPicksAreSkipped() {
        StepMachine m = machine("[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"graveyard_order\","
                + "\"picks\":[\"Other\",\"Poison Dart Frog\",\"Akal Pakal\"]}]");
        List<String> got = m.orderCardsByPicks(List.of("Akal Pakal", "Poison Dart Frog"),
                m.take(0, DecisionQueue::isGraveyardOrder), "order to Graveyard", s -> s);
        assertEquals(got, List.of("Poison Dart Frog", "Akal Pakal"),
                "a pin covering an earlier move in the same step still orders this one");
    }

    @Test
    public void anAskWithoutAPinLooseMisses() {
        StepMachine m = machine("[]");
        assertNull(m.orderCardsByPicks(List.of("Akal Pakal", "Poison Dart Frog"),
                m.take(0, DecisionQueue::isGraveyardOrder), "order to Graveyard", s -> s));
        assertTrue(m.notes.toString().contains("loose: AI answered order"), m.notes.toString());
    }

    @Test
    public void onlyTheGraveyardOrderRoleMatches() {
        Decision order = machine(ORDER).dq.all().get(0);
        assertTrue(DecisionQueue.isGraveyardOrder(order));
        Decision trigger = machine("[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"trigger_order\","
                + "\"picks\":[\"a\",\"b\"]}]").dq.all().get(0);
        assertFalse(DecisionQueue.isGraveyardOrder(trigger));
        Decision arrange = machine("[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"arrange\","
                + "\"picks\":[\"a\",\"b\"]}]").dq.all().get(0);
        assertFalse(DecisionQueue.isGraveyardOrder(arrange));
    }

    @Test
    public void anUnaskedPinIsNotALeftover() {
        StepMachine m = machine(ORDER);
        assertNull(m.dq.leftover());
        assertEquals(m.dq.unconsumed().size(), 0,
                "an order pin only answers the ask; a move Forge never asks about is unobservable");
    }
}
