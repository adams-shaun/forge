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
 * xproof Forge decision_shape cluster xpt-83c211ae, shape 2: Cataclysmic
 * Gearhulk (ChooseCard ChooseEach$ Artifact &amp; Creature &amp; Enchantment &amp;
 * Planeswalker, Mandatory$). Forge skips a category with no candidate (no
 * enchantment, no planeswalker) without asking, and gorge logs nothing for it
 * either. The Gearhulk itself is the lone artifact and the lone creature, so
 * Forge asks both categories over a one-card list and gorge logs both as
 * one-option picks of that card; the lone-forced shortcut left them over. The
 * two empty categories must stay silent: no extra ask, no leftover.
 */
public class EmptyCategoryChoiceTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(EmptyCategoryChoiceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
    public void loneCategoryCandidatesAreConsumedAndEmptyCategoriesStaySilent() throws Exception {
        StepMachine m = replay("lone-category-choice.jsonl");
        long consumed = m.notes.stream().filter(n -> n.endsWith(": lone option, gorge's pick consumed")
                && n.startsWith("entity (")).count();
        assertEquals(consumed, 2L, "the artifact and creature categories consume gorge's picks, notes: " + m.notes);
        assertTrue(m.notes.stream().noneMatch(n -> n.startsWith("loose: AI answered")),
                "no category Forge asked was answered by the AI, notes: " + m.notes);
    }
}
