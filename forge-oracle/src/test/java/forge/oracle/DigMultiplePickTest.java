// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The xproof Forge decision_shape cluster xpt-cd204b22 (In the Presence of
 * Ages: SP$ DigMultiple | DigNum$ 4 | ChangeValid$ Creature,Land |
 * DestinationZone2$ Graveyard | Optional$ True). Forge asks
 * chooseCardsForEffectMultiple (one card per ChangeValid$ category); gorge
 * logs one choose_n[digmultiple] over the four dug cards. The driver missed
 * "cards (multiple)" unconditionally and left gorge's pick over. The fixture
 * is the forge-export request of the find: four Forests on top, gorge takes
 * one; it goes to hand and the other three to the graveyard.
 */
public class DigMultiplePickTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(DigMultiplePickTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("digmultiple").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("digmultiple").resolve(name);
    }

    private static List<String> names(JsonObject p, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : p.getAsJsonArray(key)) {
            out.add(e.getAsString());
        }
        return out;
    }

    @Test
    public void forestPickGoesToHand() throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture("in-the-presence-of-ages-forest.jsonl"), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        ResultRow row = new StepMachine(req, true).run();
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("pick step 5 cards (multiple)")), "notes: " + row.notes);
        JsonObject p0 = row.snapshots.get(6).getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals(names(p0, "hand"), List.of("Forest"), "the picked Forest went to hand");
        List<String> gy = names(p0, "graveyard");
        assertEquals(gy.stream().filter("Forest"::equals).count(), 3L, "the other three Forests went to the graveyard: " + gy);
        assertTrue(gy.contains("In the Presence of Ages"));
    }

    @Test
    public void onePerCategory() {
        forge.game.card.CardCollection lands = new forge.game.card.CardCollection();
        forge.game.card.CardCollection creatures = new forge.game.card.CardCollection();
        java.util.Map<String, forge.game.card.CardCollection> valid = new java.util.LinkedHashMap<>();
        valid.put("Creature", creatures);
        valid.put("Land", lands);
        forge.game.card.Card a = new forge.game.card.Card(1, null);
        forge.game.card.Card b = new forge.game.card.Card(2, null);
        lands.add(a);
        lands.add(b);
        assertTrue(ScriptedController.assignsOnePerCategory(valid, List.of(a)));
        assertFalse(ScriptedController.assignsOnePerCategory(valid, List.of(a, b)), "two lands fill one category twice");
        creatures.add(b);
        assertTrue(ScriptedController.assignsOnePerCategory(valid, List.of(a, b)), "a land creature takes the creature slot");
    }
}
