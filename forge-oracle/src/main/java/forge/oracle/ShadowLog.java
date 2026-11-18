// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventFlipCoin;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventRollDie;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;

/**
 * The {@code forge-shadow-1} decision log of one Forge-hosted game: what a
 * follower engine (gorge-compliance {@code oracle/xengine/fshadow}, whose
 * {@code log.go} has one Go type per line) needs to replay the game Forge
 * played, decision by decision, without Forge. Every seat is a
 * {@link RecordingController}; they share one log per game.
 *
 * <p>JSONL, one object per line, UTF-8, keys in the order listed, written by
 * Gson with no HTML escaping, so the same request and seed write the same
 * bytes. Every line has {@code t}, its type. Fields marked + are Forge
 * extras the reader may ignore. A <b>ref</b> is {@code {"card": id}},
 * {@code {"player": seat}} or {@code {"spell": id}} (+: a spell on the stack,
 * by its host card's id). Seats are request order (0-based). Card ids are
 * Forge's, stable for a card's whole game; a token's id first appears in an
 * ask or a snap. Library orders are top card first.
 *
 * <ul>
 * <li>{@code header} (always line 1): {@code format} ("forge-shadow-1"),
 *   {@code seed}, {@code forge_ref}, {@code game_format} ("constructed" or
 *   "commander"), {@code mulligans} (the most London mulligans Forge allows a
 *   seat: its hand size), {@code max_turns}, {@code max_ms}, {@code seats}:
 *   [{@code seat}, {@code name}, {@code cards}: [{@code id}, {@code name}]
 *   the library in build order (as Forge created it, before any shuffle)
 *   followed by the commanders, {@code commanders}: [indices into cards],
 *   {@code policy}+ ("ai" or "random")], {@code id}+ (the request id),
 *   {@code free_mulligan}+ (Forge's first mulligan is free: more than two
 *   seats).</li>
 * <li>{@code shuffle}: {@code seat}, {@code order}: [card ids] the library
 *   after the shuffle. One per seat at the opening, one per mulligan, one per
 *   later shuffle, in game order.</li>
 * <li>{@code start}: {@code seat} who takes the first turn.</li>
 * <li>{@code mull}: {@code seat}, {@code keep} (bool), {@code bottom} (a keep
 *   after a mulligan: the card ids put on the bottom, in the order Forge put
 *   them there), {@code policy}+, {@code hand}+ (the hand Forge decided on;
 *   Forge bottoms the cards of a London mulligan right after drawing, before
 *   it decides, so a kept hand here has already lost its bottom cards).</li>
 * <li>{@code ask}: {@code n} (1-based, asks only, in log order),
 *   {@code seat}, {@code turn}, {@code step} (gorge's step name, "" before
 *   turn 1), {@code kind}, {@code options}, {@code answer}, {@code policy}+
 *   ("ai" or "random": who answered). By kind:
 *   <ul>
 *   <li>{@code priority}: options are Forge's playable list (the list
 *     {@link BridgeController} offers), pass not included: [{@code action}
 *     ("land", "cast", "activate", or "other"+), {@code card} (the host),
 *     {@code ability} (index in the host's {@code getSpellAbilities()};
 *     absent for a land), {@code index}+, {@code label}+, {@code mode}+,
 *     {@code mana_cost}+]. answer: {@code action} ("pass" or an option's),
 *     {@code card}, {@code ability}, {@code tapped} ([card ids] tapped while
 *     it was played, in tap order: mana sources and {T} costs),
 *     {@code targets} ([refs], every targeting part of the chain in chain
 *     order), {@code index}+ (the offered option, -1 when Forge played
 *     something the list lacks), {@code label}+, {@code mode}+,
 *     {@code target_slots}+ (how many of targets each targeting part holds),
 *     {@code played}+ (Forge's playChosenSpellAbility result),
 *     {@code count}+ (Forge played more than one; only the first is
 *     described). An ask made while a spell is being played (a target, a
 *     sacrifice) follows that spell's priority line.</li>
 *   <li>{@code attack}: options, one per legal (attacker, defender) pair:
 *     [{@code card}, {@code player}] or [{@code card},
 *     {@code defender_card}+] for a planeswalker or battle; answer
 *     {@code attacks}: [{@code card}, {@code defender} (seat; -1 with
 *     {@code defender_card}+ for a permanent)].</li>
 *   <li>{@code block}: options [{@code card} (blocker), {@code attacker}]
 *     every legal pair; answer {@code blocks}: [{@code card},
 *     {@code attacker}].</li>
 *   <li>{@code target} (a target choice outside a priority pick, e.g. a
 *     trigger): options, every candidate listed before Forge chose: a ref
 *     with {@code slot}+; answer {@code targets} [refs], {@code source}+
 *     (the host card), {@code label}+, {@code ok}+ (bool).</li>
 *   </ul></li>
 * <li>{@code rand}: {@code kind} ("coin" or "die"), {@code bound} (2 for a
 *   coin, the die's sides), {@code value} (coin: 1 heads, 0 tails; die:
 *   1..bound), {@code seat}+ (coin only: the flipper). The starting-player
 *   toss is not a rand: {@code start} carries it.</li>
 * <li>{@code snap}: {@code n} (1-based, snaps only), {@code snapshot} (a
 *   {@link SnapshotWriter#snapshot}, gorge's OracleSnapshot shape, checkpoint
 *   "t&lt;turn&gt;:&lt;step&gt;"), written just before a step's first ask when
 *   the stack is empty then: after the step's turn-based actions (the draw,
 *   CR 504.1) and before anyone acts, the point gorge's follower stands at.
 *   A step that asks nothing (untap, cleanup) has no snap.</li>
 * <li>{@code end} (always last): {@code winner} (seat; absent for a draw or
 *   an unfinished game), {@code reason} (Forge's GameEndReason name, or
 *   "unfinished"), {@code over}+, {@code turns}+, {@code asks}+ (the number
 *   of ask lines), {@code harness}+ (why the game stopped early).</li>
 * </ul>
 *
 * <p>Not yet recorded: Forge's other controller questions (modes, generic
 * choices, optional costs, orderings) are answered by the AI and leave no
 * line; random picks inside effects ({@code AtRandom}) are not a rand line;
 * a mulligan Forge does not offer (no {@code canMulligan}) leaves no mull.
 */
public final class ShadowLog {
    public static final String FORMAT = "forge-shadow-1";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Writer out;
    private final JsonObject header;
    private final String id;
    private final String[] names;
    private final String[] policies;
    private final JsonArray[] decks;
    private Game game;
    private Player[] seats;
    private boolean headed;
    private final List<JsonObject> preamble = new ArrayList<>();
    private int hold;
    private final List<JsonObject> held = new ArrayList<>();
    private int asks;
    private int snaps;
    private List<Integer> taps;
    private RuntimeException error;
    /** The checkpoint of the step whose snapshot waits for that step's first
     * ask, or null. GameEventTurnPhase fires before the step's turn-based
     * actions, so a snapshot taken there missed the draw-step draw (every
     * 4-seat pod's first snap diverged from gorge on the active seat's hand). */
    private String pendingSnap;

    /** A log for one game; {@link #bind} it to the game before it starts.
     * {@code gameFormat} is "constructed" or "commander". */
    public ShadowLog(Writer out, GameRequest req, String forgeRef, String gameFormat, int randomSeat) {
        this.out = out;
        int n = req.seats.size();
        names = new String[n];
        policies = new String[n];
        decks = new JsonArray[n];
        for (int i = 0; i < n; i++) {
            names[i] = req.seats.get(i).name;
            policies[i] = i == randomSeat ? "random" : "ai";
        }
        id = req.id == null ? "" : req.id;
        header = line("header");
        header.addProperty("format", FORMAT);
        header.addProperty("seed", req.seed);
        header.addProperty("forge_ref", forgeRef);
        header.addProperty("game_format", gameFormat);
        header.addProperty("mulligans", 7);
        header.addProperty("max_turns", req.maxTurns);
        header.addProperty("max_ms", req.maxMs);
    }

    /** True for the game this log records (not an AI simulation's copy). */
    boolean records(Game g) {
        return game == null || game == g;
    }

    /** Binds the log to the game and its seats, in request order. */
    public void bind(Game g, Player[] seatRefs) {
        this.game = g;
        this.seats = seatRefs.clone();
        g.subscribeToEvents(this);
    }

    int seatOf(Player p) {
        for (int i = 0; i < seats.length; i++) {
            if (seats[i] == p) {
                return i;
            }
        }
        return -1;
    }

    private int seatOf(PlayerView v) {
        for (int i = 0; i < seats.length; i++) {
            if (seats[i].getView() == v || (v != null && seats[i].getId() == v.getId())) {
                return i;
            }
        }
        return -1;
    }

    // ---- lines ------------------------------------------------------------

    /** The library after a shuffle; the first shuffle of a seat also fixes
     * its header cards from the library as built. */
    void shuffle(int seat, Iterable<Card> before, Iterable<Card> after) {
        if (decks[seat] == null) {
            JsonArray d = new JsonArray();
            for (Card c : before) {
                d.add(idName(c));
            }
            decks[seat] = d;
        }
        JsonObject o = line("shuffle");
        o.addProperty("seat", seat);
        o.add("order", ids(after));
        emit(o);
    }

    /** One keep-or-mulligan answer; {@code bottom} is null for none. */
    void mull(int seat, boolean keep, Iterable<Card> bottom, Iterable<Card> hand) {
        JsonObject o = line("mull");
        o.addProperty("seat", seat);
        o.addProperty("keep", keep);
        if (bottom != null) {
            o.add("bottom", ids(bottom));
        }
        o.addProperty("policy", policies[seat]);
        o.add("hand", ids(hand));
        emit(o);
    }

    /** An ask line; its {@code n} is assigned when it is written, and its
     * answer object may still be filled in while the log is held. */
    JsonObject ask(int seat, String kind, String policy, JsonArray options, JsonObject answer) {
        flushSnap();
        JsonObject o = line("ask");
        o.addProperty("n", 0);
        o.addProperty("seat", seat);
        PhaseHandler ph = game.getPhaseHandler();
        o.addProperty("turn", ph.getTurn());
        o.addProperty("step", SnapshotWriter.stepName(ph.getPhase()));
        o.addProperty("kind", kind);
        o.add("options", options);
        o.add("answer", answer);
        o.addProperty("policy", policy);
        emit(o);
        return o;
    }

    /** The current step's checkpoint name, "t&lt;turn&gt;:&lt;step&gt;". */
    private String checkpoint() {
        PhaseHandler ph = game.getPhaseHandler();
        return "t" + ph.getTurn() + ":" + SnapshotWriter.stepName(ph.getPhase());
    }

    /** Writes the pending step snapshot, once, before the step's first ask,
     * if the stack is empty and the game is still in that step. */
    private void flushSnap() {
        String cp = pendingSnap;
        pendingSnap = null;
        if (cp == null || !game.getStack().isEmpty() || !cp.equals(checkpoint())) {
            return;
        }
        JsonObject o = line("snap");
        o.addProperty("n", 0);
        o.add("snapshot", SnapshotWriter.snapshot(cp, game, seats));
        emit(o);
    }

    /** Holds every line until the matching {@link #release}: a priority line
     * is completed (targets, taps) only after Forge played the pick. */
    void hold() {
        hold++;
    }

    void release() {
        if (hold > 0 && --hold == 0) {
            flushHeld();
        }
    }

    private void flushHeld() {
        List<JsonObject> ls = new ArrayList<>(held);
        held.clear();
        for (JsonObject o : ls) {
            write(o);
        }
    }

    /** Starts collecting the ids of cards that become tapped. */
    void startTaps() {
        taps = new ArrayList<>();
    }

    JsonArray stopTaps() {
        JsonArray a = new JsonArray();
        if (taps != null) {
            for (int t : taps) {
                a.add(t);
            }
        }
        taps = null;
        return a;
    }

    /** The last line. Flushes anything still held or unwritten. */
    public void end(Game g, List<RegisteredPlayer> players, String harness) {
        if (!headed) {
            writeHeader();
        }
        hold = 0;
        flushHeld();
        JsonObject o = line("end");
        boolean over = g.isGameOver();
        String reason = "unfinished";
        if (over && g.getOutcome() != null) {
            GameEndReason r = g.getOutcome().getWinCondition();
            reason = r == null ? "" : r.name();
            RegisteredPlayer win = g.getOutcome().getWinningPlayer();
            if (win != null) {
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).getPlayer() == win.getPlayer()) {
                        o.addProperty("winner", i);
                        break;
                    }
                }
            }
        }
        o.addProperty("reason", reason);
        o.addProperty("over", over);
        o.addProperty("turns", g.getPhaseHandler().getTurn());
        o.addProperty("asks", asks);
        String why = harness;
        if (why == null && error != null) {
            why = "recorder: " + ResultRow.message(error);
        }
        if (why != null) {
            o.addProperty("harness", why);
        }
        write(o);
    }

    /** The first error a game-event handler hit (the event bus swallows it). */
    RuntimeException error() {
        return error;
    }

    private static JsonObject line(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    private void emit(JsonObject o) {
        if (!headed) {
            preamble.add(o);
        } else if (hold > 0) {
            held.add(o);
        } else {
            write(o);
        }
    }

    private void write(JsonObject o) {
        String t = o.get("t").getAsString();
        if (t.equals("ask")) {
            o.addProperty("n", ++asks);
        } else if (t.equals("snap")) {
            o.addProperty("n", ++snaps);
        }
        try {
            out.write(GSON.toJson(o));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeHeader() {
        JsonArray ss = new JsonArray();
        for (int i = 0; i < names.length; i++) {
            JsonObject s = new JsonObject();
            s.addProperty("seat", i);
            s.addProperty("name", names[i]);
            JsonArray cards = decks[i] == null ? new JsonArray() : decks[i];
            JsonArray cmd = new JsonArray();
            if (seats != null) {
                for (Card c : seats[i].getCommanders()) {
                    cmd.add(cards.size());
                    cards.add(idName(c));
                }
            }
            s.add("cards", cards);
            s.add("commanders", cmd);
            s.addProperty("policy", policies[i]);
            ss.add(s);
        }
        header.add("seats", ss);
        header.addProperty("id", id);
        header.addProperty("free_mulligan", seats != null && seats.length > 2);
        headed = true;
        write(header);
        for (JsonObject o : preamble) {
            write(o);
        }
        preamble.clear();
    }

    // ---- game events --------------------------------------------------------

    @Subscribe
    public void receive(GameEvent ev) {
        try {
            if (ev instanceof GameEventGameStarted gs) {
                if (headed) {
                    return; // a restarted game (Karn) keeps one header
                }
                writeHeader();
                JsonObject o = line("start");
                o.addProperty("seat", seatOf(gs.firstTurn()));
                write(o);
            } else if (ev instanceof GameEventTurnPhase) {
                // A step that asked nothing leaves its snap unwritten.
                pendingSnap = headed ? checkpoint() : null;
            } else if (ev instanceof GameEventFlipCoin fc) {
                if (!fc.startingToss()) {
                    JsonObject o = line("rand");
                    o.addProperty("kind", "coin");
                    o.addProperty("bound", 2);
                    o.addProperty("value", fc.heads() ? 1 : 0);
                    o.addProperty("seat", seatOf(fc.flipper()));
                    emit(o);
                }
            } else if (ev instanceof GameEventRollDie rd) {
                if (rd.planar() == null && rd.sides() > 0) {
                    JsonObject o = line("rand");
                    o.addProperty("kind", "die");
                    o.addProperty("bound", rd.sides());
                    o.addProperty("value", rd.result());
                    emit(o);
                }
            } else if (ev instanceof GameEventCardTapped t) {
                if (taps != null && t.tapped()) {
                    taps.add(t.card().getId());
                }
            }
        } catch (RuntimeException e) {
            if (error == null) {
                error = e;
            }
        }
    }

    // ---- refs ---------------------------------------------------------------

    private static JsonObject idName(Card c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        o.addProperty("name", c.getName());
        return o;
    }

    static JsonArray ids(Iterable<Card> cs) {
        JsonArray a = new JsonArray();
        for (Card c : cs) {
            a.add(c.getId());
        }
        return a;
    }

    /** A ref to a card, a player or a spell; null for anything else. */
    JsonObject ref(GameObject o) {
        JsonObject r = new JsonObject();
        if (o instanceof Card c) {
            r.addProperty("card", c.getId());
        } else if (o instanceof Player p) {
            r.addProperty("player", seatOf(p));
        } else if (o instanceof SpellAbility sa && sa.getHostCard() != null) {
            r.addProperty("spell", sa.getHostCard().getId());
        } else {
            return null;
        }
        return r;
    }

    JsonArray refs(Iterable<? extends GameObject> os) {
        JsonArray a = new JsonArray();
        for (GameObject o : os) {
            JsonObject r = ref(o);
            if (r != null) {
                a.add(r);
            }
        }
        return a;
    }

    /** Every target of the chain from sa, flat in chain order, into
     * {@code into}; returns how many each targeting part holds. */
    JsonArray targets(SpellAbility sa, JsonArray into) {
        JsonArray slots = new JsonArray();
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.usesTargeting()) {
                JsonArray rs = refs(cur.getTargets());
                into.addAll(rs);
                slots.add(rs.size());
            }
        }
        return slots;
    }
}
