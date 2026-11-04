// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Setup states the board: a shock land's "may pay 2 life" entry replacement
 * asks the scripted seat an unless-pay question while the land is placed, and
 * setup answers it by paying, with no scripted answer and no miss. The
 * scenario's tapped, tapped_after_untap and life fields then state the board. Reads the card
 * scripts under forge.oracle.res. */
public class SetupUnlessPayTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static ResultRow replay(String tapped) {
        String line = "{\"abilities\":{},\"gorge_decisions\":[],\"id\":\"setup/shock\",\"item\":{\"id\":\"setup/shock\","
                + "\"card\":\"\",\"template\":\"setup\",\"name\":\"setup-shock\",\"setup\":{\"p0\":{\"battlefield\":"
                + "[\"Stomping Ground\"],\"life\":20" + tapped + "},\"p1\":{\"battlefield\":[\"Grizzly Bears\"]}},\"steps\":[]}}";
        return new ForgeEngine().replayOnce(Request.parse(line), true);
    }

    private static JsonObject snapshot(ResultRow r) {
        assertNull(r.harness, r.harness);
        assertNull(r.strictMiss, r.strictMiss);
        assertTrue(!r.snapshots.isEmpty(), "no snapshot");
        return r.snapshots.get(r.snapshots.size() - 1);
    }

    private static boolean landTapped(JsonObject snap) {
        for (JsonElement pe : snap.getAsJsonArray("players")) {
            JsonObject p = pe.getAsJsonObject();
            if (p.get("seat").getAsInt() != 0) {
                continue;
            }
            assertEquals(p.get("life").getAsInt(), 20);
        }
        for (JsonElement e : snap.getAsJsonArray("permanents")) {
            JsonObject c = e.getAsJsonObject();
            if (c.get("name").getAsString().equals("Stomping Ground")) {
                return c.get("tapped").getAsBoolean();
            }
        }
        throw new AssertionError("Stomping Ground is not on the battlefield");
    }

    @Test
    public void shockLandPlacedUntappedAnswersNoQuestionAndKeepsLife() {
        assertEquals(landTapped(snapshot(replay(""))), false);
    }

    @Test
    public void shockLandPlacedTappedIsTapped() {
        assertEquals(landTapped(snapshot(replay(",\"tapped_after_untap\":[\"Stomping Ground\"]"))), true);
    }
}
