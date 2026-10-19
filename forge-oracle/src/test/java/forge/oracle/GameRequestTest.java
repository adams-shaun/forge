// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** GameRequest parsing: the whole-game driver's line grammar, and the two
 * properties that keep a batch honest -- a deck is exactly what it names, and
 * a malformed line still carries its id into its harness row. */
public class GameRequestTest {
    private static final String TWO_SEATS =
            "{\"id\":\"g0\",\"seats\":[{\"cards\":[{\"name\":\"Mountain\",\"count\":3}]},"
                    + "{\"cards\":[{\"name\":\"Forest\",\"count\":2},{\"name\":\"Bear\",\"count\":1}]}]}";

    @Test
    public void inlineCardsExpandInOrder() {
        GameRequest r = GameRequest.parse(TWO_SEATS);
        assertEquals(r.id, "g0");
        assertEquals(r.seats.size(), 2);
        assertEquals(r.seats.get(0).deck.size(), 3);
        assertEquals(r.seats.get(0).deck.get(0), "Mountain");
        assertEquals(r.seats.get(1).deck.size(), 3);
        assertEquals(r.seats.get(1).deck.get(2), "Bear");
        // Defaults: a seat name, a turn cap, a wall cap, and the CRC seed.
        assertEquals(r.seats.get(0).name, "p0");
        assertEquals(r.maxTurns, 60);
        assertEquals(r.maxMs, 60_000L);
        assertEquals(r.seed, ForgeEngine.seed("g0"));
    }

    @Test
    public void explicitSeedAndCapsWin() {
        GameRequest r = GameRequest.parse("{\"id\":\"x\",\"seed\":7,\"max_turns\":9,\"max_ms\":1234,"
                + "\"seats\":[{\"name\":\"ai\",\"cards\":[{\"name\":\"Island\"}]},"
                + "{\"cards\":[{\"name\":\"Island\"}]}]}");
        assertEquals(r.seed, 7L);
        assertEquals(r.maxTurns, 9);
        assertEquals(r.maxMs, 1234L);
        assertEquals(r.seats.get(0).name, "ai");
        // A count is optional and means one.
        assertEquals(r.seats.get(0).deck.size(), 1);
    }

    @Test
    public void seedsDifferPerId() {
        // The seed is what makes a batch reproducible and games independent.
        assertNotEquals(ForgeEngine.seed("g0"), ForgeEngine.seed("g1"));
    }

    @Test
    public void rejectsWrongSeatCount() {
        expect("{\"id\":\"a\",\"seats\":[{\"cards\":[{\"name\":\"Island\"}]}]}", "two seats");
        expect("{\"id\":\"a\",\"seats\":[]}", "two seats");
    }

    @Test
    public void rejectsEmptyAndBadDecks() {
        expect("{\"id\":\"a\",\"seats\":[{\"cards\":[]},{\"cards\":[{\"name\":\"Island\"}]}]}", "empty deck");
        expect("{\"id\":\"a\",\"seats\":[{\"cards\":[{\"name\":\"Island\",\"count\":0}]},"
                + "{\"cards\":[{\"name\":\"Island\"}]}]}", "count 0");
        expect("{\"id\":\"a\",\"seats\":[{\"cards\":[{\"count\":1}]},{\"cards\":[{\"name\":\"Island\"}]}]}",
                "no name");
        expect("{\"id\":\"a\",\"seats\":[{\"cards\":[{\"name\":\"Island\"}]},{\"cards\":[{\"name\":\"Island\"}]}],"
                + "\"max_turns\":0}", "max_turns must be positive");
    }

    @Test
    public void malformedLineKeepsItsIdForTheHarnessRow() {
        // A row a person has to read must say which game failed; the id is
        // recovered from the raw line exactly as ScenarioReplay recovers it.
        String line = "{\"id\":\"g42\",\"seats\":}";
        try {
            GameRequest.parse(line);
            fail("malformed JSON parsed");
        } catch (RuntimeException expected) {
            assertEquals(GameReplay.idOf(line), "g42");
        }
        JsonObject row = GameReplay.harnessRow(GameReplay.idOf(line), new IllegalArgumentException("bad"));
        assertEquals(row.get("id").getAsString(), "g42");
        assertTrue(row.has("harness"));
        assertEquals(row.get("engine").getAsString(), ResultRow.ENGINE);
    }

    private static void expect(String line, String want) {
        try {
            GameRequest.parse(line);
            fail("parsed a request it should have refused: " + line);
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            assertTrue(m.contains(want), "message " + m + " should contain " + want);
        }
    }
}
