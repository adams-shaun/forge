// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A {@code "format": "commander"} scenario (CR 903) as gorge's runner plays
 * it: 40 starting life, each seat's {@code command} cards seated as its
 * commanders in the command zone, castable from there with the commander
 * tax, and the snapshot's command zone, commander_casts and commander_damage
 * in gorge's OracleSnapPlayer shape. Isamaru, Hound of Konda is a {W}
 * vanilla legendary 2/2.
 */
public class CommanderTest {
    static final String ISAMARU = "Isamaru, Hound of Konda";

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static ResultRow replay(String id, String format, String p0, String p1, String steps) {
        String line = "{\"abilities\":{},\"gorge_decisions\":[],\"id\":\"" + id + "\",\"item\":{\"id\":\"" + id + "\","
                + "\"card\":\"\",\"template\":\"commander\",\"name\":\"" + id + "\"" + format
                + ",\"setup\":{\"p0\":{" + p0 + "},\"p1\":{" + p1 + "}},\"steps\":[" + steps + "]}}";
        return new ForgeEngine().replayOnce(Request.parse(line), true);
    }

    private static ResultRow commander(String id, String p0, String p1, String steps) {
        return replay(id, ",\"format\":\"commander\"", p0, p1, steps);
    }

    private static JsonObject last(ResultRow r) {
        assertNull(r.harness, r.harness + " " + r.notes);
        assertNull(r.strictMiss, r.strictMiss);
        assertFalse(r.snapshots.isEmpty(), "no snapshot");
        return r.snapshots.get(r.snapshots.size() - 1);
    }

    private static JsonObject player(JsonObject snap, int seat) {
        for (JsonElement e : snap.getAsJsonArray("players")) {
            if (e.getAsJsonObject().get("seat").getAsInt() == seat) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no seat " + seat);
    }

    private static String names(JsonArray a) {
        StringBuilder b = new StringBuilder();
        for (JsonElement e : a) {
            b.append(b.length() == 0 ? "" : "|").append(e.getAsString());
        }
        return b.toString();
    }

    private static final String CAST = "{\"op\":\"cast\",\"seat\":0,\"card\":\"p0:" + ISAMARU + "\",\"mana\":\"W\"}";

    @Test
    public void setupSeatsCommandersAtFortyLife() {
        JsonObject s = last(commander("cmdr/setup", "\"command\":[\"" + ISAMARU + "\"],\"hand\":[\"Plains\"]",
                "\"command\":[\"" + ISAMARU + "\"]", ""));
        for (int seat = 0; seat < 2; seat++) {
            JsonObject p = player(s, seat);
            assertEquals(p.get("life").getAsInt(), 40, "CR 903.7");
            assertEquals(names(p.getAsJsonArray("command")), ISAMARU, "the command zone holds the commander only, not Forge's effect card");
            assertFalse(p.has("commander_casts"), "no cast yet");
            assertFalse(p.has("commander_damage"), "no damage yet");
        }
        assertEquals(names(player(s, 0).getAsJsonArray("hand")), "Plains");
    }

    @Test
    public void castFromTheCommandZoneCountsTheTax() {
        ResultRow r = commander("cmdr/cast", "\"command\":[\"" + ISAMARU + "\"]", "", CAST);
        JsonObject s = last(r);
        JsonObject p0 = player(s, 0);
        assertEquals(p0.getAsJsonArray("command").size(), 0, "the commander left the command zone");
        assertEquals(p0.getAsJsonObject("commander_casts").get(ISAMARU).getAsInt(), 1, "CR 903.8");
        JsonArray stack = s.getAsJsonArray("stack");
        assertEquals(stack.size(), 1);
        assertTrue(stack.get(0).toString().contains(ISAMARU), stack.toString());
    }

    @Test
    public void recastPaysTheTax() {
        String again = "{\"op\":\"resolve\",\"seat\":0},{\"op\":\"move\",\"seat\":0,\"card\":\"p0:" + ISAMARU
                + "\",\"to\":\"command\"},";
        ResultRow unpaid = commander("cmdr/tax-unpaid", "\"command\":[\"" + ISAMARU + "\"]", "", CAST + "," + again + CAST);
        assertNotNull(unpaid.harness, "the second cast costs {2} more (CR 903.8)");
        assertTrue(unpaid.harness.contains("step 3"), unpaid.harness);

        String paid = CAST.replace("\"mana\":\"W\"", "\"mana\":\"WCC\"");
        JsonObject s = last(commander("cmdr/tax-paid", "\"command\":[\"" + ISAMARU + "\"]", "", CAST + "," + again + paid));
        assertEquals(player(s, 0).getAsJsonObject("commander_casts").get(ISAMARU).getAsInt(), 2);
        assertEquals(player(s, 0).get("pool").getAsString(), "");
    }

    @Test
    public void setupSeedsTaxAndDamage() {
        JsonObject s = last(commander("cmdr/seed",
                "\"battlefield\":[\"" + ISAMARU + "\"],\"commanders\":[\"" + ISAMARU + "\"],\"commander_casts\":{\"" + ISAMARU + "\":2}",
                "\"commander_damage\":{\"p0:" + ISAMARU + "\":5}", ""));
        JsonObject p0 = player(s, 0);
        assertEquals(p0.getAsJsonArray("command").size(), 0, "a commanders entry stays where it is placed");
        assertEquals(p0.getAsJsonObject("commander_casts").get(ISAMARU).getAsInt(), 2);
        JsonObject p1 = player(s, 1);
        assertEquals(p1.getAsJsonObject("commander_damage").get("p0:" + ISAMARU).getAsInt(), 5);
        assertEquals(p1.get("life").getAsInt(), 40, "setup commander damage changes no life");
    }

    @Test
    public void combatDamageFromACommanderIsTallied() {
        String steps = "{\"op\":\"attack\",\"seat\":0,\"attackers\":[\"p0:" + ISAMARU + "\"],\"defender\":\"p1\"},"
                + "{\"op\":\"pass_to\",\"seat\":0,\"step\":\"main2\",\"active\":\"p0\"}";
        JsonObject s = last(commander("cmdr/damage",
                "\"battlefield\":[\"" + ISAMARU + "\"],\"commanders\":[\"" + ISAMARU + "\"]", "", steps));
        JsonObject p1 = player(s, 1);
        assertEquals(p1.get("life").getAsInt(), 38);
        assertEquals(p1.getAsJsonObject("commander_damage").get("p0:" + ISAMARU).getAsInt(), 2, "CR 903.10a");
    }

    private static ResultRow dies(String id, String pickKind) {
        String line = "{\"abilities\":{},\"gorge_decisions\":[{\"step\":0,\"seat\":0,\"kind\":\"yesno\",\"gorge_kind\":\"commander_zone\","
                + "\"picks\":[\"x\"],\"pick_kinds\":[\"" + pickKind + "\"]}],\"id\":\"" + id + "\",\"item\":{\"id\":\"" + id + "\","
                + "\"card\":\"\",\"template\":\"commander\",\"name\":\"" + id + "\",\"format\":\"commander\","
                + "\"setup\":{\"p0\":{\"battlefield\":[\"" + ISAMARU + "\"],\"commanders\":[\"" + ISAMARU + "\"]},\"p1\":{}},"
                + "\"steps\":[{\"op\":\"move\",\"seat\":0,\"card\":\"p0:" + ISAMARU + "\",\"to\":\"graveyard\"}]}}";
        return new ForgeEngine().replayOnce(Request.parse(line), true);
    }

    @Test
    public void aDeadCommanderReturnsAsGorgeAnswered() {
        JsonObject back = player(last(dies("cmdr/dies-return", "command_zone")), 0);
        assertEquals(names(back.getAsJsonArray("command")), ISAMARU, "CR 903.9a: the owner put it into the command zone");
        assertEquals(back.getAsJsonArray("graveyard").size(), 0);
        JsonObject left = player(last(dies("cmdr/dies-leave", "leave")), 0);
        assertEquals(left.getAsJsonArray("command").size(), 0);
        assertEquals(names(left.getAsJsonArray("graveyard")), ISAMARU, "the owner left it in the graveyard");
    }

    @Test
    public void constructedPutsCommandCardsThereWithoutCommanderRules() {
        JsonObject s = last(replay("cmdr/constructed", "", "\"command\":[\"" + ISAMARU + "\"]", "", ""));
        assertEquals(player(s, 0).get("life").getAsInt(), 20);
        assertEquals(names(player(s, 0).getAsJsonArray("command")), ISAMARU);
    }

    @Test
    public void commanderFieldsNeedTheCommanderFormat() {
        ResultRow r = replay("cmdr/constructed-commanders", "",
                "\"hand\":[\"" + ISAMARU + "\"],\"commanders\":[\"" + ISAMARU + "\"]", "", "");
        assertNotNull(r.harness);
        assertTrue(r.harness.contains("commander-format"), r.harness);
    }

    @Test
    public void unknownFormatIsAHarnessRow() {
        ResultRow r = new ScenarioReplay(new ForgeEngine(), "").replayLine("{\"id\":\"cmdr/bad\",\"format\":\"brawl\",\"setup\":{},\"steps\":[]}");
        assertNotNull(r.harness);
        assertTrue(r.harness.contains("unknown format"), r.harness);
    }
}
