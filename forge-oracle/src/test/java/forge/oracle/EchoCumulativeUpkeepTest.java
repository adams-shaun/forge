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

/**
 * xproof decision_shape clusters xpt-0a72be4c, xpt-4f17dcb1 and xpt-57a56bea:
 * echo and cumulative upkeep pay-or-sacrifice answers never reached
 * {@link ScriptedController#payCostToPreventEffect}. Forge builds both keywords
 * as an upkeep trigger whose {@code DB$ Sacrifice | Echo$} /
 * {@code CumulativeUpkeep$} asks payCostToPreventEffect (CR 702.35a / 702.46a),
 * while gorge logs the election as ONE choose_n whose pick kind is
 * echo_pay / echo_pay_manual / echo_sac or
 * cumulative_pay / cumulative_pay_manual / cumulative_sac, followed by the
 * cost's own object pick (echo_action_pick, cumulative_action_sac, a discard)
 * when the answer pays a non-mana cost.
 *
 * <p>Each test replays an exported xproof request in strict mode and requires
 * no harness/strict miss and no unconsumed gorge decision:
 * <ul>
 * <li>Basalt Gargoyle: echo pay ({2}{R}).</li>
 * <li>Deepcavern Imp: echo sacrifice (decline; Forge sacrifices the source).</li>
 * <li>Flow of Maggots: cumulative upkeep pay ({1}).</li>
 * <li>Phyrexian Soulgorger: cumulative upkeep pay with a creature sacrifice,
 *     then the cumulative_action_sac pick.</li>
 * <li>Skizzik Surger: non-mana echo pay (Sac&lt;2/Land&gt;), then the two
 *     echo_action_pick lands.</li>
 * </ul>
 */
public class EchoCumulativeUpkeepTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(EchoCumulativeUpkeepTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("echo-cumulative").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures")
                .resolve("echo-cumulative").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        return row;
    }

    private static void assertNoLoose(ResultRow row) {
        assertTrue(row.notes.stream().noneMatch(n -> n.startsWith("loose:")),
                "no AI-answered ask, notes: " + row.notes);
    }

    @Test
    public void basaltGargoylePaysItsEcho() throws Exception {
        ResultRow row = replay("basalt-gargoyle-echo-pay.jsonl");
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("echo/cumulative upkeep step ")
                        && n.endsWith(": Pay 2 R paid=true")),
                "the echo_pay answer pays, notes: " + row.notes);
        assertNoLoose(row);
    }

    @Test
    public void deepcavernImpDeclinesItsEchoAndIsSacrificed() throws Exception {
        ResultRow row = replay("deepcavern-imp-echo-sacrifice.jsonl");
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("echo/cumulative upkeep step ")
                        && n.endsWith(": Sacrifice Deepcavern Imp")),
                "the echo_sac answer declines without a miss, notes: " + row.notes);
        assertNoLoose(row);
    }

    @Test
    public void flowOfMaggotsPaysItsCumulativeUpkeep() throws Exception {
        ResultRow row = replay("flow-of-maggots-cumulative-pay.jsonl");
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("echo/cumulative upkeep step ")
                        && n.endsWith(": Pay 1 per age (1 age counter(s)) paid=true")),
                "the cumulative_pay answer pays, notes: " + row.notes);
        assertNoLoose(row);
    }

    @Test
    public void phyrexianSoulgorgerPaysItsCumulativeUpkeepSacrifice() throws Exception {
        ResultRow row = replay("phyrexian-soulgorger-cumulative-sacrifice.jsonl");
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("echo/cumulative upkeep step ")
                        && n.endsWith(": Sacrifice 1 creature per age (1 age counter(s)) paid=true")),
                "the cumulative_pay answer pays, notes: " + row.notes);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("pick step ")
                        && n.contains("cost CostSacrifice") && n.contains("Phyrexian Soulgorger")),
                "the cumulative_action_sac pick answers the cost, notes: " + row.notes);
        assertNoLoose(row);
    }

    @Test
    public void skizzikSurgerPaysItsNonManaEcho() throws Exception {
        ResultRow row = replay("skizzik-surger-non-mana-echo.jsonl");
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("echo/cumulative upkeep step ")
                        && n.endsWith(": Sacrifice 2 land paid=true")),
                "the echo_pay answer pays the Sac<2/Land> cost, notes: " + row.notes);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("pick step ")
                        && n.contains("cost CostSacrifice") && n.contains("p0:Mountain")),
                "the two echo_action_pick lands answer the cost, notes: " + row.notes);
        assertNoLoose(row);
    }
}
