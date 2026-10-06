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
}
