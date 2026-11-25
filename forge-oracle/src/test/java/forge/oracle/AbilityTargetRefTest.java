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
import java.util.List;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * xproof clusters xpt-1d873918 (Forge) and xpt-7271607a (XMage): "harness:
 * step 6 (activate) p0:Azorius Guildmage was not put on the stack". p1
 * activates Dark Sphere ({T}, Sacrifice: a non-mana ability, so it uses the
 * stack, CR 113.3b and 602.2); p0 answers with Azorius Guildmage's "{2}{U}:
 * Counter target activated ability" aimed at it, gorge's ref
 * "p1:ability:Dark Sphere". The driver resolved a target ref only to a player
 * or a card, so the stack ability never bound to the Counter slot
 * (TargetType$ Activated) and the activation was refused. A "pN:ability:Name"
 * ref is now the k-th pending ability on the stack, in creation order,
 * activated or triggered by seat N, whose source is the named card.
 */
public class AbilityTargetRefTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(AbilityTargetRefTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("abilitytarget").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("abilitytarget").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        return new StepMachine(req, true).run();
    }

    private static JsonObject snapshot(ResultRow row, String checkpoint) {
        for (JsonObject s : row.snapshots) {
            if (checkpoint.equals(s.get("checkpoint").getAsString())) {
                return s;
            }
        }
        throw new AssertionError("no snapshot " + checkpoint);
    }

    @Test
    public void guildmageCountersDarkSpheresActivatedAbility() throws Exception {
        ResultRow row = replay("azorius-guildmage-dark-sphere.jsonl");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        JsonObject activated = snapshot(row, "step 6 (activate)");
        JsonArray stack = activated.getAsJsonArray("stack");
        assertEquals(stack.size(), 2, "Guildmage's ability on top of Dark Sphere's: " + stack);
        assertEquals(stack.get(0).getAsJsonObject().get("source").getAsString(), "Azorius Guildmage");
        assertEquals(stack.get(1).getAsJsonObject().get("source").getAsString(), "Dark Sphere");
        // Both players pass: the counter resolves, and Dark Sphere's ability is gone with it.
        assertEquals(snapshot(row, "step 8 (pass)").getAsJsonArray("stack").size(), 0,
                "Dark Sphere's ability was countered");
    }

    @Test
    public void abilityRefGrammar() {
        RefTable.Ref r = RefTable.parse("p1:ability:Dark Sphere");
        assertTrue(r.ability);
        assertEquals(r.seat, 1);
        assertEquals(r.name, "Dark Sphere");
        assertEquals(r.nth, 1);
        RefTable.Ref k = RefTable.parse("p0:ability:Elvish Visionary#2");
        assertTrue(k.ability);
        assertEquals(k.name, "Elvish Visionary");
        assertEquals(k.nth, 2);
        // A name with its own colon keeps it.
        assertEquals(RefTable.parse("p0:ability:Summon: Bahamut").name, "Summon: Bahamut");
        assertTrue(RefTable.isAbilityRef("p0:ability:X"));
        assertTrue(!RefTable.isAbilityRef("p0:X"));
        assertTrue(!RefTable.isAbilityRef("p0:token:X"));
        assertTrue(!RefTable.isAbilityRef("p0"));
        assertTrue(!RefTable.isAbilityRef("ability:X"));
    }

    @Test
    public void nthPendingCountsOldestFirst() {
        // The stack lists top first; gorge ranks pending abilities oldest first.
        List<String> topFirst = List.of("c-new", "b-new", "a-mid", "b-old");
        assertEquals(RefTable.nthPending(topFirst, s -> s.startsWith("b"), 1), "b-old");
        assertEquals(RefTable.nthPending(topFirst, s -> s.startsWith("b"), 2), "b-new");
        assertNull(RefTable.nthPending(topFirst, s -> s.startsWith("b"), 3));
        assertNotNull(RefTable.nthPending(topFirst, s -> s.startsWith("c"), 1));
    }
}
