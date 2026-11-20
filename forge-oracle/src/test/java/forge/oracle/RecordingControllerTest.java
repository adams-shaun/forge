// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A recorded 2-seat Forge AI game writes a {@code forge-shadow-1} log (see
 * {@link ShadowLog} for the line grammar): a header first, the opening
 * shuffles, who starts, mulligans, asks, snapshots and an end last; and the
 * same request with the same seed writes the same bytes. Shock gives the
 * game targets; Raging Goblin gives it attacks and blocks.
 */
public class RecordingControllerTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static String request(long seed, String extra) {
        String deck = "{\"cards\":[{\"name\":\"Mountain\",\"count\":17},{\"name\":\"Shock\",\"count\":8},"
                + "{\"name\":\"Raging Goblin\",\"count\":15}]}";
        return "{\"id\":\"rec/" + seed + "\",\"seed\":" + seed + ",\"max_turns\":30,\"max_ms\":60000" + extra
                + ",\"seats\":[" + deck + "," + deck + "]}";
    }

    private static String record(String line) {
        StringWriter w = new StringWriter();
        JsonObject row = new GameReplay("deadbeef").play(GameRequest.parse(line), w);
        assertTrue(row.get("over").getAsBoolean(), "the game did not finish: " + row);
        return w.toString();
    }

    private static List<JsonObject> lines(String log) {
        List<JsonObject> out = new ArrayList<>();
        for (String l : log.split("\n")) {
            assertFalse(l.isBlank(), "a blank line in the log");
            out.add(JsonParser.parseString(l).getAsJsonObject());
        }
        return out;
    }

    private static List<JsonObject> ofType(List<JsonObject> ls, String type) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject o : ls) {
            if (o.get("t").getAsString().equals(type)) {
                out.add(o);
            }
        }
        return out;
    }

    @Test
    public void aRecordedGameWritesHeaderAsksAndEnd() {
        List<JsonObject> ls = lines(record(request(7, "")));
        JsonObject h = ls.get(0);
        assertEquals(h.get("t").getAsString(), "header");
        assertEquals(h.get("format").getAsString(), ShadowLog.FORMAT);
        assertEquals(h.get("forge_ref").getAsString(), "deadbeef");
        assertEquals(h.get("seed").getAsLong(), 7L);
        assertEquals(h.get("max_turns").getAsInt(), 30);
        assertEquals(h.get("max_ms").getAsLong(), 60000L);
        assertEquals(h.get("game_format").getAsString(), "constructed");
        assertEquals(h.getAsJsonArray("seats").size(), 2);
        Set<Integer> ids = new HashSet<>();
        for (int s = 0; s < 2; s++) {
            JsonObject seat = h.getAsJsonArray("seats").get(s).getAsJsonObject();
            assertEquals(seat.get("seat").getAsInt(), s);
            assertEquals(seat.get("policy").getAsString(), "ai");
            assertEquals(seat.getAsJsonArray("cards").size(), 40, "the whole library, in build order");
            assertEquals(seat.getAsJsonArray("commanders").size(), 0, "a Constructed game has none");
            for (JsonElement c : seat.getAsJsonArray("cards")) {
                assertTrue(ids.add(c.getAsJsonObject().get("id").getAsInt()), "a Forge id twice");
            }
        }
        // The opening shuffles (one per seat) follow the header, then start.
        for (int i = 1; i <= 2; i++) {
            assertEquals(ls.get(i).get("t").getAsString(), "shuffle");
            assertEquals(ls.get(i).get("seat").getAsInt(), i - 1);
            assertEquals(ls.get(i).getAsJsonArray("order").size(), 40);
            for (JsonElement id : ls.get(i).getAsJsonArray("order")) {
                assertTrue(ids.contains(id.getAsInt()), "a shuffled id not in the header");
            }
        }
        assertEquals(ls.get(3).get("t").getAsString(), "start");
        assertTrue(ofType(ls, "mull").size() >= 2, "every seat keeps or mulligans");

        List<JsonObject> asks = ofType(ls, "ask");
        Set<String> kinds = new HashSet<>();
        for (int i = 0; i < asks.size(); i++) {
            JsonObject a = asks.get(i);
            assertEquals(a.get("n").getAsInt(), i + 1, "asks are numbered from 1 in log order");
            for (String k : new String[] {"seat", "turn", "step", "kind", "policy", "options", "answer"}) {
                assertTrue(a.has(k), "ask lacks " + k + ": " + a);
            }
            kinds.add(a.get("kind").getAsString());
        }
        assertTrue(kinds.contains("priority"), kinds.toString());
        assertTrue(kinds.contains("attack"), kinds.toString());
        // A cast Shock is answered by card id with its target and the land it tapped.
        boolean shock = false;
        for (JsonObject a : asks) {
            JsonObject ans = a.getAsJsonObject("answer");
            if (a.get("kind").getAsString().equals("priority") && ans.has("label")
                    && ans.get("label").getAsString().equals("Cast Shock") && ans.get("played").getAsBoolean()) {
                assertEquals(ans.get("action").getAsString(), "cast");
                assertTrue(ids.contains(ans.get("card").getAsInt()));
                assertEquals(ans.getAsJsonArray("targets").size(), 1, "one target");
                assertEquals(ans.getAsJsonArray("target_slots").get(0).getAsInt(), 1, "one slot holding it");
                assertEquals(ans.getAsJsonArray("tapped").size(), 1, "one Mountain tapped for {R}");
                assertTrue(ans.get("index").getAsInt() >= 0, "Forge's pick is on the offered list");
                shock = true;
            }
        }
        assertTrue(shock, "no Shock was cast");
        assertFalse(ofType(ls, "snap").isEmpty(), "no step-start snapshot");
        JsonObject end = ls.get(ls.size() - 1);
        assertEquals(end.get("t").getAsString(), "end");
        assertEquals(end.get("asks").getAsInt(), asks.size());
        assertEquals(ofType(ls, "end").size(), 1);
        assertEquals(ofType(ls, "header").size(), 1);
    }

    /** A step's snapshot is the state its first ask sees: after the turn-based
     * actions, so a draw-step snap holds the card just drawn (the farm's
     * 4-seat pods diverged from gorge at their first t1:draw snap, Forge one
     * card short, while it snapped at the step's start). */
    @Test
    public void aStepSnapFollowsTheTurnBasedActions() {
        List<JsonObject> ls = lines(record(request(7, "")));
        int draws = 0;
        for (int i = 0; i < ls.size(); i++) {
            JsonObject o = ls.get(i);
            if (!o.get("t").getAsString().equals("snap")) {
                continue;
            }
            JsonObject s = o.getAsJsonObject("snapshot");
            assertTrue(i + 1 < ls.size(), "a snap last");
            JsonObject next = ls.get(i + 1);
            assertEquals(next.get("t").getAsString(), "ask", "a snap not right before its step's first ask: " + s.get("checkpoint"));
            assertEquals(next.get("turn").getAsInt(), s.get("turn").getAsInt());
            assertEquals(next.get("step").getAsString(), s.get("step").getAsString());
            if (!s.get("step").getAsString().equals("draw")) {
                continue;
            }
            // The upkeep snap of the same turn (no upkeep effects in these decks)
            // has the active seat's library one card larger.
            int active = s.get("active").getAsInt();
            for (int j = i - 1; j >= 0; j--) {
                JsonObject p = ls.get(j);
                if (p.get("t").getAsString().equals("snap")) {
                    JsonObject ps = p.getAsJsonObject("snapshot");
                    if (ps.get("turn").getAsInt() == s.get("turn").getAsInt() && ps.get("step").getAsString().equals("upkeep")) {
                        int before = ps.getAsJsonArray("players").get(active).getAsJsonObject().get("library_count").getAsInt();
                        int after = s.getAsJsonArray("players").get(active).getAsJsonObject().get("library_count").getAsInt();
                        assertEquals(after, before - 1, "the draw-step snap of turn " + s.get("turn") + " lacks the draw");
                        draws++;
                    }
                    break;
                }
            }
        }
        assertTrue(draws > 0, "no draw-step snap followed an upkeep snap");
    }

    @Test
    public void theSameSeedWritesTheSameBytes() {
        // Seed 1: seed 11's game outlasts max_turns on Card-Forge master of 2026-11-20.
        String a = record(request(1, ""));
        String b = record(request(1, ""));
        assertTrue(a.length() > 1000, "a real log");
        assertEquals(a, b);
    }

    @Test
    public void aRandomSeatPicksFromItsListedOptionsReproducibly() {
        String a = record(request(13, ",\"random_seat\":1"));
        assertEquals(a, record(request(13, ",\"random_seat\":1")));
        List<JsonObject> ls = lines(a);
        JsonObject h = ls.get(0);
        assertEquals(h.getAsJsonArray("seats").get(0).getAsJsonObject().get("policy").getAsString(), "ai");
        assertEquals(h.getAsJsonArray("seats").get(1).getAsJsonObject().get("policy").getAsString(), "random");
        int random = 0;
        for (JsonObject ask : ofType(ls, "ask")) {
            String pol = ask.get("policy").getAsString();
            if (ask.get("seat").getAsInt() == 0) {
                assertEquals(pol, "ai");
                continue;
            }
            if (pol.equals("random")) {
                random++;
                JsonObject ans = ask.getAsJsonObject("answer");
                if (ask.get("kind").getAsString().equals("priority") && !ans.get("action").getAsString().equals("pass")) {
                    int idx = ans.get("index").getAsInt();
                    assertTrue(idx >= 0 && idx < ask.getAsJsonArray("options").size(), "a random pick is a listed option");
                }
            }
        }
        assertTrue(random > 0, "the random seat made no random pick");
    }

    /** The keys every line of each kind carries in the reader's golden,
     * gorge-compliance oracle/xengine/fshadow/testdata/vanilla-2seat.jsonl
     * (branch xedh/3-fshadow, ae141344e2): the intersection over that kind's
     * golden lines, so a key the golden writes only sometimes (a mull's
     * bottom, a cast's tapped) is not required. "ask.K.option" and
     * "ask.K.answer" are an ask of kind K's options and answer. */
    private static final String[][] GOLDEN_KEYS = {
        {"header", "t", "format", "seed", "forge_ref", "game_format", "mulligans", "max_turns", "max_ms", "seats"},
        {"header.seat", "seat", "name", "cards"},
        {"header.card", "id", "name"},
        {"shuffle", "t", "seat", "order"},
        {"start", "t", "seat"},
        {"mull", "t", "seat", "keep"},
        {"ask", "t", "n", "seat", "turn", "step", "kind", "options", "answer"},
        {"ask.priority.option", "action", "card"},
        {"ask.priority.answer", "action"},
        {"ask.attack.option", "card", "player"},
        {"ask.attack.answer", "attacks"},
        {"attack", "card", "defender"},
        {"ask.block.option", "card", "attacker"},
        {"ask.block.answer", "blocks"},
        {"block", "card", "attacker"},
        {"snap", "t", "n", "snapshot"},
        {"snap.snapshot", "checkpoint", "turn", "step", "active", "priority", "over", "players", "permanents", "stack"},
        {"snap.player", "seat", "life", "hand", "graveyard", "exile", "command", "library_count", "library_top", "pool"},
        {"end", "t", "reason"},
    };

    private static void have(java.util.Map<String, Integer> seen, String kind, JsonObject o) {
        for (String[] want : GOLDEN_KEYS) {
            if (!want[0].equals(kind)) {
                continue;
            }
            seen.merge(kind, 1, Integer::sum);
            for (int i = 1; i < want.length; i++) {
                assertTrue(o.has(want[i]), kind + " lacks the reader's \"" + want[i] + "\": " + o);
            }
        }
    }

    @Test
    public void everyLineCarriesTheReadersGoldenKeys() {
        java.util.Map<String, Integer> seen = new java.util.TreeMap<>();
        // Seed 2: on Card-Forge master of 2026-11-20 seed 7's AI never offers a
        // block; seed 2's game reaches every key the reader checks.
        for (JsonObject o : lines(record(request(2, "")))) {
            String t = o.get("t").getAsString();
            have(seen, t, o);
            if (t.equals("header")) {
                for (JsonElement s : o.getAsJsonArray("seats")) {
                    have(seen, "header.seat", s.getAsJsonObject());
                    for (JsonElement c : s.getAsJsonObject().getAsJsonArray("cards")) {
                        have(seen, "header.card", c.getAsJsonObject());
                    }
                }
            } else if (t.equals("ask")) {
                String k = o.get("kind").getAsString();
                for (JsonElement op : o.getAsJsonArray("options")) {
                    have(seen, "ask." + k + ".option", op.getAsJsonObject());
                }
                JsonObject ans = o.getAsJsonObject("answer");
                have(seen, "ask." + k + ".answer", ans);
                for (String list : new String[] {"attacks", "blocks"}) {
                    if (ans.has(list)) {
                        for (JsonElement e : ans.getAsJsonArray(list)) {
                            have(seen, list.substring(0, list.length() - 1), e.getAsJsonObject());
                        }
                    }
                }
            } else if (t.equals("snap")) {
                JsonObject snap = o.getAsJsonObject("snapshot");
                have(seen, "snap.snapshot", snap);
                for (JsonElement pl : snap.getAsJsonArray("players")) {
                    have(seen, "snap.player", pl.getAsJsonObject());
                }
            }
        }
        for (String[] want : GOLDEN_KEYS) {
            assertTrue(seen.containsKey(want[0]), "the recorded game has no " + want[0] + " to check: " + seen);
        }
    }
}
