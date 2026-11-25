// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.zone.ZoneType;

/**
 * Four xproof Forge decision_shape clusters whose asks the scripted controller
 * did not map. Each fixture is one forge-export request with gorge's answers:
 *
 * <ul>
 * <li>Mabel, Bitter Recluse (CR 606.5): "remove up to three counters" is
 *     gorge's one choose_n per counter removed, but Forge asks
 *     chooseCounterType then the five-argument chooseNumber; the params
 *     overload fell through to PlayerControllerAi and the counter picks stayed
 *     unconsumed.</li>
 * <li>Blighted Blackthorn (CR 701.60a): blight 2 is a one-creature pick, but
 *     ScriptedCostDecision.visit(CostBlight) recorded a miss for a cost with
 *     nothing to choose instead of using the creature or forcing a lone
 *     one.</li>
 * <li>Clockwork Avian (and Terra, Magical Adept's Saga chapter): CountersPutEffect's
 *     UpTo$ ask carries CounterType too, but gorge logs one numeric choose_n
 *     (resume counter_upto) rather than a kind pick per counter. Routing it
 *     through the remove-counter branch answered 0 and stranded the number;
 *     only a RemoveCounter ask may take the per-counter picks.</li>
 * <li>Curator of Destinies / Cloak and Dagger, Entwined: TwoPilesEffect's
 *     Separator$/Chooser$ and ChangeZoneEffect's Chooser$ ask
 *     chooseSingleEntityForEffect even when the defined player list has one
 *     member; gorge logs nothing for a lone forced chooser, so taking the next
 *     object pick consumed the pile card / exiled card instead. The pile pick
 *     itself (gorge resume twopiles_pick) then had no consumer.</li>
 * </ul>
 */
public class UnmappedAskTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(UnmappedAskTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("unmapped").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("unmapped").resolve(name);
    }

    private static StepMachine replay(String name) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        return m;
    }

    @Test
    public void removeAnyTypeConsumesOneCounterPickPerCounter() throws Exception {
        StepMachine m = replay("counter-remove-any-type.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("counter step 3: removed 2 Loyalty")),
                "the two per-counter picks answer Forge's amount ask, notes: " + m.notes);
        Card ajani = (Card) m.entity("p1:Ajani Resolute");
        assertEquals(ajani.getCounters(CounterEnumType.LOYALTY), 0,
                "both loyalty counters are removed, not just the first");
    }

    /** Mabel on Ajani Resolute with two loyalty and one +1/+1 counter: gorge
     * removes LOYALTY, LOYALTY, P1P1 (CR 122.1) and Ajani dies at 0 loyalty
     * (CR 704.5i). With two kinds offered, chooseCounterType took the first
     * LOYALTY pick, so the amount ask counted one loyalty and Ajani lived. */
    @Test
    public void removeAnyTypeMixedKindsLeavesTheKindPickForTheAmount() throws Exception {
        StepMachine m = replay("counter-remove-any-mixed-kinds.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("counter step 3: removed 2 Loyalty")),
                "the kind pick is counted in the loyalty run, notes: " + m.notes);
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("counter step 3: removed 1 +1/+1")),
                "the +1/+1 pick is removed too, notes: " + m.notes);
        Card ajani = (Card) m.entity("p1:Ajani Resolute");
        assertTrue(ajani.isInZone(ZoneType.Graveyard),
                "Ajani Resolute at 0 loyalty goes to the graveyard, zone: " + ajani.getZone());
    }

    /** Mabel on Ajani Resolute stopped early: gorge removes one LOYALTY then
     * logs a remove_counter_done "Done". removeAnyType offers no Done type, so
     * the kind ask consumes it and answers a legal type whose amount ask finds
     * no pick and answers 0. Ajani keeps one loyalty and its +1/+1. */
    @Test
    public void removeAnyTypeDoneStopsTheUpToRemoval() throws Exception {
        StepMachine m = replay("counter-remove-any-done.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("counter step 3: removed 1 Loyalty")),
                "the LOYALTY pick is removed, notes: " + m.notes);
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("counter kind step 3: Done")),
                "the Done pick is consumed at the kind ask, notes: " + m.notes);
        Card ajani = (Card) m.entity("p1:Ajani Resolute");
        assertTrue(ajani.isInZone(ZoneType.Battlefield),
                "Ajani Resolute survives an early stop, zone: " + ajani.getZone());
        assertEquals(ajani.getCounters(CounterEnumType.LOYALTY), 1, "one loyalty is left");
        assertEquals(ajani.getCounters(CounterEnumType.P1P1), 1, "the +1/+1 is untouched");
    }

    /** Ferropede's combat-damage trigger removes "a counter" of any type from
     * Wall of Stone, which has one +1/+1 counter: with one kind and the card's
     * fixed CounterNum$ 1 the removal is forced, so gorge logs no counter-kind
     * pick (CR 122.1). Forge's removeAnyType asks chooseCounterType (the lone
     * option is forced) then chooseNumber(1, 1); a forced amount must be
     * answered without a pick, not strict-missed. */
    @Test
    public void removeAnyTypeForcedAmountRemovesTheLoneCounter() throws Exception {
        StepMachine m = replay("counter-remove-forced-amount.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.contains("forced: no counter pick")),
                "a forced amount is answered without a counter pick, notes: " + m.notes);
        Card wall = (Card) m.entity("p1:Wall of Stone");
        assertEquals(wall.getCounters(CounterEnumType.P1P1), 0,
                "the lone +1/+1 counter is removed");
    }

    @Test
    public void blightCostPicksTheLoneCreature() throws Exception {
        StepMachine m = replay("blight-cost.jsonl");
        Card blackthorn = (Card) m.entity("p0:Blighted Blackthorn");
        assertEquals(blackthorn.getCounters(CounterEnumType.M1M1), 2,
                "blight 2 puts two -1/-1 counters on the lone creature");
        assertTrue(m.notes.stream().noneMatch(n -> n.contains("loose: AI answered cost CostBlight")),
                "the cost is answered, not missed, notes: " + m.notes);
    }

    @Test
    public void loneChooserDoesNotConsumeThePilePick() throws Exception {
        StepMachine m = replay("chooser-pile.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("entity (Chooser:): lone forced option")),
                "the separator/chooser with one member is forced without taking a pick, notes: " + m.notes);
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("pile step 10: First pile [First pile]")),
                "gorge's twopiles_pick answers chooseCardsPile, notes: " + m.notes);
    }

    @Test
    public void putCounterUpToConsumesTheRecordedNumber() throws Exception {
        StepMachine m = replay("counter-put-upto.jsonl");
        // The counter total cannot distinguish the answer here: Clockwork
        // Avian enters with four +1/+0 counters, so this probe's board has
        // more than MaxFromEffect$ 4 and the put clamps either way. The
        // recorded number is what must be consumed: the old params branch
        // answered 0 and left the counter_upto decision stranded.
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("number step 2: 1")),
                "the counter_upto number answers the put amount, notes: " + m.notes);
    }

    @Test
    public void loneChooserDoesNotConsumeTheChangeZonePick() throws Exception {
        StepMachine m = replay("chooser-changezone.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.equals("entity (Chooser:): lone forced option")),
                "the chooser with one member is forced, notes: " + m.notes);
        Card shield = (Card) m.entity("p1:Shield Sphere");
        assertTrue(shield.isInZone(ZoneType.Exile),
                "the recorded search pick exiles Shield Sphere, not the chooser's own card");
    }

    /** Replays through ForgeEngine, as production does, so the fixture's
     * die_rolls are forced (3 and 6) instead of the seeded generator's. */
    private static ResultRow rawReplay(String name) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        java.util.Random previous = forge.util.MyRandom.getRandom();
        try {
            return new ForgeEngine().replayOnce(req, true);
        } finally {
            forge.util.MyRandom.setRandom(previous);
        }
    }

    private static int knightTokens(ResultRow row) {
        JsonObject last = row.snapshots.get(row.snapshots.size() - 1);
        int n = 0;
        for (JsonElement e : last.getAsJsonArray("permanents")) {
            if (e.getAsJsonObject().get("name").getAsString().equals("Knight Token")) {
                n++;
            }
        }
        return n;
    }

    /** Valiant Endeavor (CR 706.2): roll two d6 (forced 3 and 6), choose one
     * result as X (destroy each creature with power X or greater), the other
     * is Y (Y 2/2 Knight tokens). RollDiceEffect's ChosenSVar$ asks the
     * values-list chooseNumber, which missed unconditionally, so gorge's
     * choose_n[number] pick 3 stayed unconsumed (xproof clusters xpt-8a6bce20,
     * xpt-c9c0e640). Pick 3 makes Y 6: six Knights. */
    @Test
    public void chosenDieResultAnswersTheValuesListNumber() throws Exception {
        ResultRow row = rawReplay("valiant-endeavor-chosen-die.jsonl");
        assertTrue(row.strict, "the replay must be strict, strict_miss: " + row.strictMiss);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.contains("number step 8: 3 of [3, 6]"), "gorge's pick answers the ask, notes: " + row.notes);
        assertEquals(knightTokens(row), 6, "X=3, so Y=6 Knight tokens");
    }

    /** The same rolls with gorge choosing 6: X=6, Y=3, three Knights. */
    @Test
    public void chosenDieResultSixMakesThreeKnights() throws Exception {
        ResultRow row = rawReplay("valiant-endeavor-chosen-die-six.jsonl");
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertEquals(knightTokens(row), 3, "X=6, so Y=3 Knight tokens");
    }

    /** A pick that is not a rolled result (4 over [3, 6]) is not taken: it
     * stays a strict miss. */
    @Test
    public void unofferedDieResultStaysAStrictMiss() throws Exception {
        ResultRow row = rawReplay("valiant-endeavor-chosen-die-unoffered.jsonl");
        assertTrue(row.strict, "the replay must be strict");
        String miss = row.strictMiss != null ? row.strictMiss : row.harness;
        assertNotNull(miss, "an unoffered number must strict-miss");
        assertTrue(miss.contains("number"), "want the number miss, got: " + miss);
    }
}
