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
 * xproof Forge decision_shape clusters xpt-110a8ad3, xpt-4d26982b, xpt-704ee28b
 * and xpt-b8ebd380: three asks the driver never mapped.
 * <ul>
 * <li>Join Forces (Alliance of Arms): ChooseNumberEffect with ChooseAnyNumber$
 * calls announceRequirements with a non-X title; gorge logs one
 * choose_n[number] per player.</li>
 * <li>Mystic Barrier: ChooseDirectionEffect asks chooseBinary(LeftOrRight);
 * gorge logs choose_n[direction] "left"/"right"; true means Left.</li>
 * <li>Optional$ Attach (Heavenly Blademaster's Object$ form, Nahiri's
 * Choices$ form): one confirmAction per attachment, answered from gorge's one
 * attachment pick; an empty pick answers no.</li>
 * </ul>
 * Every test replays a request the exporter wrote and requires a strict run
 * (no miss) with no unconsumed gorge decision.
 */
public class UnmappedAsksJoinDirectionAttachTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(UnmappedAsksJoinDirectionAttachTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
    public void joinForcesNumberPicksAreBothConsumed() throws Exception {
        StepMachine m = replay("join-forces-number.jsonl");
        assertEquals(count(m, "announce number step 3: 0"), 2L, "one number pick per player, notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }

    @Test
    public void mysticBarrierDirectionAnswersLeft() throws Exception {
        StepMachine m = replay("choose-direction.jsonl");
        assertTrue(m.notes.contains("binary step 9 (LeftOrRight): left [left]"), "gorge's left answers true, notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }

    @Test
    public void heavenlyBlademasterAttachesTheNamedAttachment() throws Exception {
        StepMachine m = replay("attach-optional-subset.jsonl");
        assertEquals(count(m, "attach-confirm step 10: yes Paradise Mantle"), 1L, "notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }

    @Test
    public void nahiriLoneEquipmentPickIsTheYes() throws Exception {
        StepMachine m = replay("attach-optional-choice.jsonl");
        assertEquals(count(m, "attach-confirm step 10: yes Spidersilk Net"), 1L, "notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }

    @Test
    public void nahiriEmptyPickDeclinesTheAttach() throws Exception {
        StepMachine m = replay("attach-optional-declined.jsonl");
        assertEquals(count(m, "attach-confirm step 10: no Spidersilk Net"), 1L, "notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }
}
