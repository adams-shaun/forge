// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
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
 * The xproof Forge decision_shape cluster xpt-71c21a6c (Trickster's
 * Stratagem; Happy Hogan, Bodyguard): ChangeZone AlternativeDecider$
 * TargetedOwner with LibraryPosition$ 1 and LibraryPositionAlternative$ -1.
 * CR 401.4: the owner puts the card second from the top or on the bottom.
 * gorge asks the owner a choose_n[changezone_alternative] whose options are
 * kind "primary" (labelled "second from top") and "bottom"; Forge asks
 * confirmAction(ChangeZoneToAltDestination, ["Top (2nd)", "Bottom"]). The
 * driver matched gorge's label as a prefix of Forge's, which "second from top"
 * is not, and strict-missed "Missing yesno answer". Each fixture is the
 * forge-export request of the find; the bottom one is edited (id :bottom).
 */
public class AltDestinationSecondFromTopTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(AltDestinationSecondFromTopTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("altdest").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("altdest").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        ResultRow row = new StepMachine(req, true).run();
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        return row;
    }

    /** p1's library top after the given step (snapshot step+1). */
    static List<String> p1LibraryTop(ResultRow row, int step) {
        JsonObject snap = row.snapshots.get(step + 1);
        List<String> out = new ArrayList<>();
        for (JsonElement p : snap.getAsJsonArray("players")) {
            JsonObject po = p.getAsJsonObject();
            if (po.get("seat").getAsInt() == 1) {
                po.getAsJsonArray("library_top").forEach(e -> out.add(e.getAsString()));
            }
        }
        return out;
    }

    @Test
    public void tricksterSecondFromTop() throws Exception {
        ResultRow row = replay("trickster-second-from-top.jsonl");
        assertTrue(row.notes.contains("alt destination step 6: second from top (primary)"), "notes: " + row.notes);
        assertEquals(p1LibraryTop(row, 6).subList(0, 2), List.of("Plains", "Memnite"),
                "Memnite is second from the top of its owner's library");
    }

    @Test
    public void tricksterBottom() throws Exception {
        ResultRow row = replay("trickster-bottom.jsonl");
        assertTrue(row.notes.contains("alt destination step 6: bottom (bottom)"), "notes: " + row.notes);
        assertTrue(!p1LibraryTop(row, 6).contains("Memnite"), "Memnite went to the bottom, not the top five");
    }

    @Test
    public void happyHoganSecondFromTop() throws Exception {
        ResultRow row = replay("happy-hogan-second-from-top.jsonl");
        assertTrue(row.notes.contains("alt destination step 8: second from top (primary)"), "notes: " + row.notes);
        assertEquals(p1LibraryTop(row, 8).get(1), "Shield Sphere", "Shield Sphere is second from the top");
    }
}
