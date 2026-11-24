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
 * xproof Forge decision_shape cluster xpt-83c211ae, shape 1: Benevolent
 * Offering ("Choose an opponent" twice). ChoosePlayerEffect asks
 * chooseSingleEntityForEffect over the one opponent of a two-player game, and
 * gorge logs each lone "Choose an opponent" as a one-option pick (p1). The
 * lone-forced-entity shortcut returned the option without consuming that
 * pick, leaving both as leftovers. A pick naming exactly the lone option is
 * its answer; any other pick (the pile card of a lone TwoPiles Chooser$, see
 * UnmappedAskTest) is not and stays queued.
 */
public class LoneOpponentChoiceTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(LoneOpponentChoiceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
    public void bothLoneOpponentPicksAreConsumed() throws Exception {
        StepMachine m = replay("lone-opponent-choice.jsonl");
        long consumed = m.notes.stream().filter(n -> n.endsWith(": lone option, gorge's pick consumed")
                && n.startsWith("entity (")).count();
        assertEquals(consumed, 2L, "each Choose an opponent consumes gorge's p1 pick, notes: " + m.notes);
    }
}
