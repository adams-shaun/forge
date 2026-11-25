// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.game.card.Card;
import forge.game.card.CardFactory;
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
    private static final String DIG_BOTTOM = "[{\"step\":0,\"seat\":0,\"kind\":\"order\","
            + "\"picks\":[\"Forest\",\"Forest\",\"Forest\",\"Forest\"],"
            + "\"pick_refs\":[\"p0:Forest#4\",\"p0:Forest#5\",\"p0:Forest#6\",\"p0:Forest#7\"],"
            + "\"pick_kinds\":[\"dig_bottom\",\"dig_bottom\",\"dig_bottom\",\"dig_bottom\"],"
            + "\"resume\":\"dig_arrange\",\"gorge_kind\":\"arrange\",\"options\":4,\"min\":4,\"max\":4}]";
    private static final String DIG_BOTTOM_DIFFERENT = "[{\"step\":0,\"seat\":0,\"kind\":\"order\","
            + "\"picks\":[\"Island\",\"Mountain\",\"Island\",\"Island\"],"
            + "\"pick_kinds\":[\"dig_bottom\",\"dig_bottom\",\"dig_bottom\",\"dig_bottom\"],"
            + "\"resume\":\"dig_arrange\",\"gorge_kind\":\"arrange\",\"options\":4,\"min\":4,\"max\":4}]";

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static StepMachine machine(String decisions) {
        return machine(decisions, null);
    }

    /** As machine(String); extra is spliced into the request JSON verbatim as
     * extra top-level members, each written in the caller as a leading comma
     * plus a "key":value pair, for graveyard_ends. */
    private static StepMachine machine(String decisions, String extra) {
        Request req = Request.parse("{\"id\":\"order\",\"item\":{\"steps\":[{\"op\":\"pass\"}]}"
                + (extra == null ? "" : extra)
                + ",\"gorge_decisions\":" + decisions + "}");
        StepMachine m = new StepMachine(req, false);
        m.stepIdx = 0;
        return m;
    }

    private static Card card(StepMachine m, String name) {
        Card c = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), m.seats[0], m.game);
        c.setGameTimestamp(m.game.getNextTimestamp());
        return c;
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

    /** A card that enters and leaves the graveyard within one resolution (the
     * Sorin, Lord of Innistrad -6: Destroy, then ChangeZoneAll back to the
     * battlefield) is in neither checkpoint, so no pin can be derived for its
     * CR 404.3 order. orderCards keeps Forge's offered order instead of
     * strict-missing a question gorge has no recorded answer for. */
    @Test
    public void unpinnedGraveyardOrderKeepsForgeOrder() {
        StepMachine m = machine("[]");
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Forest", "Island"}) {
            cards.add(card(m, name));
        }
        List<Card> got = m.orderCards(0, cards, "order to Graveyard");
        assertEquals(got, cards, "Forge's offered order is kept");
        assertTrue(m.notes.toString().contains(
                "order to Graveyard (no order pin: keeping Forge's order)"), m.notes.toString());
        assertFalse(m.notes.toString().contains("loose"), m.notes.toString());
    }

    /** The return half of the same resolution asks "order to Battlefield", a
     * zone for which gorge records no order at all; it keeps Forge's order
     * rather than reporting a second unanswerable ask. */
    @Test
    public void unpinnedBattlefieldOrderKeepsForgeOrder() {
        StepMachine m = machine("[]");
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Forest", "Island"}) {
            cards.add(card(m, name));
        }
        List<Card> got = m.orderCards(0, cards, "order to Battlefield");
        assertEquals(got, cards, "Forge's offered order is kept");
        assertTrue(m.notes.toString().contains(
                "order to Battlefield (no order pin: keeping Forge's order)"), m.notes.toString());
        assertFalse(m.notes.toString().contains("loose"), m.notes.toString());
    }

    /** A simultaneous multi-name return to a hand has no order the snapshot
     * can see (SnapshotWriter sorts a hand's names), so no pin can exist and
     * Forge's offered order is the only answer. The Wrenn and Seven -8
     * returns the planeswalker and seven Forests this way. */
    @Test
    public void unpinnedHandOrderKeepsForgeOrder() {
        StepMachine m = machine("[]");
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Forest", "Island"}) {
            cards.add(card(m, name));
        }
        List<Card> got = m.orderCards(0, cards, "order to Hand");
        assertEquals(got, cards, "Forge's offered order is kept");
        assertTrue(m.notes.toString().contains(
                "order to Hand (no order pin: keeping Forge's order)"), m.notes.toString());
        assertFalse(m.notes.toString().contains("loose"), m.notes.toString());
    }

    /** Every other destination still strict-misses when no decision is
     * queued: the fallback is deliberately limited to the graveyard,
     * battlefield and hand asks above, so a future zone with an order truth
     * cannot silently keep Forge's order instead of reporting it. */
    @Test
    public void unpinnedOtherZoneStillLooseMisses() {
        StepMachine m = machine("[]");
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Forest", "Island"}) {
            cards.add(card(m, name));
        }
        assertNull(m.orderCards(0, cards, "order to Exile"), "no fallback for Exile");
        assertNull(m.orderCards(0, cards, "order to Library"), "no fallback for Library");
        assertTrue(m.notes.toString().contains("loose: AI answered order"), m.notes.toString());
    }

    /** A pin a checkpoint can see is still applied: the fallback only covers an
     * ask with no decision queued, so a real ordering difference is caught. */
    @Test
    public void pinnedGraveyardOrderStillApplies() {
        StepMachine m = machine("[{\"step\":0,\"seat\":0,\"kind\":\"order\","
                + "\"gorge_kind\":\"graveyard_order\",\"picks\":[\"Island\",\"Forest\"]}]");
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Forest", "Island"}) {
            cards.add(card(m, name));
        }
        List<Card> got = m.orderCards(0, cards, "order to Graveyard");
        assertEquals(got.stream().map(Card::getName).toList(),
                List.of("Island", "Forest"));
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

    /** A same-name dig/hideaway order is unobservable, but Forge still asks
     * its "order to Library" question, so orderCards must consume the
     * matching decision rather than leave it for the leftover report. */
    @Test
    public void sameNameLibraryOrderIsConsumedThoughUnobservable() {
        StepMachine m = machine(DIG_BOTTOM);
        m.build();
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cards.add(card(m, "Forest"));
        }
        List<Card> got = m.orderCards(0, cards, "order to Library");
        assertEquals(got, cards);
        assertTrue(m.notes.toString().contains("identical names"), m.notes.toString());
        assertNull(m.take(0, DecisionQueue::isCardOrder), "the same-name card order is consumed once");
        assertNull(m.dq.leftover());
    }

    /** A different-name dig/hideaway order is observable: orderCards must
     * answer Forge's "order to Library" ask from the picks, in gorge's order,
     * rather than miss it and leave the decision over. */
    @Test
    public void differentNameLibraryOrderIsAnswered() {
        StepMachine m = machine(DIG_BOTTOM_DIFFERENT);
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Mountain", "Island", "Island", "Island"}) {
            cards.add(card(m, name));
        }
        List<Card> got = m.orderCards(0, cards, "order to Library");
        assertEquals(got.stream().map(Card::getName).toList(),
                List.of("Island", "Mountain", "Island", "Island"),
                "the picks are the bottom order Forge places");
        assertTrue(m.notes.toString().contains("order step 0: order to Library"), m.notes.toString());
        assertNull(m.take(0, DecisionQueue::isCardOrder), "the card order is consumed once");
        assertNull(m.dq.leftover());
    }

    /** Seed of Hope's mill asks "order to Graveyard" for the milled pair
     * (Island, Forest), but Island returns to the hand within the step, so
     * the pin forge-export derives from the checkpoints names only [Forest,
     * Seed of Hope]. The named cards are ordered by the pin and the transit
     * card is kept in Forge's offered order after them, with a note, instead
     * of missing an order no gorge answer can exist for: Island's graveyard
     * position is gone before the next checkpoint. */
    @Test
    public void pinnedGraveyardOrderKeepsATransitOfferedCard() {
        StepMachine m = machine(
                "[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"graveyard_order\","
                        + "\"picks\":[\"Forest\",\"Seed of Hope\"]}]",
                ",\"graveyard_ends\":{\"0:0\":[\"Forest\",\"Seed of Hope\"]}");
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Island", "Forest"}) {
            cards.add(card(m, name));
        }
        List<Card> got = m.orderCards(0, cards, "order to Graveyard");
        assertNotNull(got, "the transit ask is answered, not missed");
        assertEquals(got.stream().map(Card::getName).toList(),
                List.of("Forest", "Island"),
                "the named card takes the pin's position and the transit card follows in Forge's order");
        assertTrue(m.notes.toString().contains(
                "transit [Island] left before the checkpoint"), m.notes.toString());
        assertNull(m.take(0, DecisionQueue::isGraveyardOrder), "the pin is consumed once");
        assertNull(m.dq.leftover());
    }

    /** The transit tolerance cannot hide a real ordering difference: an
     * unnamed offered card whose name the END graveyard still contains is an
     * arrival the checkpoints see, so a pin that does not name it is still
     * the strict miss it was. */
    @Test
    public void pinnedGraveyardOrderStillMissesAnUnnamedCardTheCheckpointsSee() {
        StepMachine m = machine(
                "[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"graveyard_order\","
                        + "\"picks\":[\"Forest\",\"Seed of Hope\"]}]",
                ",\"graveyard_ends\":{\"0:0\":[\"Island\",\"Forest\",\"Seed of Hope\"]}");
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Island", "Forest"}) {
            cards.add(card(m, name));
        }
        assertNull(m.orderCards(0, cards, "order to Graveyard"),
                "Island is in the end graveyard, so the pin must name it");
        assertTrue(m.notes.toString().contains(
                "gorge order [Forest, Seed of Hope] does not name every card"), m.notes.toString());
    }

    /** An export that wrote no graveyard_ends cannot classify anything, so a
     * pinned ask with an unnamed offered card misses exactly as it did
     * before the field existed. */
    @Test
    public void pinnedGraveyardOrderWithoutEndNamesStillMisses() {
        StepMachine m = machine(
                "[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"gorge_kind\":\"graveyard_order\","
                        + "\"picks\":[\"Forest\",\"Seed of Hope\"]}]");
        m.build();
        List<Card> cards = new ArrayList<>();
        for (String name : new String[] {"Island", "Forest"}) {
            cards.add(card(m, name));
        }
        assertNull(m.orderCards(0, cards, "order to Graveyard"));
        assertTrue(m.notes.toString().contains("does not name every card"), m.notes.toString());
    }
}
