// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import forge.game.card.Card;
import forge.game.player.Player;

/**
 * A Phyrexian pip gorge paid with 2 life (CR 107.4f, 118.3b) is a
 * {@code pay_life} pick of the cast or activate step. Forge's AI pays it with
 * payPhyrexian() and payLife(2); the scripted controller paid only mana, so
 * the pip stayed unpaid and PlaySpellAbility.payManaCost refused the cast
 * ("p0:Vault Skirge was not put on the stack (cost unpaid ...)").
 */
public class PhyrexianLifePaymentTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(PhyrexianLifePaymentTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("phyrexian").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("phyrexian").resolve(name);
    }

    private static StepMachine run(String name, ResultRow[] out) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        out[0] = m.run();
        return m;
    }

    /** One Plains pays {1}; {B/P} is paid with 2 life, so Vault Skirge is cast
     * from a 20-life seat that ends at 18. */
    @Test
    public void vaultSkirgeCastsWithOnePlainsAndTwoLife() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = run("vault-skirge-cast.jsonl", row);

        assertNull(row[0].harness, "harness row: " + row[0].harness);
        assertNull(row[0].leftover, "unconsumed gorge decision: " + row[0].leftover);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.startsWith("pay phyrexian with 2 life step 1")),
                "notes: " + row[0].notes);
        Card skirge = (Card) m.entity("p0:Vault Skirge");
        assertNotNull(skirge);
        Player p0 = skirge.getController();
        assertEquals(p0.getLife(), 18, "{B/P} was paid with 2 life");
        assertTrue(skirge.isInZone(forge.game.zone.ZoneType.Battlefield), "Vault Skirge resolved onto the battlefield");
    }

    /** Immolating Souleater's {R/P} with an empty pool: the life pick alone
     * pays the activation, which resolves (+1/+0). */
    @Test
    public void souleaterActivatesFromAnEmptyPoolWithTwoLife() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = run("souleater-activate.jsonl", row);

        assertNull(row[0].harness, "harness row: " + row[0].harness);
        assertNull(row[0].leftover, "unconsumed gorge decision: " + row[0].leftover);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.startsWith("pay phyrexian with 2 life step 0")),
                "notes: " + row[0].notes);
        Card souleater = (Card) m.entity("p0:Immolating Souleater");
        assertNotNull(souleater);
        assertEquals(souleater.getController().getLife(), 18, "{R/P} was paid with 2 life");
        assertEquals(souleater.getNetPower(), 2, "the pump resolved: 1/1 +1/+0");
    }

    /** K'rrik, Son of Yawgmoth ("PayLifeInsteadOf:B", CR 601.2h, 118.9): gorge's
     * pay_life pick on a plain {B} pip pays Muck Rats with 2 life, so the cast
     * leaves the untapped Swamp alone and the seat at 18. */
    @Test
    public void krrikPaysTheBlackPipWithTwoLife() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = run("krrik-muck-rats-cast.jsonl", row);

        assertNull(row[0].harness, "harness row: " + row[0].harness);
        assertNull(row[0].leftover, "unconsumed gorge decision: " + row[0].leftover);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.startsWith("pay black with 2 life step 0")),
                "notes: " + row[0].notes);
        Card rats = (Card) m.entity("p0:Muck Rats");
        assertNotNull(rats);
        assertEquals(rats.getController().getLife(), 18, "{B} was paid with 2 life");
        Card swamp = (Card) m.entity("p0:Swamp");
        assertNotNull(swamp);
        assertTrue(!swamp.isTapped(), "the Swamp was not tapped for the life-paid pip");
        assertTrue(rats.isInZone(forge.game.zone.ZoneType.Stack), "Muck Rats is on the stack");
    }

    /** No pay_life pick, no life: with K'rrik out, a {B} pip gorge paid with the
     * Swamp stays a mana payment and the seat keeps 20 life. */
    @Test
    public void krrikWithoutALifePickStillPaysMana() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = run("krrik-muck-rats-cast-mana.jsonl", row);

        assertNull(row[0].harness, "harness row: " + row[0].harness);
        assertNull(row[0].leftover, "unconsumed gorge decision: " + row[0].leftover);
        assertTrue(row[0].notes.stream().noneMatch(n -> n.contains("with 2 life")), "notes: " + row[0].notes);
        Card rats = (Card) m.entity("p0:Muck Rats");
        assertNotNull(rats);
        assertEquals(rats.getController().getLife(), 20, "no life was paid");
        Card swamp = (Card) m.entity("p0:Swamp");
        assertTrue(swamp.isTapped(), "the Swamp paid the {B}");
    }
}
