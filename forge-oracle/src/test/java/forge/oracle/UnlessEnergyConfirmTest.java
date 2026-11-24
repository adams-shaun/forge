// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.zone.ZoneType;

/**
 * xproof harness cluster "AI is not supposed to reach this code": a paid energy
 * unless-cost ({@code UnlessCost$ PayEnergy<N>}). gorge logs ONE unless_pay mode
 * answer ("Pay the cost") and that answer is the payer's whole choice (CR 118.12);
 * Forge's payCostDuringAbilityResolve then asks confirmPayment ("Do you want to
 * spend N energy?") for the non-mandatory CostPayEnergy part. The confirm must
 * answer yes inside the scripted unless-pay instead of missing and falling
 * through to AiController.confirmPayment, which throws.
 *
 * <ul>
 * <li>Lathnu Hellion: "At the beginning of your end step, sacrifice it unless
 *     you pay {E}{E}". Two energy from entering, paid, Hellion stays.</li>
 * <li>Die Young: {@code PayEnergy<X>} with X chosen as 0 (the answer list has
 *     the choose-a-number and the pay answers).</li>
 * </ul>
 */
public class UnlessEnergyConfirmTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(UnlessEnergyConfirmTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("unlessenergy").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("unlessenergy").resolve(name);
    }

    private static StepMachine replay(String name, ResultRow[] out) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        out[0] = row;
        return m;
    }

    @Test
    public void lathnuHellionEndStepPaysTwoEnergyAndStays() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = replay("lathnu-hellion-end-step-pay-energy.jsonl", row);

        Card hellion = m.card("p0:Lathnu Hellion");
        assertTrue(hellion.isInZone(ZoneType.Battlefield), "Hellion stays: the {E}{E} was paid");
        assertEquals(m.seats[0].getCounters(CounterEnumType.ENERGY), 0, "energy 2 -> 0");
        assertTrue(row[0].notes.stream().anyMatch(n -> n.startsWith("unless-pay step ") && n.endsWith(": Pay the cost paid=true")),
                "the unless_pay answer pays, notes: " + row[0].notes);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.contains("unless-pay confirm step ") && n.contains("2 Energy")),
                "the energy part's confirm is answered inside the unless-pay, notes: " + row[0].notes);
        assertFalse(row[0].notes.stream().anyMatch(n -> n.startsWith("loose: AI answered")),
                "no loose AI answer, notes: " + row[0].notes);
    }

    @Test
    public void dieYoungPaysItsChosenEnergyWithoutASecondConfirm() throws Exception {
        ResultRow[] row = new ResultRow[1];
        replay("die-young-pay-energy-x0.jsonl", row);

        assertTrue(row[0].notes.stream().anyMatch(n -> n.startsWith("unless-pay step ") && n.endsWith(": Pay the cost paid=true")),
                "the unless_pay answer pays, notes: " + row[0].notes);
    }
}
