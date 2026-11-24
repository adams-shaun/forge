// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
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
 * The xproof harness cluster for cast_mode "suspend": Lotus Bloom (no mana
 * cost, Suspend 3--{0}) in p0's hand, step 0 a cast with cast_mode suspend,
 * then passes. Suspend from hand is a special action (CR 702.62a, 116.2f): it
 * uses no stack and exiles the card with three time counters. The driver used
 * to pick the ordinary cast and then insist the card be on the stack ("was
 * not put on the stack").
 */
public class SuspendFromHandTest {

    private static Path fixture(String name) {
        try {
            Path p = Path.of(SuspendFromHandTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("suspend").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("suspend").resolve(name);
    }

    @Test
    public void suspendFromHandExilesWithTimeCounters() throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture("lotus-bloom.jsonl"), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        Card c = m.card("p0:Lotus Bloom");
        assertTrue(c.isInZone(ZoneType.Exile), "Lotus Bloom is exiled");
        assertEquals(c.getCounters(CounterEnumType.TIME), 3, "Lotus Bloom is suspended with three time counters");
        assertTrue(m.game.getStack().isEmpty(), "suspend uses no stack");
        assertEquals(m.seats[0].getCardsIn(ZoneType.Hand).size(), 0, "the card left the hand");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("suspend step 0: p0:Lotus Bloom exiled with 3 time counters")),
                "the suspend is noted, notes: " + m.notes);
    }
}
