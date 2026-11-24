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
 * Forge's ChooseEvenOddEffect asks chooseBinary(OddsOrEvens), true meaning Odd.
 * The driver mapped only TapOrUntap, so gorge's even/odd pick was left over:
 * Extinction Event logs a resolution choose_n[odd] "odd" at step 6 and Gollum,
 * Riddle Master logs an as-enters choose_n[evenodd] "Odd" at step 4 (resume
 * "etb", so the pick kind rather than a resume string is the role to match).
 * Both answers are Odd.
 */
public class OddsOrEvensBinaryTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(OddsOrEvensBinaryTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("unmapped").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("unmapped").resolve(name);
    }

    private static StepMachine replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        return m;
    }

    private static long count(StepMachine m, String prefix) {
        return m.notes.stream().filter(n -> n.startsWith(prefix)).count();
    }

    @Test
    public void extinctionEventOddResolutionAnswersOdd() throws Exception {
        StepMachine m = replay("odds-or-evens-odd.jsonl");
        assertTrue(m.notes.contains("binary step 6 (OddsOrEvens): odd [odd]"),
                "gorge's odd answers true, notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }

    @Test
    public void gollumEvenOddAsEntersAnswersOdd() throws Exception {
        StepMachine m = replay("odds-or-evens-evenodd.jsonl");
        assertTrue(m.notes.contains("binary step 4 (OddsOrEvens): odd [Odd]"),
                "gorge's Odd answers true, notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }
}
