// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

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
}
