// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.zone.ZoneType;

/**
 * Two xproof Forge decision_shape clusters whose gorge decisions the driver
 * logged but never consumed. Each fixture is one forge-export request with
 * gorge's answers:
 *
 * <ul>
 * <li>Rottenmouth Viper (CR 118.12a): ChooseGenericEffect drops every choice
 *     whose UnlessCost the chooser cannot pay. With an empty hand and only a
 *     nonland permanent available, one choice survives, and Forge calls
 *     chooseSpellAbilitiesForEffect with a single spell; ScriptedController
 *     returned early and left gorge's one-option mode decision over.</li>
 * <li>Spock, Always Your Friend (CR 401.4): Dig's remainder goes to the bottom
 *     in any order. gorge logs that order as a "dig_bottom" arrange decision;
 *     StepMachine.orderCards missed a different-name order to Library instead
 *     of answering it from the picks.</li>
 * </ul>
 */
public class LoneModeLibraryOrderTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(LoneModeLibraryOrderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
    public void loneAvailableGenericChoiceIsConsumed() throws Exception {
        StepMachine m = replay("lone-generic-mode.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("generic choice step 9 (forced):")),
                "the forced mode is consumed and noted, notes: " + m.notes);
        Card shield = (Card) m.entity("p1:Shield Sphere");
        assertTrue(shield.isInZone(ZoneType.Graveyard),
                "the chosen mode's sacrifice is paid, board: " + m.notes);
    }

    @Test
    public void differentNameDigBottomOrderIsAnswered() throws Exception {
        StepMachine m = replay("dig-bottom-order.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.equals(
                        "order step 6: order to Library [Island, Mountain, Island, Island]")),
                "gorge's bottom order answers Forge's order ask, notes: " + m.notes);
    }
}
