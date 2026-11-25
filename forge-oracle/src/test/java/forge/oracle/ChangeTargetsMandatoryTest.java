// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
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
 * xproof Forge decision_shape cluster xpt-6b85f89a: p0's Revolutionary Rebuff
 * targets Reach Through Mists; p1 casts Redirect Lightning (an additional-cost
 * cast, 5 life) targeting the Rebuff; when Redirect resolves, gorge's
 * effChangeTargets retargets the Rebuff to Redirect Lightning (still a
 * nonartifact spell on the stack, CR 608.2) through its one-candidate choose
 * ask (CR 115.7a: a mandatory change cannot keep the old target, so Reach
 * Through Mists is not offered). The ask carries no option label, so its pick
 * travels in pick_refs alone; gorge marks the decision retarget.
 *
 * Forge's ChangeTargetsEffect poses no cast-time confirm (the card is not
 * Optional$) and asks chooseNewTargetsFor on the Rebuff as Redirect resolves;
 * StepMachine.retargetTargets consumes gorge's pick and moves the Rebuff's
 * target (CR 115.7). Rebuff then counters Redirect Lightning, so p1's
 * graveyard is [Redirect Lightning] and Reach Through Mists stays in hand --
 * the pre-fix driver strict-missed "Missing new targets answer at step 11"
 * and kept the old target, leaving [Redirect Lightning, Reach Through Mists].
 */
public class ChangeTargetsMandatoryTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ChangeTargetsMandatoryTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        return m.run();
    }

    private static JsonObject lastPlayer(ResultRow row, int seat) {
        JsonObject last = row.snapshots.get(row.snapshots.size() - 1);
        for (JsonElement e : last.getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("seat").getAsInt() == seat) {
                return p;
            }
        }
        return null;
    }

    private static int life(ResultRow row, int seat) {
        return lastPlayer(row, seat).get("life").getAsInt();
    }

    private static String zoneNames(ResultRow row, int seat, String zone) {
        JsonArray g = lastPlayer(row, seat).getAsJsonArray(zone);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < g.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(g.get(i).getAsString());
        }
        return out.append("]").toString();
    }

    @Test
    public void mandatoryChangeTargetsAppliesTheRetargetPick() throws Exception {
        ResultRow row = replay("redirect-lightning-rebuff.jsonl");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("retarget step 11")),
                "the resolution-time retarget pick is applied, notes: " + row.notes);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("target step 9") && n.contains("Revolutionary Rebuff")),
                "Redirect Lightning targeted the Rebuff, notes: " + row.notes);
        assertEquals(zoneNames(row, 1, "graveyard"), "[Redirect Lightning]",
                "the Rebuff countered Redirect Lightning, not its old target");
        assertEquals(life(row, 1), 15, "p1 paid 5 life to cast Redirect Lightning and kept its life after that");
        assertEquals(life(row, 0), 20, "p0 took no damage");
        assertEquals(zoneNames(row, 0, "graveyard"), "[Revolutionary Rebuff]",
                "the Rebuff itself went to its owner's graveyard after resolving");
        boolean mistsOnStack = false;
        for (JsonElement e : row.snapshots.get(row.snapshots.size() - 1).getAsJsonArray("stack")) {
            mistsOnStack |= "Reach Through Mists".equals(e.getAsJsonObject().get("source").getAsString());
        }
        assertTrue(mistsOnStack, "the old target was not countered: it is still resolving on the stack");
    }
}
