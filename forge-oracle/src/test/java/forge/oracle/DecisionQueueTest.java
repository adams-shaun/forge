// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.oracle.DecisionQueue.Decision;

/** gorge's decision log as the answer source, with shapes taken from real
 * forge-export rows (P1-4). */
public class DecisionQueueTest {
    private static DecisionQueue q(String... json) {
        List<JsonObject> raw = new ArrayList<>();
        for (String j : json) {
            raw.add(JsonParser.parseString(j).getAsJsonObject());
        }
        return new DecisionQueue(raw);
    }

    static final String TARGET = "{\"step\":0,\"seat\":0,\"kind\":\"target\",\"options\":3,\"picks\":[\"Grizzly Bears (b)\"],"
            + "\"pick_idx\":[2],\"pick_refs\":[\"p1:Grizzly Bears\"],\"object_picks\":[\"p1:Grizzly Bears\"],"
            + "\"pick_kinds\":[\"permanent\"],\"via\":\"target\",\"gorge_kind\":\"target\",\"min\":1,\"max\":1}";
    static final String PLAYER = "{\"step\":0,\"seat\":0,\"kind\":\"target\",\"picks\":[\"b\"],\"pick_refs\":[\"p1\"],"
            + "\"pick_kinds\":[\"player\"],\"gorge_kind\":\"target\",\"min\":1,\"max\":1}";
    static final String X = "{\"step\":0,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"X = 2\"],\"pick_refs\":[\"X = 2\"],"
            + "\"pick_kinds\":[\"x\"],\"gorge_kind\":\"choose\",\"min\":1,\"max\":1}";
    static final String TRIGGER_YES = "{\"step\":1,\"seat\":0,\"kind\":\"yesno\",\"picks\":[\"Yes — Bushy Bodyguard: When this creature enters, you may forage.\"],"
            + "\"pick_refs\":[\"p0:Bushy Bodyguard\"],\"object_picks\":[\"p0:Bushy Bodyguard\"],\"pick_kinds\":[\"yes\"],\"gorge_kind\":\"trigger_optional\"}";
    static final String DECLINE = "{\"step\":1,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Do not pay\"],\"pick_refs\":[\"p0:Bushy Bodyguard\"],"
            + "\"object_picks\":[\"p0:Bushy Bodyguard\"],\"pick_kinds\":[\"trigger_cost_decline\"],\"gorge_kind\":\"choose\"}";
    static final String SEARCH = "{\"step\":1,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Forest\"],\"pick_refs\":[\"p0:Forest\"],"
            + "\"object_picks\":[\"p0:Forest\"],\"pick_kinds\":[\"search\"],\"gorge_kind\":\"choose\",\"min\":0,\"max\":3}";
    static final String MODE = "{\"step\":0,\"seat\":0,\"kind\":\"mode\",\"picks\":[\"CARDNAME deals 4 damage to target creature.\"],"
            + "\"pick_kinds\":[\"mode\"],\"gorge_kind\":\"modes\"}";
    static final String GIFT = "{\"step\":0,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Don't promise a gift\"],"
            + "\"pick_refs\":[\"Don't promise a gift\"],\"pick_kinds\":[\"gift_decline\"],\"gorge_kind\":\"choose\"}";
    static final String PAY_G = "{\"step\":0,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Pay G\"],\"pick_refs\":[\"p0:Head of the Homestead\"],"
            + "\"object_picks\":[\"p0:Head of the Homestead\"],\"pick_kinds\":[\"pay_G\"],\"gorge_kind\":\"choose\"}";

    @Test
    public void takesInLogOrderWithinAStep() {
        DecisionQueue d = q(X, TARGET, TRIGGER_YES);
        assertNull(d.take(1, DecisionQueue::isTarget), "a step-0 target is not a step-1 answer");
        Decision t = d.take(0, DecisionQueue::isTarget);
        assertEquals(t.refs(), List.of("p1:Grizzly Bears"));
        assertNull(d.take(0, DecisionQueue::isTarget), "consumed");
        assertEquals(d.unconsumed().size(), 2);
        assertTrue(d.leftover().startsWith("unconsumed gorge decision(s): step 0 p0 choose_n[x]"), d.leftover());
        assertTrue(d.take(0, DecisionQueue::isX) != null);
        assertTrue(d.take(1, DecisionQueue::isYesNo) != null);
        assertNull(d.leftover());
    }

    @Test
    public void classifiesTheShapes() {
        Decision x = q(X).all().get(0);
        assertTrue(DecisionQueue.isX(x));
        assertEquals(DecisionQueue.xValue(x), Integer.valueOf(2));
        assertFalse(DecisionQueue.isObjectChoice(x));

        Decision player = q(PLAYER).all().get(0);
        assertTrue(DecisionQueue.isTarget(player));
        assertEquals(player.refs(), List.of("p1"), "a player target's pick_refs");

        Decision yes = q(TRIGGER_YES).all().get(0);
        assertTrue(DecisionQueue.isYesNo(yes));
        assertTrue(DecisionQueue.yes(yes), "an em-dash Yes label");
        assertFalse(DecisionQueue.isObjectChoice(yes));

        Decision decline = q(DECLINE).all().get(0);
        assertFalse(DecisionQueue.isObjectChoice(decline), "a payment answer names its source, it picks nothing");
        assertEquals(DecisionQueue.payOf(decline), Boolean.FALSE);
        assertTrue(DecisionQueue.isYesNo(decline));
        assertFalse(DecisionQueue.yes(decline));

        Decision search = q(SEARCH).all().get(0);
        assertTrue(DecisionQueue.isObjectChoice(search));
        assertEquals(search.refs(), List.of("p0:Forest"));

        Decision mode = q(MODE).all().get(0);
        assertTrue(DecisionQueue.isMode(mode));
        assertFalse(DecisionQueue.isObjectChoice(mode));

        assertFalse(DecisionQueue.isObjectChoice(q(GIFT).all().get(0)));
        assertFalse(DecisionQueue.isObjectChoice(q(PAY_G).all().get(0)), "gorge's hybrid colour choice is no object pick");
    }

    @Test
    public void yesNoLabels() {
        assertEquals(DecisionQueue.yesNoOf("Yes", ""), Boolean.TRUE);
        assertEquals(DecisionQueue.yesNoOf("Yes — shuffle", ""), Boolean.TRUE);
        assertEquals(DecisionQueue.yesNoOf("No", ""), Boolean.FALSE);
        assertEquals(DecisionQueue.yesNoOf("Nothing", ""), null, "a word starting with No is not a no");
        assertEquals(DecisionQueue.yesNoOf("Anything", "opening_no"), Boolean.FALSE);
        assertEquals(DecisionQueue.yesNoOf("Grizzly Bears", ""), null);
    }

    @Test
    public void seatAndStepAreBothKeys() {
        DecisionQueue d = q(TARGET.replace("\"seat\":0", "\"seat\":1"));
        assertNull(d.take(0, x -> x.seat == 0 && DecisionQueue.isTarget(x)));
        assertTrue(d.take(0, x -> x.seat == 1 && DecisionQueue.isTarget(x)) != null);
    }
    static final String CHARM = "{\"step\":0,\"seat\":0,\"kind\":\"target\",\"picks\":[\"Amrou Scout (a)\",\"b\"],"
            + "\"pick_refs\":[\"p0:Amrou Scout\",\"p1\"],\"object_picks\":[\"p0:Amrou Scout\"],"
            + "\"pick_kinds\":[\"permanent\",\"player\"],\"resume\":\"charm_targets\",\"gorge_kind\":\"target\",\"min\":2,\"max\":2}";
    static final String ELVES = "{\"step\":0,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Activate Llanowar Elves for mana\"],"
            + "\"pick_refs\":[\"p0:Llanowar Elves\"],\"object_picks\":[\"p0:Llanowar Elves\"],\"pick_kinds\":[\"activate\"],\"gorge_kind\":\"choose\"}";

    @Test
    public void charmTargetRefsKeepThePlayer() {
        // object_picks drops the player; the refs a mixed charm ask binds must not.
        Decision d = q(CHARM).all().get(0);
        assertEquals(d.refs(), List.of("p0:Amrou Scout", "p1"));
        assertEquals(d.resume, "charm_targets");
    }

    @Test
    public void paymentWindowAsksAreNeverLeftover() {
        DecisionQueue d = q(PAY_G, ELVES, X);
        assertTrue(DecisionQueue.isPaymentWindow(d.all().get(0)));
        assertTrue(DecisionQueue.isPaymentWindow(d.all().get(1)));
        assertFalse(DecisionQueue.isPaymentWindow(d.all().get(2)));
        assertEquals(d.unconsumed().size(), 1);
        assertTrue(d.leftover().contains("choose_n[x]"), d.leftover());
    }

    /** A "Continue" after a look is gorge's acknowledgment, which Forge never
     * asks: it is not a yes/no answer and is never left over, so the play
     * choice that follows it at the same step is the one a yes/no takes. */
    @Test
    public void lookAckIsNotTakenForTheAskAfterIt() {
        DecisionQueue d = q(
                "{\"step\":15,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Continue\"],\"pick_kinds\":[\"yes\"],"
                        + "\"resume\":\"look_ack\",\"gorge_kind\":\"choose\",\"min\":1,\"max\":1}",
                "{\"step\":15,\"seat\":0,\"kind\":\"mode\",\"picks\":[],\"resume\":\"play\",\"gorge_kind\":\"modes\",\"min\":0,\"max\":1}");
        Decision ack = d.all().get(0);
        assertFalse(DecisionQueue.isYesNo(ack));
        assertTrue(DecisionQueue.isPaymentWindow(ack));
        Decision taken = d.take(15, DecisionQueue::isYesNo);
        assertEquals(taken.kind, "mode");
        assertFalse(DecisionQueue.yes(taken), "an empty answer to a play choice declines it");
        assertNull(d.leftover());
    }

    static final String ENDURE_COUNTERS = "{\"step\":10,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Put +1/+1 counters on it\"],"
            + "\"pick_refs\":[\"p0:Dusyut Earthcarver\"],\"object_picks\":[\"p0:Dusyut Earthcarver\"],"
            + "\"pick_kinds\":[\"endure_counters\"],\"resume\":\"endure\",\"gorge_kind\":\"choose\",\"min\":1,\"max\":1}";
    static final String ENDURE_SPIRIT = ENDURE_COUNTERS.replace("Put +1/+1 counters on it", "Create a Spirit")
            .replace("endure_counters", "endure_spirit");
    static final String SEARCH_ZONES = "{\"step\":8,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Search the library\"],"
            + "\"pick_refs\":[\"Search the library\"],\"pick_kinds\":[\"zone\"],\"resume\":\"search_zones\","
            + "\"gorge_kind\":\"choose\",\"options\":2,\"min\":1,\"max\":2}";
    static final String SEARCH_ZONES_BOTH = SEARCH_ZONES.replace("[\"Search the library\"]",
            "[\"Search the library\",\"Search the graveyard\"]").replace("\"pick_refs\":[\"Search the library\"]",
            "\"pick_refs\":[\"Search the library\",\"Search the graveyard\"]").replace("\"pick_idx\":[0]",
            "\"pick_idx\":[0,1]");
    static final String DIG_BOTTOM = "{\"step\":7,\"seat\":0,\"kind\":\"order\",\"picks\":[\"Forest\",\"Forest\",\"Forest\",\"Forest\"],"
            + "\"pick_refs\":[\"p0:Forest#4\",\"p0:Forest#5\",\"p0:Forest#6\",\"p0:Forest#7\"],"
            + "\"pick_kinds\":[\"dig_bottom\",\"dig_bottom\",\"dig_bottom\",\"dig_bottom\"],\"resume\":\"dig_arrange\","
            + "\"gorge_kind\":\"arrange\",\"options\":4,\"min\":4,\"max\":4}";
    static final String GRAVEYARD_ORDER = "{\"step\":5,\"seat\":0,\"kind\":\"order\",\"picks\":[\"Grizzly Bears\",\"Forest\"],"
            + "\"pick_kinds\":[\"graveyard_order\",\"graveyard_order\"],\"gorge_kind\":\"graveyard_order\",\"min\":2,\"max\":2}";
    static final String TRIGGER_ORDER = "{\"step\":5,\"seat\":0,\"kind\":\"order\",\"picks\":[\"A: draw\",\"B: draw\"],"
            + "\"pick_kinds\":[\"trigger\",\"trigger\"],\"gorge_kind\":\"trigger_order\",\"min\":2,\"max\":2}";

    /** Endure is a two-way choice in gorge, but Forge asks it as a plain
     * confirmAction with no mode: both pick kinds are yes/no, and neither is
     * an object choice that merely names the permanent. */
    @Test
    public void endureChoiceKindsAreYesNo() {
        Decision counters = q(ENDURE_COUNTERS).all().get(0);
        assertTrue(DecisionQueue.isYesNo(counters));
        assertTrue(DecisionQueue.yes(counters));
        assertFalse(DecisionQueue.isObjectChoice(counters), "the counters pick names its source, it chooses no object");

        Decision spirit = q(ENDURE_SPIRIT).all().get(0);
        assertTrue(DecisionQueue.isYesNo(spirit));
        assertFalse(DecisionQueue.yes(spirit));
        assertFalse(DecisionQueue.isObjectChoice(spirit));
    }

    /** One search_zones election answers Forge's initial library confirm and
     * each per-zone confirm; the zone word comes from Forge's prompt. */
    @Test
    public void searchZonesElectionAnswersPerZoneConfirms() {
        Decision library = q(SEARCH_ZONES).all().get(0);
        assertTrue(DecisionQueue.isSearchZones(library));
        assertTrue(DecisionQueue.searchesZone(library, "library"));
        assertFalse(DecisionQueue.searchesZone(library, "graveyard"));
        assertEquals(DecisionQueue.zoneWord("Search your graveyard?"), "graveyard");
        assertEquals(DecisionQueue.zoneWord("Search your hand?"), "hand");
        assertEquals(DecisionQueue.zoneWord("Cancel Search"), "", "a message naming no zone");

        Decision both = q(SEARCH_ZONES_BOTH).all().get(0);
        assertTrue(DecisionQueue.searchesZone(both, "library"));
        assertTrue(DecisionQueue.searchesZone(both, "graveyard"));
    }

    /** A same-name library arrange (Growing Rites of Itlimoc's all-to-bottom
     * dig) is still an order decision to consume; the graveyard pin is its
     * own role, and a trigger order is not a card order. */
    @Test
    public void orderShapesAreClassifiedByRole() {
        Decision dig = q(DIG_BOTTOM).all().get(0);
        assertTrue(DecisionQueue.isCardOrder(dig));
        assertFalse(DecisionQueue.isGraveyardOrder(dig));

        Decision grave = q(GRAVEYARD_ORDER).all().get(0);
        assertTrue(DecisionQueue.isGraveyardOrder(grave));
        assertFalse(DecisionQueue.isCardOrder(grave), "the graveyard pin answers only the graveyard question");

        assertFalse(DecisionQueue.isCardOrder(q(TRIGGER_ORDER).all().get(0)));
    }

    /** The mana gorge spent at a step is the item's payment array entry. */
    @Test
    public void requestReadsTheRecordedPayment() {
        Request r = Request.parse("{\"id\":\"p\",\"item\":{\"id\":\"p\",\"payment\":[\"\",\"GG\"],\"steps\":[]}}");
        assertEquals(r.payment(0), "");
        assertEquals(r.payment(1), "GG");
        assertEquals(r.payment(2), "", "past the array");
        assertEquals(Request.parse("{\"id\":\"q\",\"item\":{\"id\":\"q\"}}").payment(0), "");
    }
}
