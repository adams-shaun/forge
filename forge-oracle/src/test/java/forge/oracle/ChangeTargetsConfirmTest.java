// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;

/**
 * xproof Forge decision_shape cluster "Missing yesno answer at step 9: confirm
 * null: Do you want to change targets of Reach Through Mists" (xpt-bf9a34cd).
 * p1 casts Reach Through Mists, a spell with no targets; p0 casts Redirect
 * (Optional$ ChangeTargets) targeting it. A spell with no targets has nothing
 * to choose anew (CR 115.7), so gorge's effChangeTargets skips the declaration
 * and logs no decision, while Forge's ChangeTargetsEffect still asks the
 * optional "Do you want to change targets?" confirm (its own TODO). The driver
 * answers no when no yes/no is queued and no subject spell (or subability)
 * holds a target; a targeted subject still consumes the queued answer, and
 * keeps the strict miss when gorge logged none.
 *
 * A second cluster, "Missing yesno answer at step 3: confirm null: Do you want
 * to change targets of Ranger's Guile" (xpt-c5e9f646): p0 casts Ranger's Guile
 * on Speedball, New Warrior, whose SpellCast trigger resolves "You may choose
 * new targets for that spell" through ChangeTargets (Defined$
 * TriggeredSpellAbility, Optional$ True). gorge logs its retarget ask as a
 * choose_n whose only option is Speedball itself and whose pick is blank
 * (keep the target); the confirm in front of it must consume that pick.
 */
public class ChangeTargetsConfirmTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ChangeTargetsConfirmTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("changetargets").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("changetargets").resolve(name);
    }

    private static JsonObject fixtureJson(String name) throws Exception {
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            return JsonParser.parseString(in.readLine()).getAsJsonObject();
        }
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        return m.run();
    }

    private static JsonArray single(String s) {
        JsonArray a = new JsonArray();
        a.add(s);
        return a;
    }

    /** A game with one scripted yes/no answer at step 0, before any step runs. */
    private static StepMachine machineWithYesNo(String fixture, String answer) throws Exception {
        JsonObject raw = fixtureJson(fixture);
        JsonObject d = new JsonObject();
        d.addProperty("step", 0);
        d.addProperty("seat", 0);
        d.addProperty("kind", "yesno");
        d.add("picks", single(answer));
        d.add("pick_kinds", single(answer));
        d.addProperty("min", 0);
        d.addProperty("max", 1);
        JsonArray decisions = new JsonArray();
        decisions.add(d);
        raw.add("gorge_decisions", decisions);
        StepMachine m = new StepMachine(Request.parse(raw.toString()), true);
        m.build();
        SetupBuilder.place(m);
        m.stepIdx = 0;
        return m;
    }

    private static SpellAbility changeTargets(StepMachine m, boolean subjectHasTarget) {
        Card redirect = m.card("p0:Redirect");
        Card reach = m.card("p1:Reach Through Mists");
        SpellAbility change = new SpellAbility.EmptySa(ApiType.ChangeTargets, redirect, m.seats[0]);
        change.putParam("Optional", "True");
        change.setTargets(new TargetChoices());
        SpellAbility subject = new SpellAbility.EmptySa(ApiType.Draw, reach, m.seats[1]);
        subject.setTargets(new TargetChoices());
        if (subjectHasTarget) {
            subject.getTargets().add(m.seats[0]);
        }
        change.getTargets().add(subject);
        return change;
    }

    private static int life(ResultRow row, int seat) {
        JsonObject last = row.snapshots.get(row.snapshots.size() - 1);
        for (JsonElement e : last.getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("seat").getAsInt() == seat) {
                return p.get("life").getAsInt();
            }
        }
        return -1;
    }

    private static StrictMiss expectStrictMiss(ScriptedController c, SpellAbility sa, String message) {
        try {
            c.confirmAction(sa, null, message, null, sa.getTargets().getTargetSpells().iterator().next().getHostCard(), null);
        } catch (StrictMiss expected) {
            return expected;
        }
        fail("the confirm must stay a strict miss, not a vacuous decline");
        return null;
    }

    @Test
    public void targetlessSpellDeclinesTheConfirm() throws Exception {
        ResultRow row = replay("redirect-targetless.jsonl");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("empty change targets confirm step ")
                        && n.contains("Reach Through Mists")),
                "the empty ChangeTargets confirm is declined, notes: " + row.notes);
    }

    @Test
    public void targetedSpellConsumesTheRetargetDecision() throws Exception {
        ResultRow row = replay("redirect-targeted.jsonl");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("change targets confirm step 9: no")
                        && n.contains("Shock")),
                "the targeted subject's retarget decline is consumed, notes: " + row.notes);
        assertEquals(life(row, 0), 18, "Shock kept its original target, so it still dealt 2 to p0");
    }

    @Test
    public void targetedSpellAppliesTheRetargetPick() throws Exception {
        ResultRow row = replay("redirect-targeted-change.jsonl");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("change targets confirm step 9: yes")
                        && n.contains("p1")),
                "the targeted subject's retarget pick answers the confirm yes, notes: " + row.notes);
        assertTrue(row.notes.contains("retarget step 9: [p1]"), "the pick is applied, notes: " + row.notes);
        assertEquals(life(row, 0), 20, "Shock left its old target p0");
        assertEquals(life(row, 1), 18, "Shock dealt 2 to the new target p1");
    }

    @Test
    public void targetedSpellConsumesTheQueuedYesNo() throws Exception {
        StepMachine m = machineWithYesNo("redirect-targetless.jsonl", "no");
        ScriptedController c = (ScriptedController) m.seats[0].getController();
        SpellAbility change = changeTargets(m, true);

        assertFalse(c.confirmAction(change, null, "Do you want to change targets of Reach Through Mists?",
                        null, m.card("p1:Reach Through Mists"), null),
                "the queued no declines the change");
        assertNull(m.dq.peek(0, DecisionQueue::isYesNo),
                "the targeted subject took the queued yes/no instead of the empty-subject fallback");
    }

    @Test
    public void targetedSpellWithoutAnAnswerStaysStrictMiss() throws Exception {
        StepMachine m = machineWithYesNo("redirect-targetless.jsonl", "no");
        ScriptedController c = (ScriptedController) m.seats[0].getController();
        SpellAbility change = changeTargets(m, true);

        // Spend the queued answer on a different ask so this confirm has none.
        assertNotNull(m.dq.take(0, DecisionQueue::isYesNo), "fixture must queue a yes/no answer");
        StrictMiss miss = expectStrictMiss(c, change, "Do you want to change targets of Reach Through Mists?");
        assertTrue(miss.getMessage().contains("Missing yesno answer at step 0: confirm null"), miss.getMessage());
    }

    @Test
    public void speedballTriggerChangeTargetsConfirmConsumesTheRetargetPick() throws Exception {
        ResultRow row = replay("speedball-ranger-guile.jsonl");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("change targets confirm step 3:")),
                "the trigger's optional ChangeTargets confirm is answered from gorge's retarget pick, notes: " + row.notes);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("retarget step 3:")),
                "the retarget pick is consumed at chooseNewTargetsFor, notes: " + row.notes);
    }
}
