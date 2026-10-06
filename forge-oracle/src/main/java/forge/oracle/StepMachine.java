// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.card.mana.ManaAtom;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.oracle.DecisionQueue.Decision;

/**
 * One scenario attempt: build the game, place the setup, then drive Forge's
 * own loop (PhaseHandler.mainLoopStep) and play the steps (DESIGN 6.4).
 *
 * P0 found that one mainLoopStep asks the priority player again and again
 * until it passes, so the step machine lives INSIDE the
 * chooseSpellAbilityToPlay callback ({@link #onPriority}): each time a seat
 * receives priority it checks the current step's completion, snapshots, and
 * returns the next action or passes.
 *
 * Ops: mana, cast, activate, play, resolve. Completion:
 * <ul>
 * <li>mana: immediate;</li>
 * <li>cast / activate: priority back to the acting seat with the spell or
 *     ability on the stack (a mana ability, at once); otherwise the cast
 *     failed, which is a harness row naming the step;</li>
 * <li>play: priority back with the land on the battlefield;</li>
 * <li>resolve: the stack is empty and the active player has priority. A
 *     resolve over an empty stack completes at once, as gorge's runner does.</li>
 * </ul>
 */
public final class StepMachine {
    static final int LOOP_GUARD = 4000;

    final Request req;
    final boolean strict;
    final DecisionQueue dq;
    final RefTable refs = new RefTable();
    final JsonArray steps;
    final int targetTurn;
    Game game;
    final Player[] seats = new Player[2];
    final List<JsonObject> snaps = new ArrayList<>();
    final List<String> notes = new ArrayList<>();

    int stepIdx = -1; // -1 = setup, until the scenario turn's first main-1 priority
    boolean acted;
    boolean done;
    SpellAbility actedSa;
    int actedHostId;
    List<String> stepTargets = new ArrayList<>();
    RuntimeException failure;

    public StepMachine(Request req, boolean strict) {
        this.req = req;
        this.strict = strict;
        this.dq = new DecisionQueue(req.decisions);
        this.steps = req.steps();
        this.targetTurn = req.turn();
    }

    public boolean strict() {
        return strict;
    }

    public int step() {
        return stepIdx;
    }

    // ---- run ----------------------------------------------------------------

    /** Builds, places and drives; returns the row (harness on failure). */
    public ResultRow run() {
        ResultRow row = new ResultRow();
        row.id = req.id;
        row.name = req.name;
        row.strict = strict;
        try {
            build();
            SetupBuilder.place(this);
            drive();
            if (failure != null) {
                throw failure;
            }
            String left = dq.leftover();
            if (left != null) {
                row.leftover = left;
            }
        } catch (Throwable t) {
            // A strict miss or harness error thrown inside Forge may arrive
            // wrapped; the recorded failure is the truth.
            Throwable cause = failure != null ? failure : t;
            row.harness = ResultRow.message(cause);
            row.notes.add("trace: " + ResultRow.trace(t, 8));
        }
        row.snapshots.addAll(snaps);
        row.notes.addAll(notes);
        return row;
    }

    void build() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < 2; i++) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(new ScriptedLobbyPlayer("p" + i, this, i)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "oracle");
        game = new Game(players, rules, match);
        for (int i = 0; i < 2; i++) {
            seats[i] = game.getPlayers().get(i);
        }
    }

    void drive() {
        game.setAge(GameStage.Play);
        PhaseHandler ph = game.getPhaseHandler();
        ph.setupFirstTurn(seats[0], null);
        int guard = 0;
        while (!done && failure == null && !game.isGameOver()) {
            if (++guard > LOOP_GUARD) {
                throw new HarnessError("no progress after " + LOOP_GUARD + " loop steps at step " + stepIdx);
            }
            ph.mainLoopStep();
            if (ph.getTurn() > targetTurn) {
                throw new HarnessError("passed turn " + targetTurn + " at step " + stepIdx);
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (!done) {
            throw new HarnessError("game ended at step " + stepIdx + " before every step ran");
        }
    }

    private RuntimeException fail(RuntimeException e) {
        if (failure == null) {
            failure = e;
        }
        return e;
    }

    void note(String s) {
        notes.add(s);
    }

    /** An unscripted question: strict fails the attempt, loose lets the AI
     * answer and notes it. */
    void miss(String what, String detail) {
        if (strict) {
            throw fail(new StrictMiss(what, stepIdx, detail));
        }
        notes.add("loose: AI answered " + what + " at step " + stepIdx + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    Decision take(int seat, Predicate<Decision> match) {
        return dq.take(stepIdx, d -> d.seat == seat && match.test(d));
    }

    // ---- the priority callback ---------------------------------------------

    List<SpellAbility> onPriority(ScriptedController ctl) {
        if (done || failure != null) {
            return null;
        }
        try {
            return priority(ctl);
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    private List<SpellAbility> priority(ScriptedController ctl) {
        PhaseHandler ph = game.getPhaseHandler();
        Player p = ctl.getPlayer();
        if (stepIdx < 0) {
            // gorge's xmageFixture setup stops at the FIRST main-1 priority of
            // the scenario turn, even with a non-empty stack (oracle_run.go:622-647).
            if (ph.getTurn() == targetTurn && ph.is(PhaseType.MAIN1) && p == ph.getPlayerTurn()) {
                snaps.add(SnapshotWriter.snapshot("setup", game, seats));
                stepIdx = 0;
                acted = false;
            } else {
                return null;
            }
        }
        while (true) {
            if (stepIdx >= steps.size()) {
                done = true;
                return null;
            }
            JsonObject st = steps.get(stepIdx).getAsJsonObject();
            String op = Request.str(st, "op");
            int seat = st.has("seat") ? st.get("seat").getAsInt() : 0;
            switch (op) {
                case "mana":
                    addMana(seats[seat], Request.str(st, "mana"));
                    complete(op);
                    continue;
                case "cast":
                case "activate":
                case "play":
                    if (!acted) {
                        if (p != seats[seat]) {
                            return null; // pass until the acting seat holds priority
                        }
                        return act(st, op, p);
                    }
                    if (p != seats[seat]) {
                        return null;
                    }
                    checkActed(st, op);
                    complete(op);
                    continue;
                case "resolve":
                    if (game.getStack().isEmpty() && p == ph.getPlayerTurn()) {
                        complete(op);
                        continue;
                    }
                    acted = true;
                    return null; // pass
                default:
                    throw new HarnessError("op " + op + " unsupported by the Forge driver (step " + stepIdx + ")");
            }
        }
    }

    private void complete(String op) {
        snaps.add(SnapshotWriter.snapshot("step " + stepIdx + " (" + op + ")", game, seats));
        stepIdx++;
        acted = false;
        actedSa = null;
        stepTargets = new ArrayList<>();
    }

    private List<SpellAbility> act(JsonObject st, String op, Player p) {
        String mana = Request.str(st, "mana");
        if (!mana.isEmpty()) {
            addMana(p, mana);
        }
        stepTargets = new ArrayList<>(Request.strings(st, "targets"));
        Card c = card(Request.str(st, "card"));
        SpellAbility sa;
        switch (op) {
            case "cast": sa = spellFor(c, st); break;
            case "play": sa = landFor(c, st); break;
            default: sa = abilityFor(c); break;
        }
        sa.setActivatingPlayer(p);
        acted = true;
        actedSa = sa;
        actedHostId = c.getId();
        return Lists.newArrayList(sa);
    }

    /** Priority came back to the acting seat: did the action happen? */
    private void checkActed(JsonObject st, String op) {
        if (op.equals("play")) {
            Card c = game.findById(actedHostId);
            if (c == null || !c.isInZone(ZoneType.Battlefield)) {
                throw new HarnessError("step " + stepIdx + " (play) " + Request.str(st, "card") + " was not played");
            }
            return;
        }
        if (actedSa.isManaAbility()) {
            return;
        }
        boolean wantSpell = op.equals("cast");
        for (SpellAbilityStackInstance si : game.getStack()) {
            Card src = si.getSourceCard();
            if (src != null && src.getId() == actedHostId && si.isSpell() == wantSpell) {
                return;
            }
        }
        throw new HarnessError("step " + stepIdx + " (" + op + ") " + Request.str(st, "card")
                + " was not put on the stack (cost unpaid, no legal target, or Forge refused it)");
    }

    // ---- objects and refs ---------------------------------------------------

    Card card(String ref) {
        GameEntity e = entity(ref);
        if (!(e instanceof Card)) {
            throw new HarnessError("ref " + ref + " names a player, not a card");
        }
        return (Card) e;
    }

    GameEntity entity(String ref) {
        RefTable.Ref r = RefTable.parse(ref);
        if (r.player) {
            if (r.seat >= seats.length) {
                throw new HarnessError("ref " + ref + " names no seat");
            }
            return seats[r.seat];
        }
        int id = refs.resolveCard(ref, liveObjects());
        Card c = game.findById(id);
        if (c == null) {
            throw new HarnessError("ref " + ref + " (card " + id + ") is not in the game");
        }
        return c;
    }

    List<RefTable.Obj> liveObjects() {
        List<RefTable.Obj> out = new ArrayList<>();
        for (Card c : game.getCardsInGame()) {
            List<String> names = new ArrayList<>();
            names.add(c.getName());
            if (c.getPaperCard() != null && !names.contains(c.getPaperCard().getName())) {
                names.add(c.getPaperCard().getName());
            }
            for (forge.card.CardStateName sn : c.getStates()) {
                String n = c.getState(sn).getName();
                if (n != null && !n.isEmpty() && !names.contains(n)) {
                    names.add(n);
                }
            }
            out.add(new RefTable.Obj(c.getId(), SnapshotWriter.seatOf(seats, c.getOwner()),
                    SnapshotWriter.seatOf(seats, c.getController()), names, c.isToken(), c.isInZone(ZoneType.Battlefield)));
        }
        return out;
    }

    private SpellAbility spellFor(Card c, JsonObject st) {
        List<SpellAbility> spells = new ArrayList<>(c.getSpells());
        if (spells.isEmpty()) {
            throw new HarnessError(Request.str(st, "card") + " has no spell ability");
        }
        if (spells.size() > 1) {
            note("cast: " + spells.size() + " spell abilities on " + c.getName() + "; took the first: " + spells.get(0));
        }
        return spells.get(0);
    }

    private SpellAbility landFor(Card c, JsonObject st) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isLandAbility()) {
                return sa;
            }
        }
        throw new HarnessError(Request.str(st, "card") + " has no land play");
    }

    /** Ability identity (DESIGN 6.5; P2-1 owns the census): the gorge ability
     * line's parsed params against each SA's params, exact first, then API
     * plus cost for keyword-derived abilities. */
    private SpellAbility abilityFor(Card c) {
        String line = req.abilities.get(stepIdx);
        if (line == null) {
            throw new HarnessError("no ability line for step " + stepIdx + " (sidecar abilities)");
        }
        Map<String, String> want = AbilityFactory.getMapParams(line);
        String kind = line.length() >= 2 ? line.substring(0, 2) : "";
        List<SpellAbility> exact = new ArrayList<>();
        List<SpellAbility> loose = new ArrayList<>();
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell() || sa.isLandAbility()) {
                continue;
            }
            if (sa.getMapParams().equals(want)) {
                exact.add(sa);
                continue;
            }
            String api = sa.getApi() == null ? "" : sa.getApi().name();
            String cost = sa.getPayCosts() == null ? "" : sa.getPayCosts().toSimpleString();
            if (api.equalsIgnoreCase(want.getOrDefault(kind, "")) && normCost(cost).equals(normCost(want.getOrDefault("Cost", "")))) {
                loose.add(sa);
            }
        }
        if (exact.size() == 1) {
            note("identity: exact params match at step " + stepIdx);
            return exact.get(0);
        }
        if (exact.isEmpty() && loose.size() == 1) {
            note("identity: api+cost fallback at step " + stepIdx);
            return loose.get(0);
        }
        throw new HarnessError("ability identity at step " + stepIdx + ": exact=" + exact.size() + " loose=" + loose.size());
    }

    private static String normCost(String s) {
        return s.replaceAll("[{}\\s]", "").toUpperCase(java.util.Locale.ROOT);
    }

    void addMana(Player p, String letters) {
        // A dummy source, as GameState.updateManaPool does for puzzle pools.
        Card dummy = new Card(-777777, game);
        dummy.setOwner(p);
        for (char ch : letters.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            byte b;
            switch (ch) {
                case 'W': b = (byte) ManaAtom.WHITE; break;
                case 'U': b = (byte) ManaAtom.BLUE; break;
                case 'B': b = (byte) ManaAtom.BLACK; break;
                case 'R': b = (byte) ManaAtom.RED; break;
                case 'G': b = (byte) ManaAtom.GREEN; break;
                case 'C': b = (byte) ManaAtom.COLORLESS; break;
                default: throw new HarnessError("mana letter " + ch);
            }
            p.getManaPool().addMana(new Mana(b, dummy, null, p));
        }
    }

    // ---- routed answers -----------------------------------------------------

    boolean chooseTargets(ScriptedController ctl, SpellAbility sa) {
        try {
            return targets(ctl, sa);
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    private boolean targets(ScriptedController ctl, SpellAbility sa) {
        Decision d = take(ctl.seat, DecisionQueue::isTarget);
        List<String> want;
        if (d != null) {
            want = d.refs();
        } else if (!stepTargets.isEmpty()) {
            // A bare Item (no sidecar decisions): the step's own target refs.
            want = new ArrayList<>(stepTargets);
            stepTargets.clear();
            note("targets from the step at step " + stepIdx + ": " + want);
        } else {
            miss("target", String.valueOf(sa));
            return false; // loose: no scripted target; the cast fails visibly
        }
        int max = Math.max(sa.getMaxTargets(), 1);
        for (String ref : want) {
            if (sa.getTargets().size() >= max) {
                note("target " + ref + " beyond max " + max + " at step " + stepIdx);
                break;
            }
            GameObject o = targetObject(ref);
            if (!sa.canTarget(o)) {
                throw new HarnessError("step " + stepIdx + " target " + ref + " is not a legal target for " + sa);
            }
            sa.getTargets().add(o);
            note("target step " + stepIdx + ": " + ref + " -> " + o);
        }
        if (sa.isDividedAsYouChoose() && !sa.getTargets().isEmpty()) {
            int total = sa.getStillToDivide();
            int n = sa.getTargets().size();
            if (n == 1) {
                sa.addDividedAllocation(sa.getTargets().get(0), total);
            } else {
                // gorge's log carries no per-target split (contract question for P1-6).
                miss("divided", "split of " + total + " among " + n + " targets");
                for (int k = 0; k < n; k++) {
                    sa.addDividedAllocation(sa.getTargets().get(k), total / n + (k == 0 ? total % n : 0));
                }
            }
            note("divided step " + stepIdx + ": " + total + " among " + n);
        }
        return sa.getTargets().size() >= sa.getMinTargets();
    }

    /** A target ref's object: a player, a permanent or card, or the spell a
     * stack card represents. */
    private GameObject targetObject(String ref) {
        GameEntity e = entity(ref);
        if (e instanceof Card && ((Card) e).isInZone(ZoneType.Stack)) {
            for (SpellAbilityStackInstance si : game.getStack()) {
                if (si.getSourceCard() != null && si.getSourceCard().getId() == ((Card) e).getId() && si.isSpell()) {
                    return si.getSpellAbility();
                }
            }
        }
        return e;
    }

    Boolean yesNo(int seat, String what) {
        Decision d = take(seat, DecisionQueue::isYesNo);
        if (d != null) {
            boolean y = DecisionQueue.yes(d);
            note("yesno step " + stepIdx + ": " + (y ? "yes" : "no") + " (" + what + ")");
            return y;
        }
        miss("yesno", what);
        return null;
    }

    /** A trigger-cost or optional-payment answer, if gorge logged one. */
    Boolean yesNoOptional(int seat) {
        Decision d = take(seat, x -> DecisionQueue.payOf(x) != null);
        return d == null ? null : DecisionQueue.payOf(d);
    }

    List<AbilitySub> chooseModes(int seat, SpellAbility sa, List<AbilitySub> possible, int min, int num) {
        Decision d = take(seat, DecisionQueue::isMode);
        if (d == null) {
            return null;
        }
        List<AbilitySub> out = new ArrayList<>();
        for (String label : d.picks) {
            AbilitySub hit = null;
            for (AbilitySub sub : possible) {
                if (out.contains(sub)) {
                    continue;
                }
                if (sameText(sub.getParam("SpellDescription"), label) || sameText(sub.getDescription(), label)) {
                    hit = sub;
                    break;
                }
            }
            if (hit == null) {
                miss("mode", "gorge mode \"" + label + "\" matches no Forge mode of " + sa);
                return null;
            }
            out.add(hit);
        }
        note("modes step " + stepIdx + ": " + d.picks);
        return out;
    }

    static boolean sameText(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.replaceAll("\\s+", " ").trim();
        String y = b.replaceAll("\\s+", " ").trim();
        return x.equalsIgnoreCase(y);
    }

    /** Optional costs (kicker, bargain, gift, ...). gorge logs an optional
     * cost only when it is offered AND chosen or explicitly declined (gift);
     * with no such decision at this step gorge paid none, so neither do we. */
    List<OptionalCostValue> chooseOptionalCosts(int seat, List<OptionalCostValue> values) {
        if (values.isEmpty()) {
            return new ArrayList<>();
        }
        Decision d = take(seat, x -> x.pickKinds.contains("gift_decline"));
        if (d != null) {
            note("optional cost step " + stepIdx + ": declined (" + d.picks + ")");
            return new ArrayList<>();
        }
        Decision pay = peekOptional(seat, values);
        if (pay != null) {
            pay.consumed = true;
            List<OptionalCostValue> out = new ArrayList<>();
            for (OptionalCostValue v : values) {
                if (optionalMatches(v, pay.picks.get(0))) {
                    out.add(v);
                }
            }
            note("optional cost step " + stepIdx + ": paid " + out);
            return out;
        }
        note("optional cost step " + stepIdx + ": none of " + values + " (gorge logged none)");
        return new ArrayList<>();
    }

    private Decision peekOptional(int seat, List<OptionalCostValue> values) {
        return dq.peek(stepIdx, x -> x.seat == seat && x.kind.equals("choose_n") && x.picks.size() == 1
                && values.stream().anyMatch(v -> optionalMatches(v, x.picks.get(0))));
    }

    static boolean optionalMatches(OptionalCostValue v, String label) {
        String l = label.trim().toLowerCase(java.util.Locale.ROOT);
        return !l.isEmpty() && (l.equals(v.toString().toLowerCase(java.util.Locale.ROOT))
                || l.startsWith(v.getType().getName().toLowerCase(java.util.Locale.ROOT)));
    }

    /** Object picks from gorge's log: by ref (id, then same name when gorge's
     * ordinal names an object Forge numbered differently), else forced when
     * the options are exactly what min/max require. null = loose AI answer. */
    <T extends GameEntity> List<T> pickObjects(int seat, Iterable<T> options, int min, int max, String what) {
        List<T> opts = new ArrayList<>();
        options.forEach(opts::add);
        Decision d = take(seat, DecisionQueue::isObjectChoice);
        if (d != null) {
            List<T> out = new ArrayList<>();
            Set<T> used = new HashSet<>();
            for (String ref : d.refs()) {
                T o = matchRef(ref, opts, used);
                if (o == null) {
                    miss(what, "gorge pick " + ref + " is not among Forge's " + opts.size() + " options");
                    return null;
                }
                used.add(o);
                out.add(o);
            }
            if (out.size() < min || out.size() > max) {
                miss(what, "gorge picked " + out.size() + ", Forge wants " + min + ".." + max);
                return null;
            }
            note("pick step " + stepIdx + " " + what + ": " + d.refs());
            return out;
        }
        if (opts.isEmpty()) {
            return new ArrayList<>();
        }
        if (min == max && opts.size() == min) {
            return opts; // forced: every option is required
        }
        miss(what, opts.size() + " options, " + min + ".." + max + " to pick");
        return null;
    }

    private <T extends GameEntity> T matchRef(String ref, List<T> opts, Set<T> used) {
        GameEntity e;
        try {
            e = entity(ref);
        } catch (HarnessError notFound) {
            e = null;
        }
        for (T o : opts) {
            if (!used.contains(o) && o.equals(e)) {
                return o;
            }
        }
        RefTable.Ref r = RefTable.parse(ref);
        for (T o : opts) {
            if (!used.contains(o) && o instanceof Card && ((Card) o).getName().equals(r.name)
                    && SnapshotWriter.seatOf(seats, ((Card) o).getOwner()) == r.seat) {
                note("pick " + ref + ": took another " + r.name + " (gorge's ordinal names a different copy)");
                return o;
            }
        }
        return null;
    }

    List<Card> orderCards(int seat, Collection<Card> cards, String what) {
        Set<String> names = new HashSet<>();
        for (Card c : cards) {
            names.add(c.getName());
        }
        if (names.size() <= 1) {
            return new ArrayList<>(cards); // identical names: the order is unobservable
        }
        miss("order", what);
        return null;
    }

    List<SpellAbility> orderTriggers(int seat, List<SpellAbility> sas) {
        miss("trigger order", sas.size() + " simultaneous triggers");
        return null;
    }

    String chooseLabel(int seat, Collection<String> options, String what) {
        Decision d = take(seat, x -> x.kind.equals("choose_n") && x.picks.size() == 1 && containsIgnoreCase(options, x.picks.get(0)));
        if (d == null) {
            return null;
        }
        for (String o : options) {
            if (o.equalsIgnoreCase(d.picks.get(0))) {
                note("label step " + stepIdx + " " + what + ": " + o);
                return o;
            }
        }
        return null;
    }

    Integer chooseNumber(int seat, int min, int max) {
        Decision d = take(seat, x -> x.kind.equals("choose_n") && x.picks.size() == 1 && number(x.picks.get(0)) != null);
        if (d == null) {
            return null;
        }
        Integer n = number(d.picks.get(0));
        return n != null && n >= min && n <= max ? n : null;
    }

    private static Integer number(String s) {
        String t = s.replaceFirst("^\\s*X\\s*=\\s*", "").trim();
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(Collection<String> options, String s) {
        for (String o : options) {
            if (o.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }
}
