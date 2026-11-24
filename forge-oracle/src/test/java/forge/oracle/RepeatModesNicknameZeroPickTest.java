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
 * xproof Forge decision_shape clusters xpt-7553d8f4, xpt-7852725e,
 * xpt-b0865a52 and xpt-a3ee64aa: three matching gaps in the driver.
 * <ul>
 * <li>CR 700.2d: a Confluence may choose the same mode more than once
 * (CharmEffect CanRepeatModes). ScriptedController.chooseModeForAbility now
 * passes allowRepeat to StepMachine.chooseModes, which binds a mode already
 * in the answer again.</li>
 * <li>Forge substitutes a card's nickname (the part before the first comma)
 * into trigger text, while gorge's order label keeps the raw NICKNAME token.
 * StepMachine.norm folds both to the same placeholder, so Uro's sacrifice and
 * draw triggers bind in gorge's chosen order.</li>
 * <li>CR 608.2c/700.2: a choice among zero cards is no choice. Forge's
 * Dawnbreak Reclaimer still asks its empty ChooseCard and then ChoosePlayer;
 * pickObjects now consumes only an explicit empty answer and leaves the p1
 * player pick queued for ChoosePlayer. The following Optional ChangeZoneAll
 * with no remembered card answers no without an unscripted ask.</li>
 * </ul>
 * Every test replays a request the exporter wrote and requires a strict run
 * (no miss) with no unconsumed gorge decision.
 */
public class RepeatModesNicknameZeroPickTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(RepeatModesNicknameZeroPickTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("repeat-modes-nickname-zeropick").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures")
                .resolve("repeat-modes-nickname-zeropick").resolve(name);
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
    public void verdantConfluenceRepeatsTheReturnMode() throws Exception {
        StepMachine m = replay("verdant-confluence.jsonl");
        String bound = "modes step 6: [Return target permanent card from your graveyard to your hand., "
                + "Search your library for a basic land card, put it onto the battlefield tapped, then shuffle., "
                + "Return target permanent card from your graveyard to your hand.]";
        assertEquals(count(m, bound), 1L, "the third slot repeats the first mode, notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }

    @Test
    public void wretchedConfluenceRepeatsTheDrawModeThreeTimes() throws Exception {
        StepMachine m = replay("wretched-confluence.jsonl");
        String bound = "modes step 5: [Target player draws a card and loses 1 life., "
                + "Target player draws a card and loses 1 life., "
                + "Target player draws a card and loses 1 life.]";
        assertEquals(count(m, bound), 1L, "all three slots are the one mode, notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }

    @Test
    public void uroNicknameTriggersBindInGorgeOrder() throws Exception {
        StepMachine m = replay("uro-titan.jsonl");
        assertEquals(count(m, "trigger order step 5: 2 bound"), 1L, "both ETB triggers bind, notes: " + m.notes);
        assertEquals(count(m, "pick step 7 zone change:"), 1L, "the land drop after the order is consumed, notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }

    @Test
    public void dawnbreakEmptyCardAsksLeaveThePlayerPick() throws Exception {
        StepMachine m = replay("dawnbreak-reclaimer.jsonl");
        assertEquals(count(m, "pick step 20 card (Choose a card ): 0 options; no gorge pick to consume"), 2L,
                "both empty graveyard card asks are answered empty, notes: " + m.notes);
        assertTrue(m.notes.contains("entity (Choose a player): lone option, gorge's pick consumed"),
                "ChoosePlayer consumes the queued p1 pick, notes: " + m.notes);
        assertEquals(count(m, "zero-candidate confirm step 20: no ("), 1L,
                "the Optional return with nothing remembered is answered no, notes: " + m.notes);
        assertEquals(count(m, "loose:"), 0L, "no AI-answered ask, notes: " + m.notes);
    }
}
