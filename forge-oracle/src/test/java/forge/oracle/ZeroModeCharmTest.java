// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertFalse;
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
 * xproof cluster xpt-d2190210: a modal ability asked for zero modes.
 *
 * <p>Bumi, King of Three Trials's ETB is {@code DB$ Charm | MinCharmNum$ 0 |
 * CharmNum$ X} with X = the Lesson cards in your graveyard. With none, CR
 * 700.2 lets the controller choose up to zero modes, and CR 603.3c removes a
 * modal ability with no mode chosen from the stack. gorge poses no KModes
 * decision for that ask (engine/effects/charm.go returns before the decision
 * when max &lt; 1), so the only answer Forge can take is the empty list. The
 * driver used to strict-miss because the offered mode list was not the ask's
 * num.</p>
 *
 * <p>A maximum of zero is not a question: the controller returns an empty
 * list without consuming a mode decision, so a decision queued for a later
 * mode ask is still there for that ask. Every num &gt; 0 ask keeps the strict
 * miss when no decision answers it.</p>
 */
public class ZeroModeCharmTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ZeroModeCharmTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("zero-mode").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures")
                .resolve("zero-mode").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        return new StepMachine(req, true).run();
    }

    @Test
    public void bumiZeroModeCharmTakesTheEmptyList() throws Exception {
        ResultRow row = replay("bumi-zero-mode.jsonl");
        assertTrue(row.strict, "the replay must be strict");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("modes step 8: none (0 modes possible")),
                "the zero-mode ask was not answered empty, notes: " + row.notes);
        assertTrue(row.notes.stream().noneMatch(n -> n.startsWith("loose:")),
                "the zero-mode ask fell through to the AI, notes: " + row.notes);
    }

    @Test
    public void zeroModePeekLeavesALaterModeDecisionQueued() throws Exception {
        Request req = Request.parse("{\"id\":\"zero-mode-peek\",\"item\":{\"steps\":[]},"
                + "\"gorge_decisions\":[{\"step\":-1,\"seat\":0,\"kind\":\"mode\","
                + "\"picks\":[\"Take 5 damage\"],\"resume\":\"unless_pay\",\"min\":1,\"max\":1}]}");
        StepMachine m = new StepMachine(req, true);
        assertTrue(m.hasMode(0), "the queued mode decision answers this seat");
        assertFalse(m.hasMode(1), "the queued mode decision is another seat's");
        assertFalse(m.dq.all().get(0).isConsumed(), "the peek must not consume the decision");
        assertTrue(m.hasMode(0), "the decision is still queued for the ask that can use it");
    }

    @Test
    public void numOneModeAskWithoutADecisionStillStrictMisses() throws Exception {
        ResultRow row = replay("bumi-one-lesson-no-decision.jsonl");
        assertTrue(row.strict, "the replay must be strict");
        assertNotNull(row.harness, "a num = 1 mode ask with no decision must strict-miss");
        assertTrue(row.harness.startsWith("Missing mode answer at step 8"),
                "want a mode strict miss at step 8, got: " + row.harness);
        assertTrue(row.notes.stream().noneMatch(n -> n.startsWith("modes step 8: none")),
                "a num = 1 ask must not take the zero-mode path, notes: " + row.notes);
    }
}
