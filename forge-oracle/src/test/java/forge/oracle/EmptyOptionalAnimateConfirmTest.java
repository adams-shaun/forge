// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * xproof Forge decision_shape cluster "Missing yesno answer at step N: confirm
 * null: It has base power and toughness N/N until end of turn." (Sita Varma,
 * Masked Racer's Exhaust with X = 0 and no other creature). The Optional$
 * Animate on "each other creature you control" has nothing to animate, an
 * election with no board effect either way (CR 608.2d), so gorge poses no ask
 * and logs no yes/no; Forge's AnimateEffect still calls confirmAction. The
 * driver answers no when no yes/no is queued and no battlefield card would be
 * animated, and keeps the strict miss when one would be.
 */
public class EmptyOptionalAnimateConfirmTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(EmptyOptionalAnimateConfirmTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("animate").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("animate").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        return m.run();
    }

    @Test
    public void noOtherCreatureDeclinesTheConfirm() throws Exception {
        ResultRow row = replay("sita-varma-no-other-creature.jsonl");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("empty animate confirm step ")
                        && n.contains("It has base power and toughness")),
                "the empty Animate confirm is declined, notes: " + row.notes);
    }

    @Test
    public void anotherCreatureKeepsTheStrictMiss() throws Exception {
        ResultRow row = replay("sita-varma-with-other-creature.jsonl");
        // A strict replay reports a miss as its harness row (ScenarioReplay
        // then re-runs loose and records the text as strict_miss).
        assertNotNull(row.harness, "a creature to animate must still strict-miss");
        assertTrue(row.harness.contains("Missing yesno answer")
                        && row.harness.contains("Grizzly Bears") && row.harness.contains("has base power and toughness"),
                "the miss names the Animate confirm: " + row.harness);
        assertTrue(row.notes.stream().noneMatch(n -> n.startsWith("empty animate confirm step ")),
                "no decline is noted when a creature would be animated, notes: " + row.notes);
    }
}
