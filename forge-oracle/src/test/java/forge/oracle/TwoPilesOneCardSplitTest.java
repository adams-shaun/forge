// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * xproof Forge decision_shape cluster "Missing card (Divide cards into two
 * piles) answer at step N: 1 options, 0..1 to pick" (Epiphany at the Drownyard
 * cast with X = 0, so one card is revealed). Forge's TwoPilesEffect always asks
 * the separator to choose cards for pile 1 (min 0, max the set size). With one
 * card the two possible splits are the same partition (the card and an empty
 * pile, CR 700.3), so gorge poses no split and logs only the opponent's pile
 * pick ("First pile"). The driver answers the one-card split with the card as
 * pile 1 (gorge's pile A) when no gorge object pick is queued for it, and the
 * following chooseCardsPile reads that pile pick as before.
 */
public class TwoPilesOneCardSplitTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(TwoPilesOneCardSplitTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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

    @Test
    public void oneCardSplitWithNoGorgeSplitPutsTheCardInPileOne() throws Exception {
        StepMachine m = replay("two-piles-one-card-epiphany-at-the-drownyard.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("two piles step ") && n.contains("the card is pile 1")),
                "the lone card is pile 1, notes: " + m.notes);
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("pile step ") && n.contains("First pile")),
                "the opponent's pile pick is still consumed, notes: " + m.notes);
    }

    /** Only a one-card 0..1 TwoPiles ask qualifies: no spell ability, or a
     * bigger pool, is never swallowed here (it falls through to pickObjects). */
    @Test
    public void onlyTheLoneTwoPilesAskQualifies() throws Exception {
        StepMachine m = replay("two-piles-one-card-epiphany-at-the-drownyard.jsonl");
        assertFalse(m.loneTwoPilesSplit(0, null, 1, 0, 1), "no spell ability");
        assertFalse(m.loneTwoPilesSplit(0, null, 2, 0, 2), "a two-card pool is a real split");
    }
}
