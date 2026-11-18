// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A 3- and 4-seat Commander pod through the whole-game driver: every seat is
 * a Forge AI with its commander (and partner) seated in the command zone at
 * 40 life (CR 903.6, 903.7), and the game is held to its max_turns cap,
 * reported as an unfinished row rather than run to the end or thrown away.
 */
public class GameReplayCommanderTest {
    static final String ISAMARU = "Isamaru, Hound of Konda";
    static final String ROGRAKH = "Rograkh, Son of Rohgahh";
    static final String ARDENN = "Ardenn, Intrepid Archaeologist";
    static final int MAX_TURNS = 6;

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static String seat(int i, String commander, String partner) {
        return "{\"name\":\"p" + i + "\",\"commander\":\"" + commander + "\""
                + (partner == null ? "" : ",\"partner\":\"" + partner + "\"")
                + ",\"cards\":[{\"name\":\"Plains\",\"count\":12},{\"name\":\"Mountain\",\"count\":8},"
                + "{\"name\":\"Savannah Lions\",\"count\":5},{\"name\":\"Raging Goblin\",\"count\":5}]}";
    }

    private static String pod(String id, int n) {
        StringBuilder b = new StringBuilder("{\"id\":\"" + id + "\",\"format\":\"commander\",\"seed\":11,"
                + "\"max_turns\":" + MAX_TURNS + ",\"max_ms\":20000,\"seats\":[");
        for (int i = 0; i < n; i++) {
            b.append(i == 0 ? "" : ",").append(i == 1 ? seat(i, ROGRAKH, ARDENN) : seat(i, ISAMARU, null));
        }
        return b.append("]}").toString();
    }

    private static JsonObject play(String id, int n) {
        JsonObject row = new GameReplay("test").replayLine(pod(id, n));
        String harness = row.has("harness") ? row.get("harness").getAsString() : "";
        // The only harness note a capped pod may carry is the cap itself.
        assertTrue(harness.isEmpty() || harness.equals("game did not finish within max_turns " + MAX_TURNS),
                harness + " " + (row.has("notes") ? row.get("notes").getAsString() : ""));
        assertEquals(row.get("format").getAsString(), "commander");
        JsonArray seats = row.getAsJsonArray("seats");
        assertEquals(seats.size(), n);
        for (int i = 0; i < n; i++) {
            JsonObject s = seats.get(i).getAsJsonObject();
            assertEquals(s.get("name").getAsString(), "p" + i);
            assertEquals(names(s.getAsJsonArray("commanders")), i == 1 ? List.of(ROGRAKH, ARDENN) : List.of(ISAMARU));
            assertTrue(s.get("life").getAsInt() <= 40 && s.get("life").getAsInt() > 0, "CR 903.7 " + s);
        }
        int turns = row.get("turns").getAsInt();
        assertTrue(turns >= 1 && turns <= MAX_TURNS + 1, "turns " + turns);
        if (!row.get("over").getAsBoolean()) {
            assertEquals(row.get("reason").getAsString(), "unfinished");
            assertEquals(row.get("winner_seat").getAsInt(), -1);
        }
        assertFalse(row.get("game_sha").getAsString().isEmpty());
        return row;
    }

    private static List<String> names(JsonArray a) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonElement e : a) {
            out.add(e.getAsString());
        }
        return out;
    }

    @Test
    public void fourSeatPodPlaysUnderItsCaps() {
        String sha = play("pod4", 4).get("game_sha").getAsString();
        // A pod is reproducible from its request: the same line, the same game.
        assertEquals(play("pod4", 4).get("game_sha").getAsString(), sha);
    }

    @Test
    public void threeSeatPodPlaysUnderItsCaps() {
        play("pod3", 3);
    }
}
