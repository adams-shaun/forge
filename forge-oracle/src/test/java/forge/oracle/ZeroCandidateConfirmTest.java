// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * An xproof Forge decision_shape cluster (Bruna, Light of Alabaster). Its
 * attack trigger ends in an Optional$ hidden-origin ChangeZone ("any number of
 * Aura cards that could enchant it from your graveyard and/or hand", ChangeNum$
 * CountAuras). With no such Aura card gorge poses nothing (a choice of zero
 * cards) and the driver answers Forge's "Search your hand and graveyard?" no;
 * with an Aura card in the hand gorge would have posed it, so the same
 * unanswered confirm stays a strict miss.
 *
 * <p>The zero-candidate branch must use the same candidate filter
 * ChangeZoneEffect uses, so the special ChangeType$ forms are covered too:
 * "EACH Creature & Land" (Michelangelo, Improviser) and "Triggered.sameName"
 * (Verdant Succession). It must also never swallow Forge's "Cancel Search"
 * prompt, which is a different ChangeZoneGeneral confirm and is a strict miss
 * when no search pick is in flight (including after a recorded empty pick,
 * which never arms the search carry).
 */
public class ZeroCandidateConfirmTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ZeroCandidateConfirmTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("zerocandidate").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("zerocandidate").resolve(name);
    }

    private static ResultRow replay(String name, StepMachine[] mach) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        mach[0] = m;
        return m.run();
    }

    /** The fixture's request object, so a test can adjust the setup or the
     * decision log before building the game. */
    private static JsonObject fixtureJson(String name) throws Exception {
        Bootstrap.init(TestRes.res());
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            return JsonParser.parseString(in.readLine()).getAsJsonObject();
        }
    }

    private static StepMachine placed(JsonObject raw) {
        StepMachine m = new StepMachine(Request.parse(raw.toString()), true);
        m.build();
        SetupBuilder.place(m);
        m.stepIdx = 0;
        return m;
    }

    private static ScriptedController controller(StepMachine m) {
        return (ScriptedController) m.seats[0].getController();
    }

    private static SpellAbility hiddenChangeZone(StepMachine m, String origin, String changeType) {
        Card host = m.seats[0].getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals("Bruna, Light of Alabaster"))
                .findFirst().orElseThrow();
        SpellAbility sa = new SpellAbility.EmptySa(ApiType.ChangeZone, host, m.seats[0]);
        sa.putParam("Optional", "True");
        sa.putParam("Origin", origin);
        sa.putParam("ChangeType", changeType);
        sa.putParam("ChangeNum", "1");
        return sa;
    }

    private static StrictMiss expectStrictMiss(ScriptedController c, SpellAbility sa, String message) {
        try {
            c.confirmAction(sa, PlayerActionConfirmMode.ChangeZoneGeneral, message, null, null, null);
        } catch (StrictMiss expected) {
            return expected;
        }
        fail("the confirm must stay a strict miss, not a zero-candidate decline");
        return null;
    }

    @Test
    public void emptyHandAndGraveyardAurasAnswersNo() throws Exception {
        StepMachine[] m = new StepMachine[1];
        ResultRow row = replay("bruna-empty-hand.jsonl", m);

        assertNull(row.harness, "replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("zero-candidate confirm step 10: no")),
                "the zero-candidate confirm is declined, notes: " + row.notes);
        assertEquals(m[0].card("p0:Cowed by Wisdom").getAttachedTo().getName(), "Bruna, Light of Alabaster",
                "gorge's yes attached the Aura on the battlefield to Bruna");
    }

    @Test
    public void auraInHandStaysStrictMiss() throws Exception {
        StepMachine[] m = new StepMachine[1];
        ResultRow row = replay("bruna-aura-in-hand.jsonl", m);

        String miss = row.strictMiss != null ? row.strictMiss : row.harness;
        assertNotNull(miss, "an Aura card in the hand keeps the confirm a strict miss");
        assertTrue(miss.contains("Missing yesno answer at step 10: confirm ChangeZoneGeneral"), miss);
        assertTrue(m[0].card("p0:Pacifism").isInZone(ZoneType.Hand), "the Aura card stayed in the hand");
    }

    /** Michelangelo, Improviser's "EACH Creature & Land": a creature card in
     * the hand is a real candidate even though Card.isValid cannot parse the
     * raw EACH string, so the confirm must remain a visible strict miss. */
    @Test
    public void eachChangeTypeWithCreatureInHandStaysStrictMiss() throws Exception {
        JsonObject raw = fixtureJson("bruna-empty-hand.jsonl");
        raw.getAsJsonObject("item").getAsJsonObject("setup").getAsJsonObject("p0")
                .getAsJsonArray("hand").add("Grizzly Bears");
        StepMachine m = placed(raw);
        ScriptedController c = controller(m);

        SpellAbility sa = hiddenChangeZone(m, "Hand", "EACH Creature & Land");
        StrictMiss miss = expectStrictMiss(c, sa, "Search your hand and graveyard?");
        assertTrue(miss.getMessage().contains("confirm ChangeZoneGeneral"), miss.getMessage());
    }

    /** A recorded empty pick arms no search carry (pickObjects never sets
     * carryStep for it), so Forge's following "Cancel Search" has no gorge
     * answer. It must stay a strict miss; answering no would make
     * ChangeZoneEffect loop and its AI could take a card the pick declined. */
    @Test
    public void cancelSearchAfterEmptyPickStaysStrictMiss() throws Exception {
        JsonObject raw = fixtureJson("bruna-empty-hand.jsonl");
        JsonArray decisions = new JsonArray();
        JsonObject empty = new JsonObject();
        empty.addProperty("step", 0);
        empty.addProperty("seat", 0);
        empty.addProperty("kind", "choose_n");
        empty.add("picks", new JsonArray());
        empty.add("pick_kinds", new JsonArray());
        empty.addProperty("min", 0);
        empty.addProperty("max", 1);
        decisions.add(empty);
        raw.add("gorge_decisions", decisions);
        StepMachine m = placed(raw);
        ScriptedController c = controller(m);

        assertEquals(m.pickObjects(0, List.<Card>of(), 0, 1, "search").size(), 0,
                "the recorded empty pick consumes as zero cards");
        assertNull(m.searchWantsMore(0), "an empty pick leaves no search carry");

        SpellAbility sa = hiddenChangeZone(m, "Graveyard", "Triggered.sameName");
        sa.setTriggeringObject(AbilityKey.Card, m.seats[0].getCardsIn(ZoneType.Graveyard).get(0));
        StrictMiss miss = expectStrictMiss(c, sa, "Cancel Search — up to 1 card?");
        assertTrue(miss.getMessage().contains("Missing yesno answer at step 0: confirm ChangeZoneGeneral"),
                miss.getMessage());
    }
}
