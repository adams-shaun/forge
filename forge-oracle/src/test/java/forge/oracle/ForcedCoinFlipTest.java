// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

import org.testng.annotations.Test;

import com.google.gson.JsonParser;

import forge.util.Aggregates;
import forge.util.MyRandom;

/**
 * The coin force is step-scoped and outcome-matched: a step armed with a
 * recorded flip returns the tape's result, a step with no record keeps
 * Forge's seeded generator, a forced flip (a FlipCoinMod static) spends no
 * generator draw, and a doubled flip spends one draw per result it took.
 * Without the force, Forge's FlipCoinEffect flips from its own stream (Mana
 * Clash loops a different number of times than gorge's).
 */
public class ForcedCoinFlipTest {

    private static Request req(String coinFlips) {
        return Request.parse("{\"id\":\"xe:flip\",\"item\":{"
                + "\"id\":\"xe:flip\","
                + "\"steps\":[{\"op\":\"pass\",\"seat\":0},{\"op\":\"activate\",\"seat\":0},"
                + "{\"op\":\"pass\",\"seat\":0}]"
                + (coinFlips.isEmpty() ? "" : ",\"coin_flips\":" + coinFlips)
                + "}}");
    }


    /** A sibling of the same seed with no armed flips: whatever the MyRandom
     * wrapper on the classpath does to an unarmed draw, both generators take
     * the identical route, so the pair compares the fall-through, not the
     * wrapper. */
    private static ForcedDieRandom sibling() {
        return new ForcedDieRandom(7, req(""));
    }

    @Test
    public void recordedFlipIsForcedOnItsStep() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"win\":true}]");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertTrue(r.nextBoolean(), "the recorded result (win = heads)");
        // The forced flip consumed the generator's nextBoolean, so the seeded
        // generator is still at its first value, exactly as a flip-free
        // replay would be.
        assertEquals(r.nextBoolean(), sibling().nextBoolean(), "unarmed draw falls through");
    }

    @Test
    public void stepWithoutAFlipKeepsForgeRng() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"win\":true}]");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(0);
        assertEquals(r.nextBoolean(), sibling().nextBoolean(), "step 0 has no recorded flip");
    }

    @Test
    public void aForcedFlipSpendsNoGeneratorDraw() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"win\":true,\"forced\":true}]");
        assertTrue(rq.coinFlips(1).isEmpty(), "a FlipCoinMod flip takes no generator result");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertEquals(r.nextBoolean(), sibling().nextBoolean(), "the forced flip drew nothing");
    }

    @Test
    public void aDoubledFlipSpendsOneDrawPerResult() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"win\":true,\"draws\":2}]");
        assertEquals(rq.coinFlips(1).size(), 2, "Krark's Thumb took two results");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertTrue(r.nextBoolean(), "the first drawn result");
        assertTrue(r.nextBoolean(), "the second drawn result: both the recorded outcome");
        assertEquals(r.nextBoolean(), sibling().nextBoolean(), "both consumed, then falls through");
    }

    @Test
    public void severalFlipsKeepLogOrderAndReArmIsANoOp() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"win\":false},{\"step\":1,\"seat\":0,\"win\":true}]");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        r.arm(1); // the priority callback re-enters; do not queue twice
        assertEquals(r.nextBoolean(), false, "the first recorded flip in log order");
        assertEquals(r.nextBoolean(), true, "the second recorded flip in log order");
        assertEquals(r.nextBoolean(), sibling().nextBoolean(), "both consumed, then falls through");
    }

    @Test
    public void malformedFlipsAreSkipped() {
        Request rq = req("[{\"step\":1},{\"step\":1,\"seat\":0,\"win\":\"heads\"},"
                + "{\"step\":1,\"seat\":0,\"win\":true}]");
        assertEquals(rq.coinFlips(1).size(), 1, "only the boolean flip is armed");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertTrue(r.nextBoolean());
    }

    @Test
    public void noCoinFlipsLeavesTheGeneratorUntouched() {
        Request rq = req("");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertEquals(r.nextBoolean(), new Random(7).nextBoolean());
        assertNotEquals(ForgeEngine.seed("a"), ForgeEngine.seed("b"), "the seed still names the scenario");
    }

    @Test
    public void productionSetRandomForwardsTheBoolean() {
        // ForgeEngine.replayOnce installs the generator with MyRandom.setRandom,
        // and Forge's FlipCoinEffect draws with MyRandom.getRandom().nextBoolean().
        // MyRandom's wrapper must reach that call to the installed generator
        // itself; routing it through next(bits) would re-derive the boolean
        // from the delegate's unbounded nextInt() and this force would never
        // run at the real call site.
        Random previous = MyRandom.getRandom();
        try {
            Request rq = req("[{\"step\":1,\"seat\":0,\"win\":true}]");
            ForcedDieRandom r = new ForcedDieRandom(7, rq);
            MyRandom.setRandom(r);
            r.arm(1);
            assertTrue(MyRandom.getRandom().nextBoolean(),
                    "the production MyRandom path must reach ForcedDieRandom.nextBoolean()");
            assertEquals(MyRandom.getRandom().nextBoolean(), sibling().nextBoolean(),
                    "the unarmed draw still falls through to the seeded generator");
        } finally {
            MyRandom.setRandom(previous);
        }
    }

    @Test
    public void anUnrelatedUnboundedDrawDoesNotSpendTheArmedFlip() {
        // Goblin Kaboomist's upkeep trigger makes a Land Mine token and then
        // flips. The token's art pick is Aggregates.random over a Set, which
        // draws an unbounded nextInt() per item before the flip is asked; an
        // armed flip those draws consumed left FlipCoinEffect to flip from the
        // seeded stream (Kaboomist survived a flip gorge lost).
        Random previous = MyRandom.getRandom();
        try {
            Request rq = req("[{\"step\":1,\"seat\":0,\"win\":false}]");
            ForcedDieRandom r = new ForcedDieRandom(7, rq);
            MyRandom.setRandom(r);
            r.arm(1);
            Aggregates.random(new LinkedHashSet<>(List.of("a", "b", "c")));
            assertFalse(MyRandom.getRandom().nextBoolean(),
                    "the recorded lost flip survives a set pick's unbounded draws");
        } finally {
            MyRandom.setRandom(previous);
        }
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(ForcedCoinFlipTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("coin").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("coin").resolve(name);
    }

    private static ResultRow replay(String name) throws Exception {
        Bootstrap.init(TestRes.res());
        Request rq;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            rq = Request.parse(in.readLine());
        }
        ResultRow row = new ForgeEngine().replayOnce(rq, true);
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        return row;
    }

    private static List<String> graveyard(ResultRow row, int seat) {
        com.google.gson.JsonObject last = row.snapshots.get(row.snapshots.size() - 1);
        List<String> out = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement pl : last.getAsJsonArray("players")) {
            com.google.gson.JsonObject o = pl.getAsJsonObject();
            if (o.get("seat").getAsInt() == seat) {
                for (com.google.gson.JsonElement c : o.getAsJsonArray("graveyard")) {
                    out.add(c.getAsString());
                }
            }
        }
        return out;
    }

    /** The xproof rng find xs:XP:goblin-kaboomist:f0t0:s2: the upkeep trigger
     * (DB$ FlipCoin | LoseSubAbility$ DmgSelf, 2 damage to the 1/2 itself)
     * resolves on step 37 after two mana activations in the same window. The
     * pair is win and lose of the same flip: an unforced flip is the same on
     * both rows whatever its seed, so a force that does not reach the flip
     * fails exactly one of them. */
    @Test
    public void aLostUpkeepFlipKillsGoblinKaboomist() throws Exception {
        assertEquals(graveyard(replay("goblin-kaboomist-upkeep-lose.jsonl"), 0), List.of("Goblin Kaboomist"),
                "the recorded lost flip deals the 2 damage");
    }

    @Test
    public void aWonUpkeepFlipSparesGoblinKaboomist() throws Exception {
        assertEquals(graveyard(replay("goblin-kaboomist-upkeep-win.jsonl"), 0), List.of(),
                "the recorded won flip deals no damage");
    }
}
