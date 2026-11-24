// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.oracle.DecisionQueue.Decision;

/**
 * CR 702.66a Delve: gorge poses a choose whose every pick kind is "exile";
 * the driver used to return an empty collection, so Forge paid pool-only and
 * refused every Delve cast. A cast through the real engine now consumes the
 * picks and leaves exactly those cards in exile, one distinct graveyard card
 * per pick even when the picks share a name.
 */
public class DelvePicksTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    /** Dig Through Time {6}{U}{U}, pool UU: the six generic are delved. */
    private static String request(String id, String grave, String picks, String refs, int kinds) {
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < kinds; i++) {
            k.append(i == 0 ? "" : ",").append("\"exile\"");
        }
        return "{\"id\":\"" + id + "\",\"item\":{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"turn\":1,"
                + "\"setup\":{\"p0\":{\"hand\":[\"Dig Through Time\"],\"graveyard\":[" + grave + "],\"life\":20},"
                + "\"p1\":{\"life\":20}},"
                + "\"steps\":[{\"op\":\"mana\",\"seat\":0,\"mana\":\"UU\"},"
                + "{\"op\":\"cast\",\"seat\":0,\"card\":\"p0:Dig Through Time\"}]},"
                + "\"gorge_decisions\":[{\"step\":1,\"seat\":0,\"kind\":\"choose_n\",\"gorge_kind\":\"choose\","
                + "\"picks\":[" + picks + "],\"pick_refs\":[" + refs + "],"
                + "\"pick_kinds\":[" + k + "]}],\"abilities\":{}}";
    }

    private static ResultRow replay(String req) {
        return new ForgeEngine().replayOnce(Request.parse(req), true);
    }

    private static JsonObject lastSnapshot(ResultRow r) {
        assertNull(r.harness, r.harness);
        assertNull(r.strictMiss, r.strictMiss);
        assertTrue(!r.snapshots.isEmpty(), "no snapshot");
        return r.snapshots.get(r.snapshots.size() - 1);
    }

    private static JsonObject p0(JsonObject snap) {
        return snap.getAsJsonArray("players").get(0).getAsJsonObject();
    }

    private static List<String> names(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) {
            out.add(e.getAsString());
        }
        return out;
    }

    @Test
    public void sixPicksExileExactlyThoseCards() {
        String picks = "\"Mountain\",\"Forest\",\"Plains\",\"Swamp\",\"Island\",\"Grizzly Bears\"";
        String refs = "\"p0:Mountain\",\"p0:Forest\",\"p0:Plains\",\"p0:Swamp\",\"p0:Island\",\"p0:Grizzly Bears\"";
        ResultRow r = replay(request("delve/distinct", picks + ",\"Divination\"", picks, refs, 6));
        JsonObject snap = lastSnapshot(r);
        assertEquals(snap.get("checkpoint").getAsString(), "step 1 (cast)");
        JsonObject p = p0(snap);
        assertEquals(names(p.getAsJsonArray("exile")),
                List.of("Forest", "Grizzly Bears", "Island", "Mountain", "Plains", "Swamp"),
                "the picked cards, and only those, are exiled");
        assertEquals(names(p.getAsJsonArray("graveyard")), List.of("Divination"), "the unpicked card stays");
        assertEquals(snap.getAsJsonArray("stack").size(), 1, "Dig Through Time reached the stack");
        assertEquals(snap.getAsJsonArray("stack").get(0).getAsJsonObject().get("source").getAsString(),
                "Dig Through Time");
        assertTrue(r.notes.contains("delve step 1: 6 exiled"), r.notes.toString());
    }

    @Test
    public void sameNamedCopiesConsumeOneEachAndCapAtTheGenericCost() {
        // Seven Islands in the graveyard, seven picks: Dig Through Time has six
        // generic mana, so the seventh pick is over the cap. Each pick still
        // consumes its own copy, leaving exactly one Island behind.
        String grave = "\"Island\",\"Island\",\"Island\",\"Island\",\"Island\",\"Island\",\"Island\"";
        String picks = grave;
        String refs = "\"p0:Island#1\",\"p0:Island#2\",\"p0:Island#3\",\"p0:Island#4\","
                + "\"p0:Island#5\",\"p0:Island#6\",\"p0:Island#7\"";
        ResultRow r = replay(request("delve/same-name", grave, picks, refs, 7));
        JsonObject p = p0(lastSnapshot(r));
        assertEquals(names(p.getAsJsonArray("exile")),
                List.of("Island", "Island", "Island", "Island", "Island", "Island"));
        assertEquals(names(p.getAsJsonArray("graveyard")), List.of("Island"));
        assertTrue(r.notes.contains("delve step 1: 6 exiled"), r.notes.toString());
    }

    @Test
    public void aPickNotInTheGraveyardMissesLoudly() {
        String grave = "\"Island\",\"Island\",\"Island\",\"Island\",\"Island\",\"Island\"";
        String picks = "\"Island\",\"Island\",\"Island\",\"Mountain\",\"Island\",\"Island\"";
        String refs = "\"p0:Island#1\",\"p0:Island#2\",\"p0:Island#3\",\"p0:Mountain\","
                + "\"p0:Island#4\",\"p0:Island#5\"";
        ResultRow r = replay(request("delve/missing", grave, picks, refs, 6));
        assertTrue(r.harness != null && r.harness.startsWith("Missing delve answer at step 1"),
                "want a strict delve miss, got " + r.harness);
        assertTrue(r.harness.contains("p0:Mountain is not in the graveyard"), r.harness);
    }

    @Test
    public void onlyAnAllExileChooseIsADelvePick() {
        Decision exile = decision("{\"step\":1,\"seat\":0,\"kind\":\"choose_n\",\"gorge_kind\":\"choose\","
                + "\"picks\":[\"Island\",\"Island\"],\"pick_refs\":[\"p0:Island#1\",\"p0:Island#2\"],"
                + "\"pick_kinds\":[\"exile\",\"exile\"]}");
        assertTrue(DecisionQueue.isDelvePick(exile));
        Decision convoke = decision("{\"step\":1,\"seat\":0,\"kind\":\"choose_n\","
                + "\"picks\":[\"Island\"],\"pick_refs\":[\"p0:Island#1\"],\"pick_kinds\":[\"convoke_generic\"]}");
        assertTrue(!DecisionQueue.isDelvePick(convoke), "convoke is a different payment");
        Decision mixed = decision("{\"step\":1,\"seat\":0,\"kind\":\"choose_n\","
                + "\"picks\":[\"Island\",\"Forest\"],\"pick_refs\":[\"p0:Island#1\",\"p0:Forest#1\"],"
                + "\"pick_kinds\":[\"exile\",\"convoke_generic\"]}");
        assertTrue(!DecisionQueue.isDelvePick(mixed), "every pick kind must be exile");
        // gorge also uses "exile" as an arrange's unpicked-card destination,
        // which is an order, not the Delve choose.
        Decision arrange = decision("{\"step\":1,\"seat\":0,\"kind\":\"order\","
                + "\"gorge_kind\":\"arrange\",\"picks\":[\"Island\"],"
                + "\"pick_refs\":[\"p0:Island#1\"],\"pick_kinds\":[\"exile\"]}");
        assertTrue(!DecisionQueue.isDelvePick(arrange), "an arrange's exile remainder is not Delve");
    }

    private static Decision decision(String json) {
        List<JsonObject> raw = new ArrayList<>();
        raw.add(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
        return new DecisionQueue(raw).all().get(0);
    }
}
