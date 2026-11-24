// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
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

/**
 * Katara, Water Tribe's Hope: "Waterbend {X}: Creatures you control have base
 * power and toughness X/X until end of turn." With every land tapped, Katara
 * alone untapped and X = 1, gorge's step-8 decisions are one choose_n with
 * pick kind "waterbend_generic" (pick_refs ["p0:Katara, ..."]) and the X
 * announcement. CR 701.67a: for each generic mana in a waterbend cost the
 * player may tap an untapped artifact or creature they control instead, and
 * CR 302.6 does not apply (it is not a {T} cost of Katara's own ability), so
 * a summoning-sick Katara may tap for her own waterbend.
 *
 * <p>Forge asks for those taps through chooseCardsForConvokeOrImprovise, but
 * the scripted controller only consumed pick kinds starting with "convoke" or
 * "improvise": the waterbend_generic pick stayed unconsumed, Forge paid X from
 * an empty pool and refused the activation ("step 13 (activate) ... was not
 * put on the stack", leftover "unconsumed gorge decision(s) ... waterbend").
 */
public class WaterbendTapPickTest {

    private static final String FIXTURE = "katara-x1-w8.jsonl";

    private static Path fixture() {
        try {
            Path p = Path.of(WaterbendTapPickTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("waterbend").resolve(FIXTURE);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("waterbend").resolve(FIXTURE);
    }

    @Test
    public void convokeLikeKindsIncludeWaterbend() {
        assertTrue(ScriptedController.isConvokeLikeKind("waterbend_generic"));
        assertTrue(ScriptedController.isConvokeLikeKind("convoke_generic"));
        assertTrue(ScriptedController.isConvokeLikeKind("convoke_W"));
        assertTrue(ScriptedController.isConvokeLikeKind("improvise_generic"));
        assertFalse(ScriptedController.isConvokeLikeKind("x"));
        assertFalse(ScriptedController.isConvokeLikeKind("untap"));
    }

    @Test
    public void summoningSickKataraTapsItselfToWaterbendX() throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }

        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();

        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.contains("announce X=1")), "notes: " + row.notes);
        assertTrue(row.notes.stream().anyMatch(n -> n.equals("convoke step 8: 1 tapped")),
                "the waterbend tap must be consumed, notes: " + row.notes);

        Card katara = (Card) m.entity("p0:Katara, Water Tribe's Hope");
        assertNotNull(katara);
        assertTrue(katara.isTapped(), "Katara tapped herself to pay Waterbend {1} (X = 1)");

        JsonObject step8 = null;
        for (JsonObject snap : row.snapshots) {
            JsonElement cp = snap.get("checkpoint");
            if (cp != null && "step 8 (activate)".equals(cp.getAsString())) {
                step8 = snap;
            }
        }
        assertNotNull(step8, "step-8 snapshot");
        int abilities = 0;
        for (JsonElement e : step8.getAsJsonArray("stack")) {
            JsonObject o = e.getAsJsonObject();
            if ("ability".equals(o.get("kind").getAsString())
                    && o.get("source").getAsString().startsWith("Katara")) {
                abilities++;
            }
        }
        // Katara's own enters trigger plus the activated waterbend ability.
        assertEquals(abilities, 2, "stack at step 8: " + step8.get("stack"));
    }
}
