// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One input line of the whole-game driver: two decks and the caps that keep a
 * Forge AI game from running away.
 *
 * <pre>
 * {"id": "...",
 *  "seats": [{"name": "p0", "cards": [{"name": "Lightning Bolt", "count": 4}]},
 *            {"cards": [{"name": "Mountain", "count": 20}, ...]}],
 *  "max_turns": 60, "max_ms": 60000, "seed": 12345,
 *  "shadow_log": "out/g1.jsonl", "random_seat": 1}
 * </pre>
 *
 * {@code shadow_log} (optional) is the path a {@link ShadowLog}
 * ({@code forge-shadow-1}) is written to: every seat without a bridge is then
 * a {@link RecordingController}. {@code random_seat} (optional, needs
 * {@code shadow_log}) is the one seat that answers in random mode; the caller
 * rotates it by seed.
 *
 * {@code "format": "commander"} makes it a Commander pod of 2 to 4 seats
 * (CR 903): each seat names its {@code commander} and, for a partner pair,
 * its {@code partner}; those go to the command zone and the seat starts at 40
 * life. A deck file's {@code commander} is the seat's commander there, not a
 * library card. Without a format (or with {@code "constructed"}) the request
 * is exactly two seats and names no commander.
 *
 * {@code seed} is optional; absent, it is the CRC32 of {@code id} (the same
 * scheme {@link ForgeEngine#seed} uses), so a batch is reproducible from its
 * id alone. {@code max_turns} is the phase-handler turn cap; {@code max_ms}
 * is a wall-clock cap enforced between game steps, because Forge's AI can
 * spend unbounded time on a large board.
 *
 * Unlike the scenario harness, a deck here is real card names with counts:
 * every name must resolve or the row fails naming it (no Wastes filler --
 * a game that silently substituted cards would be a measurement of nothing).
 */
public final class GameRequest {
    public final String id;
    public final List<Seat> seats;
    public final int maxTurns;
    public final long maxMs;
    public final long seed;
    public final String name;
    /** The forge-shadow-1 log path, or "" for none. */
    public final String shadowLog;
    /** The random-mode seat, or -1 for none. */
    public final int randomSeat;
    /** "constructed" or "commander". */
    public final String format;

    /** One seat's deck: card names with counts, in deck order. A seat with a
     * bridge binary is played by gorge's bot over stdio; otherwise by Forge's
     * AI. */
    public static final class Seat {
        public final String name;
        public final List<String> deck;
        public final String bridge;
        /** Extra arguments for the bridge binary (for example
         * ["-policy", "forge-heuristic"]), passed before its -seed. */
        public final List<String> bridgeArgs;
        /** The seat's commanders, in order (commander, then partner); empty
         * outside the commander format. */
        public final List<String> commanders;

        Seat(String name, List<String> deck, String bridge, List<String> bridgeArgs, List<String> commanders) {
            this.name = name;
            this.deck = Collections.unmodifiableList(deck);
            this.bridge = bridge;
            this.bridgeArgs = Collections.unmodifiableList(bridgeArgs);
            this.commanders = Collections.unmodifiableList(commanders);
        }
    }

    private GameRequest(String id, List<Seat> seats, int maxTurns, long maxMs, long seed, String name, String format,
            String shadowLog, int randomSeat) {
        this.id = id;
        this.seats = Collections.unmodifiableList(seats);
        this.maxTurns = maxTurns;
        this.maxMs = maxMs;
        this.seed = seed;
        this.name = name == null ? "" : name;
        this.shadowLog = shadowLog;
        this.randomSeat = randomSeat;
        this.format = format;
    }

    /** Whether this is a Commander pod. */
    public boolean commander() {
        return FORMAT_COMMANDER.equals(format);
    }

    static final String FORMAT_CONSTRUCTED = "constructed";
    static final String FORMAT_COMMANDER = "commander";
    /** CR 903 plays with any number of players; the pod runner seats at most four. */
    static final int MAX_COMMANDER_SEATS = 4;

    /** Parses one line. Throws on JSON that is not an object or a spec the
     * driver cannot run (wrong seat count, an empty deck). */
    public static GameRequest parse(String line) {
        JsonElement e = JsonParser.parseString(line);
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("request line is not a JSON object");
        }
        JsonObject o = e.getAsJsonObject();
        String id = str(o, "id");
        String format = strOr(o, "format", FORMAT_CONSTRUCTED);
        if (!format.equals(FORMAT_CONSTRUCTED) && !format.equals(FORMAT_COMMANDER)) {
            throw new IllegalArgumentException("unknown format " + format);
        }
        boolean commander = format.equals(FORMAT_COMMANDER);
        List<Seat> seats = new ArrayList<>();
        JsonElement se = o.get("seats");
        if (se == null || !se.isJsonArray()) {
            throw new IllegalArgumentException("request has no seats array");
        }
        int i = 0;
        for (JsonElement s : se.getAsJsonArray()) {
            if (!s.isJsonObject()) {
                throw new IllegalArgumentException("seat " + i + " is not an object");
            }
            JsonObject so = s.getAsJsonObject();
            List<String> commanders = new ArrayList<>();
            List<String> deck = deck(so, i, commander ? commanders : null);
            if (deck.isEmpty()) {
                throw new IllegalArgumentException("seat " + i + " has an empty deck");
            }
            List<String> bridgeArgs = new java.util.ArrayList<>();
            if (so.has("bridge_args")) {
                for (var arg : so.getAsJsonArray("bridge_args")) {
                    bridgeArgs.add(arg.getAsString());
                }
            }
            for (String k : new String[] {"commander", "partner"}) {
                String c = str(so, k);
                if (c.isEmpty()) {
                    continue;
                }
                if (!commander) {
                    throw new IllegalArgumentException("seat " + i + " names a " + k + "; that needs format commander");
                }
                if (!commanders.contains(c)) {
                    commanders.add(c);
                }
            }
            if (commander && commanders.isEmpty()) {
                throw new IllegalArgumentException("seat " + i + " has no commander");
            }
            seats.add(new Seat(strOr(so, "name", "p" + i), deck, str(so, "bridge"), bridgeArgs, commanders));
            i++;
        }
        if (commander) {
            if (seats.size() < 2 || seats.size() > MAX_COMMANDER_SEATS) {
                throw new IllegalArgumentException("a commander pod needs 2 to " + MAX_COMMANDER_SEATS
                        + " seats, got " + seats.size());
            }
        } else if (seats.size() != 2) {
            throw new IllegalArgumentException("two seats are required, got " + seats.size());
        }
        int maxTurns = intOr(o, "max_turns", 60);
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("max_turns must be positive");
        }
        long maxMs = longOr(o, "max_ms", 60_000L);
        if (maxMs <= 0) {
            throw new IllegalArgumentException("max_ms must be positive");
        }
        long seed = o.has("seed") ? o.get("seed").getAsLong() : ForgeEngine.seed(id);
        int randomSeat = intOr(o, "random_seat", -1);
        if (randomSeat < -1 || randomSeat >= seats.size()) {
            throw new IllegalArgumentException("random_seat " + randomSeat + " is not a seat");
        }
        return new GameRequest(id, seats, maxTurns, maxMs, seed, str(o, "name"), format, str(o, "shadow_log"), randomSeat);
    }

    /** A seat's deck: either an inline {@code cards} array
     * ({@code [{"name","count"}]}) or a {@code deck} path to that same JSON,
     * which is gorge's {@code testkit/testutil/decks/*.json} shape (its
     * {@code commander}, when present, is appended as a one-of, or in the
     * commander format goes to {@code commanders} instead). */
    private static List<String> deck(JsonObject seat, int idx, List<String> commanders) {
        List<String> out = new ArrayList<>();
        JsonElement cards = seat.get("cards");
        if (cards != null && cards.isJsonArray()) {
            addCards(cards.getAsJsonArray(), out, idx);
            return out;
        }
        JsonElement path = seat.get("deck");
        if (path == null || path.isJsonPrimitive()) {
            if (path == null) {
                throw new IllegalArgumentException("seat " + idx + " has neither cards nor deck");
            }
            return deckFile(path.getAsString(), idx, commanders);
        }
        throw new IllegalArgumentException("seat " + idx + " deck is not a path");
    }

    private static List<String> deckFile(String path, int idx, List<String> commanders) {
        java.nio.file.Path p = java.nio.file.Path.of(path);
        String text;
        try {
            text = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("seat " + idx + " deck " + path + " cannot be read: " + e.getMessage());
        }
        JsonElement e = JsonParser.parseString(text);
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("seat " + idx + " deck " + path + " is not a JSON object");
        }
        JsonObject o = e.getAsJsonObject();
        JsonElement cards = o.get("cards");
        if (cards == null || !cards.isJsonArray()) {
            throw new IllegalArgumentException("seat " + idx + " deck " + path + " has no cards array");
        }
        List<String> out = new ArrayList<>();
        addCards(cards.getAsJsonArray(), out, idx);
        String cmd = str(o, "commander");
        if (!cmd.isEmpty()) {
            if (commanders != null) {
                commanders.add(cmd);
            } else {
                out.add(cmd);
            }
        }
        return out;
    }

    private static void addCards(JsonArray cards, List<String> out, int idx) {
        for (JsonElement c : cards) {
            if (!c.isJsonObject()) {
                throw new IllegalArgumentException("seat " + idx + " card entry is not an object");
            }
            JsonObject co = c.getAsJsonObject();
            String name = str(co, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("seat " + idx + " card entry has no name");
            }
            int count = co.has("count") ? co.get("count").getAsInt() : 1;
            if (count <= 0) {
                throw new IllegalArgumentException("seat " + idx + " card " + name + " has count " + count);
            }
            for (int i = 0; i < count; i++) {
                out.add(name);
            }
        }
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    static String strOr(JsonObject o, String k, String dflt) {
        String s = str(o, k);
        return s.isEmpty() ? dflt : s;
    }

    static int intOr(JsonObject o, String k, int dflt) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? dflt : e.getAsInt();
    }

    static long longOr(JsonObject o, String k, long dflt) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? dflt : e.getAsLong();
    }
}
