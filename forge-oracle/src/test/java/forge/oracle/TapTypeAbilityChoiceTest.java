// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * xproof Forge decision_shape cluster xpt-bf618006 ("Missing ability choice
 * answer at step N: getAbilityToPlay over N abilities of Disruption Protocol",
 * gorge~forge, gorge is right: CR 601.2b lets the player pay either additional
 * cost). Disruption Protocol (K:AlternateAdditionalCost:tapXType<1/Artifact>:1)
 * offers two spell abilities, one per additional cost; Forge's CostTapType
 * part prints "Tap 1 untapped Artifact you control", so gorge's pick label
 * "Tap 1 artifact" is not a substring of the cost text, and the verb fallback
 * compared the CostPart class name against "costtap" while the class of a
 * tap-a-named-type part is CostTapType. getAbilityToPlay now binds by an
 * explicit verb-to-class map: "tap this permanent" ({T}) is CostTap, any
 * other tap label names a type and is CostTapType.
 */
public class TapTypeAbilityChoiceTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(TapTypeAbilityChoiceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("altaddcost").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("altaddcost").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        return new StepMachine(req, true).run();
    }

    private static boolean tappedOnBattlefield(JsonObject snap, String name) {
        JsonArray perms = snap.getAsJsonArray("permanents");
        for (JsonElement e : perms) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("controller").getAsInt() == 0 && name.equals(p.get("name").getAsString())
                    && p.has("tapped") && p.get("tapped").getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void disruptionProtocolTapPickBindsCostTapType() throws Exception {
        ResultRow row = replay("disruption-protocol-tap-s1.jsonl");
        assertNull(row.harness, "harness row (a strict miss): " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.equals("ability choice step 7: Tap 1 artifact")),
                "the altaddcost pick bound the CostTapType spell ability, notes: " + row.notes);
        JsonObject last = row.snapshots.get(row.snapshots.size() - 1);
        assertTrue(tappedOnBattlefield(last, "Memnite"), "Memnite was tapped as the additional cost: " + last);
    }
}
