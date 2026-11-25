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

/**
 * xproof Forge decision_shape cluster xpt-e750d7a5: Alhammarret, High Arbiter
 * cast against an opponent with an empty hand. ChooseCardNameEffect
 * (ChooseFromDefinedCards$ Remembered) builds its face list from the revealed
 * nonland cards, which is empty, and still calls chooseCardName(sa, faces,
 * message). gorge logs no decision (no name to choose); the driver treated only
 * a one-face list as forced and answered the empty list with a
 * "Missing card name answer" strict miss. An empty list now returns "" with a
 * note, as ChooseCardNameAi does, and the effect names nothing. The fixture is
 * the forge-export request of the find's item.
 */
public class ChooseCardNameEmptyFacesTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ChooseCardNameEmptyFacesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("alhammarret").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("alhammarret")
                .resolve(name);
    }

    @Test
    public void emptyFaceListNamesNothingWithoutAMiss() throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture("alhammarret-empty-hand.jsonl"),
                StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();

        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "an empty face list has no answer to miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertEquals(row.notes.stream().filter(n -> n.startsWith("loose: AI answered")).count(), 0L,
                "no card-name ask falls to the AI, notes: " + row.notes);
        assertEquals(row.notes.stream().filter(n -> n.startsWith("card name step ")
                && n.contains("no face to name")).count(), 1L, "one empty-face ask noted, notes: " + row.notes);
        assertTrue(m.seats[0].getCardsIn(forge.game.zone.ZoneType.Battlefield).stream()
                .anyMatch(c -> c.getName().equals("Alhammarret, High Arbiter")), "Alhammarret resolved");
    }
}
