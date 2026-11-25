// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.zone.ZoneType;

/**
 * Phyrexian Processor ("As Phyrexian Processor enters, pay any amount of
 * life.", Cost$ Mandatory PayLife&lt;X&gt;): gorge logs the amount as a
 * {@code choose_n} with pick kind {@code paylife} and an "N life" label, 0
 * included (CR 119.4, 118.3). Forge announces it as the cost's X, which the
 * driver took only from an {@code x} decision, so the pick was a leftover and
 * the AI answered ("Missing x answer at step 6: X for"). xproof cluster
 * xpt-b1209a2d.
 */
public class PhyrexianProcessorPayLifeTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(PhyrexianProcessorPayLifeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("phyrexian").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("phyrexian").resolve(name);
    }

    private static StepMachine run(String from, String to, ResultRow[] out) throws Exception {
        Bootstrap.init(TestRes.res());
        String line;
        try (BufferedReader in = Files.newBufferedReader(fixture("processor-zero-life.jsonl"), StandardCharsets.UTF_8)) {
            line = in.readLine();
        }
        assertTrue(line.contains(from), "fixture carries the 0 life pick");
        StepMachine m = new StepMachine(Request.parse(line.replace(from, to)), true);
        out[0] = m.run();
        return m;
    }

    private static final String ZERO = "\"picks\":[\"0 life\"],\"pick_idx\":[0]";

    /** The find: a 0 life payment is announced as X=0, nothing is left over,
     * and the seat keeps 20 life. */
    @Test
    public void zeroLifeIsAnnouncedAsX() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = run(ZERO, ZERO, row);

        assertNull(row[0].harness, "harness row: " + row[0].harness);
        assertNull(row[0].leftover, "unconsumed gorge decision: " + row[0].leftover);
        assertTrue(row[0].notes.stream().noneMatch(n -> n.startsWith("loose:")), "notes: " + row[0].notes);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.startsWith("announce X=0 ")), "notes: " + row[0].notes);
        Card processor = (Card) m.entity("p0:Phyrexian Processor");
        assertNotNull(processor);
        assertTrue(processor.isInZone(ZoneType.Battlefield), "Phyrexian Processor entered");
        assertEquals(processor.getController().getLife(), 20, "0 life was paid");
    }

    /** A nonzero amount ("3 life") is paid in full. */
    @Test
    public void threeLifeIsPaidInFull() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = run(ZERO, "\"picks\":[\"3 life\"],\"pick_idx\":[3]", row);

        assertNull(row[0].harness, "harness row: " + row[0].harness);
        assertNull(row[0].leftover, "unconsumed gorge decision: " + row[0].leftover);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.startsWith("announce X=3 ")), "notes: " + row[0].notes);
        Card processor = (Card) m.entity("p0:Phyrexian Processor");
        assertNotNull(processor);
        assertTrue(processor.isInZone(ZoneType.Battlefield), "Phyrexian Processor entered");
        assertEquals(processor.getController().getLife(), 17, "3 life was paid");
    }
}
