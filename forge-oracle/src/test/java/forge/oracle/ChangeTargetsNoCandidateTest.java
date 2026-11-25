// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * xproof Forge decision_shape cluster xpt-5b82851f (Deflection, Shunt,
 * Swerve): p1's Aerial Volley targets only its Ornithopter; p0's Deflection
 * targets the Volley ("change the target of target spell with a single
 * target"); p1 then Bounce Offs the Ornithopter to its hand. When Deflection
 * resolves no creature with flying is on the battlefield, so the Volley has no
 * legal new target and its target stays unchanged (CR 115.7a): gorge poses
 * nothing. Forge's ChangeTargetsEffect still calls chooseNewTargetsFor (the
 * change is mandatory, so the optional-and-no-copy quiet return does not
 * apply); the driver now counts the candidates Forge's own selection would
 * offer and, with none, keeps the targets with a note instead of a strict
 * miss "Missing new targets answer at step 15".
 */
public class ChangeTargetsNoCandidateTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ChangeTargetsNoCandidateTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("changetargets").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("changetargets").resolve(name);
    }

    private static Request parse(String name) throws Exception {
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            return Request.parse(in.readLine());
        }
    }

    private static ResultRow replay(String name) throws Exception {
        StepMachine m = new StepMachine(parse(name), true);
        return m.run();
    }

    /** The batch driver's shape: strict first, then loose on a strict miss, so
     * the row carries the miss in strictMiss. */
    private static ResultRow replayBatch(String name) throws Exception {
        return new ScenarioReplay(new ForgeEngine(), null).replay(parse(name));
    }

    @Test
    public void noLegalNewTargetKeepsTheTargetWithoutAMiss() throws Exception {
        ResultRow row = replay("deflection-aerial-volley-no-candidate.jsonl");
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("new targets: no legal candidate")),
                "Forge's mandatory ask is answered by keeping the targets, notes: " + row.notes);
        assertTrue(row.notes.stream().noneMatch(n -> n.startsWith("loose: AI answered new targets")),
                "the AI did not answer the new-targets ask, notes: " + row.notes);
    }

    /** The same game with a second Ornithopter (p1) that is never Bounce Offed:
     * when Deflection resolves a legal new target still exists, but the gorge
     * log holds no retarget decision, so the ask is still a strict miss. This
     * pins the miss branch against a driver that keeps the targets whenever no
     * decision exists. */
    @Test
    public void legalNewTargetWithNoGorgeDecisionIsStillAMiss() throws Exception {
        ResultRow row = replayBatch("deflection-volley-second-flyer-still-miss.jsonl");
        assertNotNull(row.strictMiss, "a legal new target with no gorge decision is still a miss, notes: " + row.notes);
        assertTrue(row.strictMiss.contains("new targets"),
                "the miss names the new-targets ask: " + row.strictMiss);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("loose: AI answered new targets")),
                "the loose attempt answered the ask and the game went on, notes: " + row.notes);
    }
}
