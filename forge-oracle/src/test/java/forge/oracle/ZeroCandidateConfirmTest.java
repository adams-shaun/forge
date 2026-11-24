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

import forge.game.zone.ZoneType;

/**
 * An xproof Forge decision_shape cluster (Bruna, Light of Alabaster). Its
 * attack trigger ends in an Optional$ hidden-origin ChangeZone ("any number of
 * Aura cards that could enchant it from your graveyard and/or hand", ChangeNum$
 * CountAuras). With no such Aura card gorge poses nothing (a choice of zero
 * cards) and the driver answers Forge's "Search your hand and graveyard?" no;
 * with an Aura card in the hand gorge would have posed it, so the same
 * unanswered confirm stays a strict miss.
 */
public class ZeroCandidateConfirmTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ZeroCandidateConfirmTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("zerocandidate").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("zerocandidate").resolve(name);
    }

    private static ResultRow replay(String name, StepMachine[] mach) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        mach[0] = m;
        return m.run();
    }

    @Test
    public void emptyHandAndGraveyardAurasAnswersNo() throws Exception {
        StepMachine[] m = new StepMachine[1];
        ResultRow row = replay("bruna-empty-hand.jsonl", m);

        assertNull(row.harness, "replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        assertTrue(row.notes.stream().anyMatch(n -> n.startsWith("zero-candidate confirm step 10: no")),
                "the zero-candidate confirm is declined, notes: " + row.notes);
        assertEquals(m[0].card("p0:Cowed by Wisdom").getAttachedTo().getName(), "Bruna, Light of Alabaster",
                "gorge's yes attached the Aura on the battlefield to Bruna");
    }

    @Test
    public void auraInHandStaysStrictMiss() throws Exception {
        StepMachine[] m = new StepMachine[1];
        ResultRow row = replay("bruna-aura-in-hand.jsonl", m);

        String miss = row.strictMiss != null ? row.strictMiss : row.harness;
        assertNotNull(miss, "an Aura card in the hand keeps the confirm a strict miss");
        assertTrue(miss.contains("Missing yesno answer at step 10: confirm ChangeZoneGeneral"), miss);
        assertTrue(m[0].card("p0:Pacifism").isInZone(ZoneType.Hand), "the Aura card stayed in the hand");
    }
}
