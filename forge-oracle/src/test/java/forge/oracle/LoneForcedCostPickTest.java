// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * xproof Forge decision_shape clusters xpt-5e60af5c / xpt-f2f006f3 ("Missing
 * cost CostSacrifice answer at step N: gorge pick p0:Swamp is not among Forge's
 * 1 options"). Memnite saddles The Gitrog, Ravenous Ride, which hits p1; at the
 * trigger's resolution Forge's Sacrifice cost offers exactly one creature
 * (Memnite, the only saddler), so the pick is forced and gorge logs none. The
 * queued p0:Swamp pick answers the LATER "put up to X land cards from your hand
 * onto the battlefield" ask of the same step, so a forced lone cost must leave
 * it queued rather than consume it and miss.
 */
public class LoneForcedCostPickTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(LoneForcedCostPickTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("gitrog").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("gitrog").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        return new StepMachine(req, true).run();
    }

    private static boolean onBattlefield(JsonObject snap, String name) {
        JsonArray perms = snap.getAsJsonArray("permanents");
        for (JsonElement e : perms) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("controller").getAsInt() == 0 && name.equals(p.get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void gitrogLoneSacrificeLeavesLandPick() throws Exception {
        ResultRow row = replay("lone-sacrifice-then-land-move.jsonl");
        assertNull(row.harness, "harness row (a strict miss): " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.contains("cost CostSacrifice") && n.contains("queued pick left for a later ask")),
                "the lone Sacrifice cost is forced and leaves the queued pick, notes: " + row.notes);
        JsonObject last = row.snapshots.get(row.snapshots.size() - 1);
        assertTrue(onBattlefield(last, "Swamp"), "the p0:Swamp pick put the land onto the battlefield: " + last);
        assertTrue(!onBattlefield(last, "Memnite"), "Memnite was sacrificed as the forced cost: " + last);
    }
}
