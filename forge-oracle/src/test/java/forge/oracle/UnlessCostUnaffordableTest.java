// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * xproof decision_shape cluster xpt-25d9fc42: Nimble Hobbit attacks with no
 * mana and no Food. Its GenericChoice offers PayW and PaySacFood, each an
 * ImmediateTrigger whose UnlessCost$ is unpayable, so gorge asks nothing and
 * logs no unless_pay decision (CR 118.3: a cost cannot be paid without the
 * resources to pay it in full). Forge's ChooseGenericEffect filters with
 * Cost.canPay, which does not check mana, so PayW survives and
 * AbilityUtils.handleUnlessCost calls payCostToPreventEffect; the driver used
 * to strict-miss "Missing unless-pay answer". Declining an unaffordable unless
 * cost is its only legal answer, so the driver now declines without a miss.
 * Affordability is the boundary: with three Plains the cost is payable and an
 * unanswered ask stays a real decision-shape miss.
 */
public class UnlessCostUnaffordableTest {
    private static final String NIMBLE_REQUEST = """
            {"id":"xs:XP:nimble-hobbit:f0t0:s1","scenario_sha":"0f5fa484a5febc0f52d17d60c421306c25d23b9e3c1b1c4d445d9300859976b0","item":{"id":"xs:XP:nimble-hobbit:f0t0:s1","card":"Nimble Hobbit","template":"xproof-start","name":"xproof start Nimble Hobbit f0/t0 seed 1","turn":1,"cr":[],"why":"A gorge game from a scripted start, played on by explore bots and a focus seat, replayed from turn 1's first main phase.","setup":{"p0":{"battlefield":["Nimble Hobbit"],"library_top":["Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains"],"life":20},"p1":{"battlefield":["Shield Sphere"],"library_top":["Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains","Plains"],"life":20}},"steps":[{"op":"pass","seat":0},{"op":"pass","seat":1},{"op":"pass","seat":0},{"op":"pass_to","seat":0,"decision":"attackers"},{"op":"attack","seat":0,"attackers":["p0:Nimble Hobbit"],"defender":"p1"},{"op":"pass","seat":0},{"op":"pass","seat":1},{"op":"pass","seat":0},{"op":"pass","seat":1},{"op":"pass_to","seat":0,"decision":"priority"},{"op":"pass","seat":0},{"op":"pass","seat":1},{"op":"pass","seat":0},{"op":"pass","seat":1},{"op":"pass","seat":0},{"op":"pass","seat":1},{"op":"pass","seat":0},{"op":"pass","seat":1},{"op":"pass","seat":0},{"op":"pass","seat":1}]},"gorge_decisions":[],"abilities":{}}
            """;

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static ResultRow replay(String request) {
        return new ScenarioReplay(new ForgeEngine(), "").replay(Request.parse(request));
    }

    private static JsonObject permanent(JsonObject snap, String name) {
        for (JsonElement e : snap.getAsJsonArray("permanents")) {
            JsonObject c = e.getAsJsonObject();
            if (c.get("name").getAsString().equals(name)) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void unaffordableUnlessCostIsDeclinedWithoutAMiss() {
        ResultRow r = replay(NIMBLE_REQUEST);
        assertNull(r.harness, "harness: " + r.harness);
        assertNull(r.strictMiss, "strict miss: " + r.strictMiss);
        assertNull(r.leftover, "leftover: " + r.leftover);
        assertTrue(r.strict, "not a strict run");
        assertTrue(r.notes.stream().anyMatch(n -> n.startsWith("unless-pay step 6: unaffordable, declined")),
                "the unaffordable {2}{W} was not declined: " + r.notes);
        JsonObject last = r.snapshots.get(r.snapshots.size() - 1);
        JsonObject shield = permanent(last, "Shield Sphere");
        assertTrue(shield != null, "Shield Sphere is not on the battlefield: " + last);
        assertFalse(shield.get("tapped").getAsBoolean(), "the unpaid trigger tapped Shield Sphere");
        JsonObject hobbit = permanent(last, "Nimble Hobbit");
        assertTrue(hobbit != null && hobbit.get("tapped").getAsBoolean(), "Nimble Hobbit did not attack");
    }

    @Test
    public void affordableUnlessCostWithNoAnswerIsStillAMiss() {
        JsonObject raw = JsonParser.parseString(NIMBLE_REQUEST).getAsJsonObject();
        raw.getAsJsonObject("item").getAsJsonObject("setup").getAsJsonObject("p0")
                .add("battlefield", JsonParser.parseString("[\"Nimble Hobbit\",\"Plains\",\"Plains\",\"Plains\"]"));
        ResultRow r = replay(raw.toString());
        assertNull(r.harness, "harness: " + r.harness);
        assertFalse(r.strict, "an affordable unanswered unless cost was accepted as strict");
        assertTrue(r.strictMiss != null && r.strictMiss.contains("unless-pay"),
                "an affordable unanswered unless cost is still a decision-shape gap: " + r.strictMiss);
    }
}
