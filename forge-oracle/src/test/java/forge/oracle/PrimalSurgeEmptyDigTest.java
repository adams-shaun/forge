// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import forge.game.zone.ZoneType;

/**
 * An xproof Forge decision_shape cluster (Primal Surge). The library is exactly
 * 29 Forests: gorge exiles the top card, puts it onto the battlefield and
 * repeats, so it asks 29 times and stops when no card can be exiled. Primal
 * Surge repeats while Remembered holds a permanent; DigEffect honoured
 * ForgetOtherRemembered only when something was dug, so on the 30th pass over
 * the empty library Remembered still held the 29th Forest (now on the
 * battlefield) and Forge asked a spurious 30th "move Forest from exile" confirm.
 * The fixture is the forge-export request of the find's item.
 */
public class PrimalSurgeEmptyDigTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(PrimalSurgeEmptyDigTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("primalsurge").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("primalsurge")
                .resolve(name);
    }

    @Test
    public void emptyLibraryStopsAfterTheLastForest() throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture("primal-surge-29-forests.jsonl"),
                StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();

        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "a 30th confirm has no recorded answer: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertFalse(row.notes.stream().anyMatch(n -> n.startsWith("loose: AI answered")),
                "no confirm falls to the AI, notes: " + row.notes);
        assertEquals(row.notes.stream().filter(n -> n.startsWith("yesno step 12: yes (confirm")).count(), 29,
                "one confirm per exiled Forest and none for the empty library, notes: " + row.notes);
        assertEquals(m.seats[0].getCardsIn(ZoneType.Library).size(), 0, "the library was emptied");
        assertEquals(m.seats[0].getCardsIn(ZoneType.Exile).size(), 0, "every Forest left exile");
        assertEquals(m.seats[0].getCardsIn(ZoneType.Battlefield).size(), 39,
                "10 setup Forests plus the 29 from the library");
    }
}
