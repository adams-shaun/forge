// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.MyRandom;

/**
 * The whole-game driver: Forge's AI plays Forge's AI, headless, one JSON line
 * per game.
 *
 * <pre>
 *   GameReplay &lt;in.jsonl&gt; &lt;out.jsonl&gt; [--res DIR]
 * </pre>
 *
 * This exists to give gorge an independent opponent. It is the same JVM and
 * the same {@link Bootstrap} the scenario oracle uses; the difference is that
 * a scenario scripts gorge's decisions and asks Forge to confirm them, while a
 * game here lets Forge's own controllers play both seats.
 *
 * One line in, one row out, in input order; ANY throwable while handling one
 * line becomes that line's harness row and the loop goes on (the same rule
 * {@link ScenarioReplay} follows). A row carries the outcome and a
 * {@code game_sha} over the deterministic part, so a re-run of the same input
 * compares byte for byte while {@code ms} does not.
 */
public final class GameReplay {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String forgeRef;

    public GameReplay(String forgeRef) {
        this.forgeRef = forgeRef == null ? "" : forgeRef;
    }

    /** One line in, one row out; never throws. */
    public JsonObject replayLine(String line) {
        GameRequest req;
        try {
            req = GameRequest.parse(line);
        } catch (Throwable t) {
            JsonObject row = harnessRow(idOf(line), t);
            row.addProperty("request_sha", sha256(line));
            return row;
        }
        JsonObject row;
        try {
            row = play(req);
        } catch (Throwable t) {
            row = harnessRow(req.id, t);
        }
        row.addProperty("request_sha", sha256(line));
        return row;
    }

    /** Builds both decks, then hands the game to Forge's own loop. */
    private JsonObject play(GameRequest req) {
        MyRandom.setRandom(new Random(req.seed));

        List<RegisteredPlayer> players = new ArrayList<>();
        List<BridgeLobbyPlayer> bridges = new ArrayList<>();
        List<Integer> bridgeSeats = new ArrayList<>();
        for (int i = 0; i < req.seats.size(); i++) {
            GameRequest.Seat s = req.seats.get(i);
            LobbyPlayerAi lobby;
            if (s.bridge.isEmpty()) {
                lobby = new LobbyPlayerAi(s.name, null);
            } else {
                // Each bridged seat gets its own rng stream, derived from the
                // game seed and the seat index exactly as bench derives a
                // seat's policy seed, so a match is reproducible.
                BridgeLobbyPlayer b = new BridgeLobbyPlayer(s.name, i, s.bridge, s.bridgeArgs, req.seed ^ (i + 1));
                bridges.add(b);
                bridgeSeats.add(i);
                lobby = b;
            }
            players.add(new RegisteredPlayer(buildDeck(s)).setPlayer(lobby));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "game");
        Game game = new Game(players, rules, match);
        // The live player list shrinks when a seat loses; keep our own refs.
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        Player[] seatRefs = {p0, p1};

        long start = System.nanoTime();
        Deadline deadline = new Deadline(start, req.maxMs, req.maxTurns, game);
        try {
            match.startGame(game, deadline::check);
            deadline.check();
        } finally {
            for (BridgeLobbyPlayer b : bridges) {
                b.close();
            }
        }

        if (System.getenv("BRIDGE_GAMELOG") != null) {
            // Forge's own game log, oldest first, for diagnosing a bridged
            // game (env-gated; never part of a row).
            var entries = new ArrayList<>(game.getGameLog().getLogEntries(null));
            java.util.Collections.reverse(entries);
            for (var e : entries) {
                System.err.println("GAMELOG " + e.type() + " " + e.message());
            }
        }
        JsonObject row = new JsonObject();
        row.addProperty("engine", ResultRow.ENGINE);
        row.addProperty("forge_ref", forgeRef);
        if (req.id != null) {
            row.addProperty("id", req.id);
        }
        row.addProperty("seed", req.seed);

        JsonArray seats = new JsonArray();
        for (int i = 0; i < 2; i++) {
            Player p = seatRefs[i];
            JsonObject so = new JsonObject();
            so.addProperty("name", req.seats.get(i).name);
            so.addProperty("life", p.getLife());
            so.addProperty("library", p.getCardsIn(ZoneType.Library).size());
            so.addProperty("lost", p.hasLost());
            seats.add(so);
        }
        row.add("seats", seats);

        boolean over = game.isGameOver();
        row.addProperty("over", over);
        row.addProperty("turns", game.getPhaseHandler().getTurn());
        if (over) {
            GameEndReason reason = game.getOutcome().getWinCondition();
            row.addProperty("reason", reason == null ? "" : reason.name());
            RegisteredPlayer win = game.getOutcome().getWinningPlayer();
            int seat = -1;
            if (win != null) {
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).getPlayer() == win.getPlayer()) {
                        seat = i;
                        break;
                    }
                }
            }
            row.addProperty("winner_seat", seat);
        } else {
            row.addProperty("reason", "unfinished");
            row.addProperty("winner_seat", -1);
            row.addProperty("harness", "game did not finish within "
                    + (deadline.why != null ? deadline.why : "the caps"));
        }
        // A bridged seat that fell back to Forge's AI on some question is
        // reported, never hidden: the number says how much of the match the
        // bridge actually played.
        JsonArray bridgeFallbacks = new JsonArray();
        for (int bi = 0; bi < bridges.size(); bi++) {
            BridgeLobbyPlayer b = bridges.get(bi);
            if (b.controller() == null) {
                continue;
            }
            JsonObject fo = new JsonObject();
            fo.addProperty("seat", bridgeSeats.get(bi));
            JsonObject by = new JsonObject();
            for (var e : b.controller().fallbacks().entrySet()) {
                by.addProperty(e.getKey(), e.getValue());
            }
            fo.add("by_method", by);
            bridgeFallbacks.add(fo);
        }
        row.add("bridge_fallbacks", bridgeFallbacks);
        // How much of the match the bridge answered: asks per kind and the
        // option counts. Diagnostics only; not part of game_sha.
        JsonArray bridgeAsks = new JsonArray();
        for (int bi = 0; bi < bridges.size(); bi++) {
            BridgeLobbyPlayer b = bridges.get(bi);
            if (b.controller() == null) {
                continue;
            }
            JsonObject ao = b.controller().askStats();
            ao.addProperty("seat", bridgeSeats.get(bi));
            bridgeAsks.add(ao);
        }
        row.add("bridge_asks", bridgeAsks);
        // Every decision Forge posed to each bridged seat, by controller
        // method: {bridged, fallback[, nested]}. Diagnostics only; not part
        // of game_sha.
        JsonArray coverage = new JsonArray();
        for (int bi = 0; bi < bridges.size(); bi++) {
            BridgeLobbyPlayer b = bridges.get(bi);
            if (b.controller() == null) {
                continue;
            }
            JsonObject co = new JsonObject();
            co.addProperty("seat", bridgeSeats.get(bi));
            co.add("by_method", b.controller().decisionCoverage());
            coverage.add(co);
        }
        row.add("decision_coverage", coverage);
        row.addProperty("ms", (System.nanoTime() - start) / 1_000_000);
        row.addProperty("game_sha", gameSha(row));
        return row;
    }

    /** A deck built strictly: every name must resolve to a paper card. A name
     * that does not is the row's failure, named, so a run never silently
     * substitutes (Bootstrap's card DB is the same one the oracle uses). */
    private static Deck buildDeck(GameRequest.Seat seat) {
        Deck d = new Deck(seat.name);
        for (String name : seat.deck) {
            PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
            if (pc == null) {
                throw new IllegalArgumentException("card not found: " + name);
            }
            d.getMain().add(pc, 1);
        }
        return d;
    }

    /** The wall/turn/turn-count cap, checked from Forge's start hook and after
     * {@code startGame}. Forge's own loop does not call back per step, so the
     * wall cap is enforced at the only boundary the driver controls; a game
     * that overruns is reported, not killed (Forge has no safe abort). */
    private static final class Deadline {
        final long start;
        final long maxMs;
        final int maxTurns;
        final Game game;
        String why;

        Deadline(long start, long maxMs, int maxTurns, Game game) {
            this.start = start;
            this.maxMs = maxMs;
            this.maxTurns = maxTurns;
            this.game = game;
        }

        void check() {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > maxMs) {
                why = "max_ms " + maxMs;
                throw new IllegalStateException("game exceeded max_ms " + maxMs + " (" + ms + " ms)");
            }
            int t = game.getPhaseHandler().getTurn();
            if (t > maxTurns) {
                why = "max_turns " + maxTurns;
                throw new IllegalStateException("game exceeded max_turns " + maxTurns + " (turn " + t + ")");
            }
        }
    }

    /** The deterministic tuple: seed, reason, winner seat, turns and every
     * seat's life/library. Wall time is deliberately excluded. */
    static String gameSha(JsonObject row) {
        StringBuilder b = new StringBuilder();
        b.append(row.get("seed").getAsLong()).append('|');
        b.append(row.get("reason").getAsString()).append('|');
        b.append(row.get("winner_seat").getAsInt()).append('|');
        b.append(row.get("turns").getAsInt()).append('|');
        for (var e : row.getAsJsonArray("seats")) {
            JsonObject s = e.getAsJsonObject();
            b.append(s.get("life").getAsInt()).append('/').append(s.get("library").getAsInt()).append(';');
        }
        return sha256(b.toString()).substring(0, 16);
    }

    /** The id a malformed line still carries, so its row is identifiable. */
    static String idOf(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"id\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    static JsonObject harnessRow(String id, Throwable t) {
        JsonObject o = new JsonObject();
        o.addProperty("engine", ResultRow.ENGINE);
        if (id != null) {
            o.addProperty("id", id);
        }
        o.addProperty("harness", ResultRow.message(t));
        o.addProperty("notes", "trace: " + ResultRow.trace(t, 8));
        return o;
    }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replays every non-blank line, writing one row each, flushed per row. */
    public static int replayLines(GameReplay r, BufferedReader in, PrintWriter out) throws IOException {
        int n = 0;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            out.println(GSON.toJson(r.replayLine(line)));
            out.flush();
            n++;
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: GameReplay <in.jsonl> <out.jsonl> [--res DIR]");
            System.exit(2);
        }
        String res = System.getenv().getOrDefault("FORGE_RES", "forge-gui/res");
        String ref = System.getenv().getOrDefault("FORGE_ORACLE_REF", "");
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--res": res = args[++i]; break;
                default: throw new IllegalArgumentException("unknown flag " + args[i]);
            }
        }
        long t0 = System.nanoTime();
        Bootstrap.init(res);
        long tBoot = System.nanoTime();
        GameReplay r = new GameReplay(ref);
        int n;
        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(w)) {
            try (BufferedReader in = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8)) {
                n = replayLines(r, in, out);
            }
        }
        long tEnd = System.nanoTime();
        System.err.printf("GameReplay: %d games bootstrap_ms=%d total_ms=%d%n", n,
                (tBoot - t0) / 1_000_000, (tEnd - t0) / 1_000_000);
        System.exit(0);
    }
}
