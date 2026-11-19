// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.Random;

import org.testng.annotations.Test;

import com.google.gson.JsonParser;

/**
 * The die force is step-scoped and side-matched: a step armed with a recorded
 * d6 returns the tape's natural result, a step with no record keeps Forge's
 * seeded generator, and a roll armed for one side is not spent by a different
 * draw. Without the force, a follower rolls its own die (Ebony Fly animates to
 * a different P/T than gorge's).
 */
public class ForcedDieRollTest {

    private static Request req(String dieRolls) {
        return Request.parse("{\"id\":\"xe:die\",\"item\":{"
                + "\"id\":\"xe:die\","
                + "\"steps\":[{\"op\":\"pass\",\"seat\":0},{\"op\":\"activate\",\"seat\":0},"
                + "{\"op\":\"pass\",\"seat\":0}]"
                + (dieRolls.isEmpty() ? "" : ",\"die_rolls\":" + dieRolls)
                + "}}");
    }

    @Test
    public void recordedD6IsForcedOnItsStep() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"sides\":6,\"natural\":3,\"result\":3}]");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertEquals(r.nextInt(6) + 1, 3, "the recorded natural roll");
        // The forced roll consumed no draw, so the seeded generator is still
        // at its first value, exactly as a roll-free replay would be.
        assertEquals(r.nextInt(6), new Random(7).nextInt(6), "unarmed draw falls through");
    }

    @Test
    public void stepWithoutARollKeepsForgeRng() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"sides\":6,\"natural\":3,\"result\":3}]");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(0);
        assertEquals(r.nextInt(6), new Random(7).nextInt(6), "step 0 has no recorded roll");
    }

    @Test
    public void aRollIsNotSpentByADifferentSide() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"sides\":20,\"natural\":17,\"result\":17}]");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertEquals(r.nextInt(6), new Random(7).nextInt(6), "a d6 draw does not consume the d20");
        assertEquals(r.nextInt(20) + 1, 17, "the d20 rolls its recorded natural");
    }

    @Test
    public void severalRollsKeepLogOrderAndReArmIsANoOp() {
        Request rq = req("[{\"step\":1,\"seat\":0,\"sides\":6,\"natural\":3,\"result\":3},"
                + "{\"step\":1,\"seat\":0,\"sides\":6,\"natural\":5,\"result\":5}]");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        r.arm(1); // the priority callback re-enters; do not queue twice
        assertEquals(r.nextInt(6) + 1, 3);
        assertEquals(r.nextInt(6) + 1, 5);
        assertEquals(r.nextInt(6), new Random(7).nextInt(6), "both rolls consumed, then falls through");
    }

    @Test
    public void malformedRollsAreSkipped() {
        Request rq = req("[{\"step\":1,\"sides\":6},{\"step\":1,\"seat\":0,\"sides\":6,\"natural\":9},"
                + "{\"step\":1,\"seat\":0,\"sides\":6,\"natural\":3,\"result\":3}]");
        assertTrue(rq.dieRolls(1).size() == 1, "only the in-range roll is armed");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertEquals(r.nextInt(6) + 1, 3);
    }

    @Test
    public void noDieRollsLeavesTheGeneratorUntouched() {
        Request rq = req("");
        ForcedDieRandom r = new ForcedDieRandom(7, rq);
        r.arm(1);
        assertEquals(r.nextInt(100), new Random(7).nextInt(100));
        assertNotEquals(ForgeEngine.seed("a"), ForgeEngine.seed("b"), "the seed still names the scenario");
    }
}
