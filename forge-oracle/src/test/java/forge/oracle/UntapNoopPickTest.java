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
 * SUP-s131-supp window xe:RAND:4:13:w16. Peregrine Drake's enters ability is
 * "untap up to five lands"; gorge's step-10 decision is one choose_n with pick
 * kind "untap" and pick_refs ["p0:Island"]. That Island is already untapped,
 * so the choice is a legal no-op (CR 608.2d: the controller announces the
 * choice while applying the effect; CR 701.26b: only tapped permanents can be
 * untapped). Forge's UntapEffect offers only permanents that can untap, so
 * p0:Island is absent from its candidate list.
 *
 * <p>Before the fix the driver's same-name fallback bound the ref to another
 * Island (a tapped copy) and untapped it, changing the board. The driver must
 * consume an unmatched up-to pick as a no-op and answer empty, never let the
 * AI or a same-named sibling decide.
 */
public class UntapNoopPickTest {

    private static final String FIXTURE = "peregrine-drake-w16.jsonl";

    /** The fixture under the fork's forge-oracle project, found from the test
     * classes directory so an external FORGE_RES cannot point at a different
     * checkout. */
    private static Path fixture() {
        try {
            Path p = Path.of(UntapNoopPickTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("untap").resolve(FIXTURE);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("untap").resolve(FIXTURE);
    }

    @Test
    public void upToUntapPickOfAnUntappedLandIsANoop() throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }

        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();

        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);

        JsonObject step10 = null;
        for (JsonObject snap : row.snapshots) {
            JsonElement cp = snap.get("checkpoint");
            if (cp != null && "step 10 (pass)".equals(cp.getAsString())) {
                step10 = snap;
                break;
            }
        }
        assertNotNull(step10, "step-10 snapshot");

        // gorge's step-10 board has two untapped and three tapped Islands
        // (the three that paid for Peregrine Drake); the recorded no-op pick
        // must not add a fourth untapped Island.
        int tapped = 0;
        int untapped = 0;
        for (JsonElement e : step10.getAsJsonArray("permanents")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("controller").getAsInt() != 0 || !"Island".equals(p.get("name").getAsString())) {
                continue;
            }
            if (p.get("tapped").getAsBoolean()) {
                tapped++;
            } else {
                untapped++;
            }
        }
        assertEquals(tapped, 3, "p0 tapped Islands at step 10: " + step10);
        assertEquals(untapped, 2, "p0 untapped Islands at step 10: " + step10);

        Card island5 = (Card) m.entity("p0:Island#5");
        assertTrue(island5.isTapped(),
                "p0:Island#5 must stay tapped: the recorded untap pick was an already-untapped land");
        assertTrue(row.notes.stream().anyMatch(n -> n.contains("p0:Island") && n.contains("no-op")),
                "the recorded pick must be consumed as a no-op, notes: " + row.notes);
        assertFalse(row.notes.stream().anyMatch(n -> n.contains("took another Island")),
                "a same-named sibling must not stand in for the bound pick, notes: " + row.notes);
    }
}
