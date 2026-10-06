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
import com.google.gson.JsonElement;
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
    static final int TURN_SLACK = 4;

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
            // Setup must stop on the scenario turn. A step may legitimately move
            // on (pass_to a later turn, an off-turn attack, an end-the-turn
            // resolve), but never far: gorge's own loop caps it the same way.
            int cap = stepIdx < 0 ? targetTurn : targetTurn + TURN_SLACK;
            if (ph.getTurn() > cap) {
                throw new HarnessError("passed turn " + cap + " at step " + stepIdx);
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
        for (int st : decisionSteps()) {
            Decision d = dq.take(st, x -> x.seat == seat && match.test(x));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** The steps whose logged decisions answer a question asked now. gorge's
     * pass_to stops at the first decision posed in the named step: a
     * beginning-of-combat trigger's target ask is pending there, so gorge
     * logs that answer under the NEXT step. Forge asks it while putting the
     * trigger on the stack, before the priority where this driver stops, so
     * once the pass_to's step stop holds, the next step's answers count too. */
    int[] decisionSteps() {
        if (stepIdx >= 0 && stepIdx + 1 < steps.size() && game != null) {
            JsonObject st = steps.get(stepIdx).getAsJsonObject();
            if (Request.str(st, "op").equals("pass_to") && acted && !Request.str(st, "step").isEmpty()
                    && Request.str(st, "step").equals(SnapshotWriter.stepName(game.getPhaseHandler().getPhase()))) {
                String active = Request.str(st, "active");
                if (active.isEmpty() || game.getPhaseHandler().getPlayerTurn() == seats[RefTable.parseSeat(active)]) {
                    return new int[] {stepIdx, stepIdx + 1};
                }
            }
        }
        return new int[] {stepIdx};
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
                    // Never at a cleanup priority: an end-the-turn effect (Time
                    // Stop) gives one there, but gorge's resolve stops at the next
                    // turn's first priority (the XMage driver's endTurnScenario).
                    if (game.getStack().isEmpty() && p == ph.getPlayerTurn() && !ph.is(PhaseType.CLEANUP)) {
                        complete(op);
                        continue;
                    }
                    acted = true;
                    return null; // pass
                case "pass":
                    // gorge's pass answers exactly one priority decision of the
                    // named seat; the checkpoint is the next priority (the
                    // opponent's, or the active player's after a resolution).
                    if (!acted) {
                        if (p != seats[seat]) {
                            throw new HarnessError("pass: p" + seat + " does not hold priority at step " + stepIdx
                                    + " (p" + SnapshotWriter.seatOf(seats, p) + " does)");
                        }
                        acted = true;
                        return null;
                    }
                    complete(op);
                    continue;
                case "pass_to":
                    if (passToReached(st, ph)) {
                        complete(op);
                        continue;
                    }
                    acted = true; // gorge answers at least one decision before a step stop
                    return null;
                case "attack":
                case "block":
                    // The declaration happens in the declareAttackers/Blockers
                    // callback; until then every priority passes, as gorge's
                    // runner answers "to-attack"/"to-block". The checkpoint is
                    // the first priority after the declaration.
                    if (!acted) {
                        if (op.equals("attack") && pastDeclare(ph, seats[seat], PhaseType.COMBAT_DECLARE_ATTACKERS)) {
                            throw new HarnessError("attack: passed declare-attackers without being asked (step " + stepIdx + ")");
                        }
                        if (op.equals("block") && ph.getPhase() != null
                                && ph.getPhase().isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS) && ph.inCombat()) {
                            throw new HarnessError("block: passed declare-blockers without being asked (step " + stepIdx + ")");
                        }
                        return null;
                    }
                    complete(op);
                    continue;
                case "move":
                case "attach":
                    // A direct state change, as gorge's runner emits it; then
                    // one empty priority round so state-based actions and any
                    // trigger reach the stack before the checkpoint (gorge's
                    // priorityRound + untilPriority).
                    if (!acted) {
                        if (op.equals("move")) {
                            move(st);
                        } else {
                            attach(st);
                        }
                        acted = true;
                        return new ArrayList<>();
                    }
                    complete(op);
                    continue;
                default:
                    throw new HarnessError("op " + op + " unsupported by the Forge driver (step " + stepIdx + ")");
            }
        }
    }

    /** gorge's pass_to stop (rules/oracle_run.go oracleOpPassTo): a pending
     * decision of the named kind (checked first, even before any answer), or
     * -- after at least one answer -- the named step with the named active
     * seat. At a priority callback the only pending kind is "priority"; the
     * attackers/blockers kinds stop inside those callbacks. */
    private boolean passToReached(JsonObject st, PhaseHandler ph) {
        String decision = Request.str(st, "decision");
        if (decision.equals("priority")) {
            return true;
        }
        if (!decision.isEmpty() && !decision.equals("attackers") && !decision.equals("blockers")) {
            throw new HarnessError("pass_to decision " + decision + " unsupported (step " + stepIdx + ")");
        }
        String want = Request.str(st, "step");
        if (want.isEmpty() || !acted) {
            return false;
        }
        if (!want.equals(SnapshotWriter.stepName(ph.getPhase()))) {
            return false;
        }
        String active = Request.str(st, "active");
        return active.isEmpty() || ph.getPlayerTurn() == seats[RefTable.parseSeat(active)];
    }

    private boolean pastDeclare(PhaseHandler ph, Player attacker, PhaseType declare) {
        return ph.getPlayerTurn() == attacker && ph.getPhase() != null && ph.getPhase().isAfter(declare)
                && !ph.getPhase().isAfter(PhaseType.COMBAT_END);
    }

    // ---- combat callbacks ------------------------------------------------

    /** declareAttackers: the attack step's plan, or a pass_to stop on the
     * attackers decision (snapshot taken here, before anything is declared),
     * or no attack. */
    void declareAttackers(Player attacker, forge.game.combat.Combat combat) {
        try {
            while (!done && failure == null && stepIdx >= 0 && stepIdx < steps.size()) {
                JsonObject st = steps.get(stepIdx).getAsJsonObject();
                String op = Request.str(st, "op");
                int seat = st.has("seat") ? st.get("seat").getAsInt() : 0;
                if (op.equals("pass_to") && Request.str(st, "decision").equals("attackers")) {
                    complete(op);
                    continue;
                }
                if (op.equals("attack") && !acted && attacker == seats[seat]) {
                    String defRef = Request.str(st, "defender");
                    int def = RefTable.parseSeat(defRef);
                    if (def < 0 || def >= seats.length) {
                        throw new HarnessError("attack: bad defender " + defRef);
                    }
                    for (String a : Request.strings(st, "attackers")) {
                        Card c = card(a);
                        if (!forge.game.combat.CombatUtil.canAttack(c, seats[def])) {
                            throw new HarnessError("attack: " + a + " at " + defRef + " not offered (step " + stepIdx + ")");
                        }
                        combat.addAttacker(c, seats[def]);
                    }
                    acted = true;
                    note("attack step " + stepIdx + ": " + Request.strings(st, "attackers") + " -> " + defRef);
                    return;
                }
                if (op.equals("attack") && acted) {
                    throw new HarnessError("attack declaration invalid in Forge (step " + stepIdx + ")");
                }
                return; // no attack this combat
            }
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /** declareBlockers: the block step's [blocker, attacker] pairs, or a
     * pass_to stop on the blockers decision (the snapshot is taken inside
     * this callback, as gorge's checkpoint sits on the pending decision). */
    void declareBlockers(Player defender, forge.game.combat.Combat combat) {
        try {
            while (!done && failure == null && stepIdx >= 0 && stepIdx < steps.size()) {
                JsonObject st = steps.get(stepIdx).getAsJsonObject();
                String op = Request.str(st, "op");
                int seat = st.has("seat") ? st.get("seat").getAsInt() : 0;
                if (op.equals("pass_to") && (Request.str(st, "decision").equals("blockers")
                        || (acted && Request.str(st, "step").equals("declare-blockers")))) {
                    complete(op);
                    continue;
                }
                if (op.equals("block") && !acted && defender == seats[seat]) {
                    for (JsonElement e : st.getAsJsonArray("blocks")) {
                        JsonArray pair = e.getAsJsonArray();
                        Card b = card(pair.get(0).getAsString());
                        Card a = card(pair.get(1).getAsString());
                        if (!combat.isAttacking(a) || !forge.game.combat.CombatUtil.canBlock(a, b, combat)) {
                            throw new HarnessError("block: " + pair.get(0).getAsString() + " on " + pair.get(1).getAsString()
                                    + " not offered (step " + stepIdx + ")");
                        }
                        combat.addBlocker(a, b);
                    }
                    acted = true;
                    note("block step " + stepIdx + ": " + st.get("blocks"));
                    return;
                }
                if (op.equals("block") && acted) {
                    throw new HarnessError("block declaration invalid in Forge (step " + stepIdx + ")");
                }
                return;
            }
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    private void move(JsonObject st) {
        String ref = Request.str(st, "card");
        Card c = card(ref);
        ZoneType to;
        switch (Request.str(st, "to")) {
            case "library": to = ZoneType.Library; break;
            case "hand": to = ZoneType.Hand; break;
            case "battlefield": to = ZoneType.Battlefield; break;
            case "graveyard": to = ZoneType.Graveyard; break;
            case "exile": to = ZoneType.Exile; break;
            default: throw new HarnessError("move: zone " + Request.str(st, "to") + " unsupported");
        }
        Card moved = game.getAction().moveTo(to, c, null, null);
        if (moved == null || !moved.isInZone(to)) {
            throw new HarnessError("move " + ref + " to " + to + " refused");
        }
        note("move step " + stepIdx + ": " + ref + " -> " + to);
    }

    private void attach(JsonObject st) {
        Card a = card(Request.str(st, "card"));
        GameEntity bearer = entity(Request.str(st, "attached_to"));
        if (!a.isInZone(ZoneType.Battlefield)) {
            throw new HarnessError("attach: " + Request.str(st, "card") + " is not on the battlefield");
        }
        a.attachToEntity(bearer, null);
        if (a.getEntityAttachedTo() != bearer) {
            throw new HarnessError("attach " + Request.str(st, "card") + " to " + Request.str(st, "attached_to") + " refused");
        }
        game.getAction().checkStaticAbilities();
        note("attach step " + stepIdx + ": " + Request.str(st, "card") + " -> " + Request.str(st, "attached_to"));
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
        if (!op.equals("play")) {
            payingManaSources(p);
        }
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

    /** gorge activated these mana sources inside the payment window (pick
     * kind "activate", object_picks naming the source). The driver pays from
     * the pool only, so it activates them first, at priority: the mana floats
     * into the pool and the cast that follows spends it. Same end state. */
    private void payingManaSources(Player p) {
        while (true) {
            Decision d = take(SnapshotWriter.seatOf(seats, p), x -> x.pickKinds.size() == 1 && x.pickKinds.get(0).equals("activate") && !x.objectPicks.isEmpty());
            if (d == null) {
                return;
            }
            Card src = card(d.objectPicks.get(0));
            SpellAbility ma = null;
            for (SpellAbility a : src.getManaAbilities()) {
                a.setActivatingPlayer(p);
                if (a.canPlay()) {
                    ma = a;
                    break;
                }
            }
            if (ma == null) {
                throw new HarnessError("step " + stepIdx + " payment source " + d.objectPicks.get(0) + " has no playable mana ability");
            }
            ma.setActivatingPlayer(p);
            boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(p.getController(), p, ma);
            note("payment source step " + stepIdx + ": " + d.objectPicks.get(0) + " activated=" + ok + " pool=" + p.getManaPool().totalMana());
        }
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

    /** Ability identity (DESIGN 6.5, P2-1). The sidecar carries gorge's IR
     * line for the step's ability_index. Tiers, first unique hit wins, and
     * the tier is noted so the census reads it from the rows:
     * <ol>
     * <li>exact: the line's parsed params equal the SA's map params;</li>
     * <li>basic-land: gorge's synthesized "intrinsic: basic land mana" is
     *     Forge's CardState.getLandManaForColor ability (Secondary$, one
     *     colour), picked by the step's "Add X" mana pick when several;</li>
     * <li>keyword: a keyword-derived line (Keyword$ K: cycling, equip, ...)
     *     against the SAs Forge derived from keyword K with the same API,
     *     then the same cost;</li>
     * <li>api+cost: same API and the same cost, parsed by Forge's own Cost.</li>
     * </ol>
     * Anything else is a harness row listing the candidates. */
    private SpellAbility abilityFor(Card c) {
        String line = req.abilities.get(stepIdx);
        if (line == null) {
            throw new HarnessError("no ability line for step " + stepIdx + " (sidecar abilities)");
        }
        List<SpellAbility> all = new ArrayList<>();
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (!sa.isSpell() && !sa.isLandAbility()) {
                all.add(sa);
            }
        }
        if (line.startsWith("intrinsic: basic land mana")) {
            List<SpellAbility> land = new ArrayList<>();
            for (SpellAbility sa : all) {
                if (sa.isManaAbility() && "True".equals(sa.getParam("Secondary")) && sa.getParam("Produced") != null
                        && sa.getParam("Produced").length() == 1) {
                    land.add(sa);
                }
            }
            if (land.size() > 1) {
                Decision pick = dq.peek(stepIdx, x -> x.pickKinds.contains("mana") && x.picks.size() == 1);
                if (pick != null) {
                    byte col = manaColor(pick.picks.get(0));
                    land.removeIf(sa -> forge.card.MagicColor.fromName(sa.getParam("Produced")) != col);
                    if (land.size() == 1) {
                        pick.consumed = true;
                    }
                }
            }
            return identity("basic-land", land, all, line);
        }
        Map<String, String> want = AbilityFactory.getMapParams(line);
        List<SpellAbility> exact = new ArrayList<>();
        for (SpellAbility sa : all) {
            if (sa.getMapParams().equals(want)) {
                exact.add(sa);
            }
        }
        if (!exact.isEmpty()) {
            return identity("exact", exact, all, line);
        }
        String kind = line.length() >= 2 ? line.substring(0, 2) : "";
        String api = want.getOrDefault(kind, "");
        String cost = costKey(want.getOrDefault("Cost", ""), c);
        String kw = want.get("Keyword");
        if (kw != null) {
            List<SpellAbility> byKw = new ArrayList<>();
            for (SpellAbility sa : all) {
                if (sa.getKeyword() != null && sa.getKeyword().getKeyword() != null
                        && sa.getKeyword().getKeyword().toString().equalsIgnoreCase(kw) && apiOf(sa).equalsIgnoreCase(api)) {
                    byKw.add(sa);
                }
            }
            if (byKw.size() > 1) {
                byKw.removeIf(sa -> !costKey(sa).equals(cost));
            }
            if (!byKw.isEmpty()) {
                return identity("keyword", byKw, all, line);
            }
        }
        List<SpellAbility> loose = new ArrayList<>();
        for (SpellAbility sa : all) {
            if (apiOf(sa).equalsIgnoreCase(api) && costKey(sa).equals(cost)) {
                loose.add(sa);
            }
        }
        return identity("api+cost", loose, all, line);
    }

    private SpellAbility identity(String tier, List<SpellAbility> hits, List<SpellAbility> all, String line) {
        if (hits.size() > 1) {
            // Same API and cost (a Class's level-ups): the most equal params.
            Map<String, String> want = AbilityFactory.getMapParams(line);
            int best = -1;
            List<SpellAbility> top = new ArrayList<>();
            for (SpellAbility sa : hits) {
                int n = 0;
                for (Map.Entry<String, String> e : want.entrySet()) {
                    if (e.getValue().equals(sa.getParam(e.getKey()))) {
                        n++;
                    }
                }
                if (n > best) {
                    best = n;
                    top.clear();
                }
                if (n == best) {
                    top.add(sa);
                }
            }
            if (top.size() == 1) {
                note("identity: " + tier + "+params at step " + stepIdx);
                return top.get(0);
            }
        }
        if (hits.size() == 1) {
            note("identity: " + tier + " at step " + stepIdx);
            return hits.get(0);
        }
        StringBuilder b = new StringBuilder();
        for (SpellAbility sa : all) {
            b.append(b.length() == 0 ? "" : "; ").append(apiOf(sa)).append(" ").append(costKey(sa));
            if (sa.getKeyword() != null) {
                b.append(" kw=").append(sa.getKeyword().getKeyword());
            }
        }
        String head = line.length() > 60 ? line.substring(0, 60) : line;
        throw new HarnessError("ability identity at step " + stepIdx + ": " + tier + " " + hits.size()
                + " (want " + head + "; candidates: " + b + ")");
    }

    private static String apiOf(SpellAbility sa) {
        return sa.getApi() == null ? "" : sa.getApi().name();
    }

    private static String costKey(SpellAbility sa) {
        return sa.getPayCosts() == null ? "" : normCost(sa.getPayCosts().toSimpleString());
    }

    private static String costKey(String raw, Card host) {
        if (raw.isEmpty()) {
            return "";
        }
        try {
            return normCost(new forge.game.cost.Cost(raw, true).toSimpleString());
        } catch (RuntimeException e) {
            return normCost(raw);
        }
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

    /**
     * Target binding by target SPEC, not by log order (P1-8 ticket 2). gorge
     * logs one target decision per ask; Forge asks once per targeting
     * (sub-)ability, in its own order: a charm's two modes come as one gorge
     * decision with two refs, and a trigger whose leading "up to one" slot
     * had no candidate is logged without that slot. So each Forge ask takes,
     * from this step's unconsumed target refs in log order, the refs this
     * ability can legally target, up to its max; the rest stay for the next
     * ask. A ref used here is not offered again.
     */
    private boolean targets(ScriptedController ctl, SpellAbility sa) {
        int min = sa.getMinTargets();
        int max = Math.max(sa.getMaxTargets(), 0);
        if (req.decisions.isEmpty() && !stepTargets.isEmpty()) {
            // A bare Item (hand fixture, no sidecar decisions): the step's own refs.
            List<String> want = new ArrayList<>(stepTargets);
            stepTargets.clear();
            note("targets from the step at step " + stepIdx + ": " + want);
            bindRefs(sa, want, Math.max(max, 1), null);
            return finishTargets(sa);
        }
        List<Decision> pending = new ArrayList<>();
        for (int stp : decisionSteps()) {
            for (Decision d : dq.all()) {
                if (!d.isConsumed() && d.step == stp && d.seat == ctl.seat && DecisionQueue.isTarget(d)) {
                    pending.add(d);
                }
            }
        }
        for (Decision d : pending) {
            List<String> left = unusedRefs(d);
            if (left.isEmpty()) {
                if (d.refs().isEmpty() && min == 0) {
                    // gorge's explicit empty answer to an "up to" ask.
                    d.consumed = true;
                    note("target step " + stepIdx + ": empty answer for " + sa.getHostCard());
                    return true;
                }
                continue;
            }
            if (bindRefs(sa, left, max, d) > 0) {
                return finishTargets(sa);
            }
        }
        // No scripted ref fits this ask.
        List<GameEntity> cands = sa.getTargetRestrictions() == null ? new ArrayList<>()
                : sa.getTargetRestrictions().getAllCandidates(sa);
        if (cands.isEmpty() || max == 0) {
            note("target step " + stepIdx + ": no candidate for " + sa.getHostCard());
            return min == 0;
        }
        if (min == 0) {
            // An "up to" slot gorge did not pose: leave it empty.
            note("target step " + stepIdx + ": up-to slot left empty (gorge posed none) for " + sa.getHostCard());
            return true;
        }
        if (conditional(sa)) {
            // A sub-ability whose condition (bargained, teamwork, gift, kicked)
            // gorge settled as false at cast time, so it posed no target ask;
            // Forge still asks. Any legal pick is inert at resolution: take the
            // first candidate not already chosen elsewhere in the chain.
            List<GameObject> taken = chainTargets(sa);
            for (GameEntity c : sortedCandidates(cands)) {
                if (!taken.contains(c) && sa.canTarget(c)) {
                    sa.getTargets().add(c);
                    note("target step " + stepIdx + ": conditional slot, inert pick " + c);
                    return finishTargets(sa);
                }
            }
        }
        for (Decision d : pending) {
            if (!unusedRefs(d).isEmpty()) {
                throw new HarnessError("step " + stepIdx + " target " + unusedRefs(d) + " is not a legal target for " + sa);
            }
        }
        miss("target", String.valueOf(sa));
        return false; // loose: no scripted target; the cast fails visibly
    }

    /** Binds the refs this ability can target, in order, up to max; marks
     * them used on the decision (consumed once every ref is used). */
    private int bindRefs(SpellAbility sa, List<String> refsIn, int max, Decision d) {
        int got = 0;
        for (String ref : refsIn) {
            if (sa.getTargets().size() >= max) {
                break;
            }
            GameObject o;
            try {
                o = targetObject(ref);
            } catch (HarnessError e) {
                o = null;
            }
            if (o == null || sa.getTargets().contains(o) || !sa.canTarget(o)) {
                // An unbound ordinal counts gorge's deal-shuffled object order;
                // name and owner are the identity (contract): another copy.
                o = sameNameTarget(sa, ref);
                if (o == null) {
                    continue;
                }
            }
            sa.getTargets().add(o);
            got++;
            if (d != null) {
                usedRefs.computeIfAbsent(d, k -> new ArrayList<>()).add(ref);
                if (unusedRefs(d).isEmpty()) {
                    d.consumed = true;
                }
            }
            note("target step " + stepIdx + ": " + ref + " -> " + o);
        }
        return got;
    }

    private GameObject sameNameTarget(SpellAbility sa, String ref) {
        RefTable.Ref r;
        try {
            r = RefTable.parse(ref);
        } catch (HarnessError e) {
            return null;
        }
        if (r.player || r.token) {
            return null;
        }
        List<Card> cs = new ArrayList<>();
        for (Card c : game.getCardsInGame()) {
            if (c.getName().equals(r.name) && SnapshotWriter.seatOf(seats, c.getOwner()) == r.seat
                    && !sa.getTargets().contains(c) && sa.canTarget(c)) {
                cs.add(c);
            }
        }
        cs.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
        if (cs.isEmpty()) {
            return null;
        }
        note("target " + ref + ": took another " + r.name + " (gorge's ordinal names a different copy)");
        return cs.get(0);
    }

    private final Map<Decision, List<String>> usedRefs = new java.util.IdentityHashMap<>();

    private List<String> unusedRefs(Decision d) {
        List<String> out = new ArrayList<>(d.refs());
        for (String u : usedRefs.getOrDefault(d, List.of())) {
            out.remove(u);
        }
        return out;
    }

    /** Divided allocation, then the min-count check. gorge poses the split
     * as its own choose_n (resume "damage_split") after the target ask: its
     * picks repeat a target once per point (contract, gorge forge.go). */
    private boolean finishTargets(SpellAbility sa) {
        if (sa.isDividedAsYouChoose() && !sa.getTargets().isEmpty()) {
            int total = sa.getStillToDivide();
            int n = sa.getTargets().size();
            if (n == 1) {
                sa.addDividedAllocation(sa.getTargets().get(0), total);
            } else {
                Decision split = dq.take(stepIdx, x -> x.resume.equals("damage_split"));
                Map<GameObject, Integer> amt = new java.util.LinkedHashMap<>();
                if (split != null) {
                    List<String> per = split.objectPicks.isEmpty() ? split.pickRefs : split.objectPicks;
                    for (String ref : per) {
                        GameObject o;
                        try {
                            o = targetObject(ref);
                        } catch (HarnessError e) {
                            continue;
                        }
                        if (sa.getTargets().contains(o)) {
                            amt.merge(o, 1, Integer::sum);
                        }
                    }
                }
                int sum = 0;
                for (int v : amt.values()) {
                    sum += v;
                }
                if (split == null || sum != total || amt.size() != n) {
                    miss("divided", "split of " + total + " among " + n + " targets"
                            + (split == null ? " (no damage_split)" : " (split " + amt.values() + ")"));
                    amt.clear();
                    for (int k = 0; k < n; k++) {
                        amt.put(sa.getTargets().get(k), total / n + (k == 0 ? total % n : 0));
                    }
                }
                for (Map.Entry<GameObject, Integer> e : amt.entrySet()) {
                    sa.addDividedAllocation(e.getKey(), e.getValue());
                }
            }
            note("divided step " + stepIdx + ": " + total + " among " + n);
        }
        return sa.getTargets().size() >= sa.getMinTargets();
    }

    /** The ability (or a parent in its chain) is gated by a Condition param. */
    static boolean conditional(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getParent()) {
            for (String k : s.getMapParams().keySet()) {
                if (k.startsWith("Condition")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<GameObject> chainTargets(SpellAbility sa) {
        List<GameObject> out = new ArrayList<>();
        SpellAbility root = sa;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
            if (s != sa && s.getTargets() != null) {
                for (GameObject o : s.getTargets()) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    private static List<GameEntity> sortedCandidates(List<GameEntity> cands) {
        List<GameEntity> out = new ArrayList<>(cands);
        out.sort((a, b) -> {
            boolean pa = a instanceof Player;
            boolean pb = b instanceof Player;
            if (pa != pb) {
                return pa ? -1 : 1;
            }
            return Integer.compare(a.getId(), b.getId());
        });
        return out;
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

    /** A GenericChoice ("choose one": Food or Treasure; the punisher's
     * "lose 4 life unless ..." per player): gorge logs a mode ask (resume
     * modes / generic_players) whose labels are the choices' descriptions. */
    List<SpellAbility> chooseGenericModes(int seat, List<SpellAbility> spells, int num) {
        Decision d = take(seat, x -> x.kind.equals("mode") && !x.picks.isEmpty() && x.picks.size() <= num
                && !x.resume.equals("unless_pay") && !x.resume.equals("cast_modes"));
        if (d == null) {
            return null;
        }
        List<SpellAbility> out = new ArrayList<>();
        for (String label : d.picks) {
            SpellAbility hit = null;
            for (SpellAbility sp : spells) {
                if (out.contains(sp)) {
                    continue;
                }
                String host = sp.getHostCard() == null ? "" : sp.getHostCard().getName();
                String want = norm(label, host);
                for (String c : new String[] {sp.getParam("SpellDescription"), sp.getDescription(), String.valueOf(sp)}) {
                    if (c != null && !want.isEmpty() && (norm(c, host).startsWith(want) || want.startsWith(norm(c, host)) && norm(c, host).length() >= 3)) {
                        hit = sp;
                        break;
                    }
                }
                if (hit != null) {
                    break;
                }
            }
            if (hit == null) {
                miss("generic choice", "gorge choice \"" + label + "\" matches no Forge choice");
                return null;
            }
            out.add(hit);
        }
        note("generic choice step " + stepIdx + ": " + d.picks);
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
        if (carryStep == stepIdx && carrySeat == seat && (!carryRefs.isEmpty() || carryOpen)) {
            // The rest of one gorge pick that Forge asks for one card at a time
            // (a search "up to two" is one gorge decision, two Forge asks).
            List<T> out = new ArrayList<>();
            Set<T> used = new HashSet<>();
            while (!carryRefs.isEmpty() && out.size() < max) {
                T o = matchRef(carryRefs.get(0), opts, used);
                if (o == null) {
                    break;
                }
                carryRefs.remove(0);
                used.add(o);
                out.add(o);
            }
            if (!out.isEmpty() || (carryRefs.isEmpty() && min == 0)) {
                if (carryRefs.isEmpty()) {
                    carryOpen = false;
                }
                note("pick step " + stepIdx + " " + what + ": carried " + out.size());
                return out;
            }
            carryRefs.clear();
            carryOpen = false;
        }
        Decision d = take(seat, x -> DecisionQueue.isObjectChoice(x) || DecisionQueue.isEmptyChoice(x));
        if (d != null) {
            List<T> out = new ArrayList<>();
            Set<T> used = new HashSet<>();
            List<String> refsAll = d.refs();
            List<String> refsNow = refsAll.size() > max && max >= 1 ? refsAll.subList(0, max) : refsAll;
            if (refsNow != refsAll || d.max > max) {
                carryStep = stepIdx;
                carrySeat = seat;
                carryRefs = new ArrayList<>(refsAll.subList(refsNow.size(), refsAll.size()));
                carryOpen = d.max > refsAll.size() || !carryRefs.isEmpty();
            }
            for (String ref : refsNow) {
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
        if (opts.size() <= min) {
            return opts; // forced: every option is required (and there are no more)
        }
        miss(what, opts.size() + " options, " + min + ".." + max + " to pick");
        return null;
    }

    /** Forge's "Cancel search?" after each searched card: true while gorge's
     * one pick still has refs to give, false once it is used up; null when no
     * gorge search pick is in flight at this step. */
    Boolean searchWantsMore(int seat) {
        if (carryStep != stepIdx || carrySeat != seat) {
            return null;
        }
        boolean more = !carryRefs.isEmpty();
        if (!more) {
            carryOpen = false;
        }
        return more;
    }

    private int carryStep = -99;
    private int carrySeat = -1;
    private List<String> carryRefs = new ArrayList<>();
    private boolean carryOpen;

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

    /** gorge's trigger_order ("order" kind, pick kinds "trigger"): picks[0]
     * is put on the stack FIRST and resolves LAST (decision.KTriggerOrder).
     * Forge's list is resolution order (orderAndPlaySimultaneousSa plays it
     * from the end), so the matched list is reversed. Labels are "Host: text"
     * and bind to Forge's triggers by normalized text prefix. */
    List<SpellAbility> orderTriggers(int seat, List<SpellAbility> sas) {
        Decision d = take(seat, x -> x.kind.equals("order") && !x.pickKinds.isEmpty() && x.pickKinds.get(0).equals("trigger")
                && x.picks.size() == sas.size());
        if (d != null) {
            List<SpellAbility> left = new ArrayList<>(sas);
            List<SpellAbility> placed = new ArrayList<>();
            for (String label : d.picks) {
                SpellAbility best = null;
                int bestScore = -1;
                boolean tie = false;
                for (SpellAbility sa : left) {
                    int sc = triggerScore(label, sa);
                    if (sc > bestScore) {
                        best = sa;
                        bestScore = sc;
                        tie = false;
                    } else if (sc == bestScore) {
                        tie = true;
                    }
                }
                if (best == null || bestScore < 4 || (tie && !sameEffect(left))) {
                    miss("trigger order", "gorge trigger \"" + label + "\" binds no unique Forge trigger");
                    return null;
                }
                left.remove(best);
                placed.add(best);
            }
            java.util.Collections.reverse(placed);
            note("trigger order step " + stepIdx + ": " + d.picks.size() + " bound");
            return placed;
        }
        if (sameEffect(sas)) {
            return sas; // copies of one trigger: the order is unobservable
        }
        miss("trigger order", sas.size() + " simultaneous triggers");
        return null;
    }

    private static boolean sameEffect(List<SpellAbility> sas) {
        java.util.Set<String> ds = new java.util.HashSet<>();
        for (SpellAbility sa : sas) {
            ds.add(sa.getHostCard().getName() + "|" + norm(String.valueOf(sa.getTrigger() != null ? sa.getTrigger() : sa), sa.getHostCard().getName()));
        }
        return ds.size() <= 1;
    }

    static String norm(String s, String host) {
        String t = s;
        if (host != null && !host.isEmpty()) {
            t = t.replace(host, "cardname");
        }
        return t.toLowerCase(java.util.Locale.ROOT).replace("cardname", "~").replaceAll("[^a-z0-9~]", "");
    }

    private static int triggerScore(String label, SpellAbility sa) {
        String host = sa.getHostCard().getName();
        String text = label;
        if (text.startsWith(host + ": ")) {
            text = text.substring(host.length() + 2);
        }
        String want = norm(text, host);
        int best = 0;
        List<String> cands = new ArrayList<>();
        if (sa.getTrigger() != null) {
            cands.add(sa.getTrigger().toString());
            if (sa.getTrigger().getKeyword() != null) {
                cands.add(sa.getTrigger().getKeyword().getKeyword().toString());
            }
        }
        cands.add(sa.toString());
        cands.add(sa.getDescription());
        for (String c : cands) {
            if (c == null) {
                continue;
            }
            String h = norm(c.startsWith(host + " - ") ? c.substring(host.length() + 3) : c, host);
            int k = 0;
            while (k < want.length() && k < h.length() && want.charAt(k) == h.charAt(k)) {
                k++;
            }
            best = Math.max(best, k);
        }
        return best;
    }

    /** A colour pick by name ("White"); null when gorge logged none. */
    Byte chooseColor(int seat, forge.card.ColorSet options) {
        Decision d = take(seat, x -> x.kind.equals("choose_n") && x.picks.size() == 1
                && (x.pickKinds.contains("color") || (x.pickKinds.contains("mana") && manaColor(x.picks.get(0)) != 0)));
        if (d == null) {
            return null;
        }
        byte b = d.pickKinds.contains("mana") ? manaColor(d.picks.get(0)) : forge.card.MagicColor.fromName(d.picks.get(0));
        if (b == 0 || (options != null && !options.hasAnyColor(b))) {
            miss("colour", "gorge colour " + d.picks.get(0) + " is not offered");
            return null;
        }
        note("colour step " + stepIdx + ": " + d.picks.get(0));
        return b;
    }

    /** gorge's mana-ability colour pick ("Add R", "Add {G}"): the colour of
     * a single coloured symbol, else 0 (colourless or several symbols). */
    static byte manaColor(String label) {
        String t = label.trim();
        int colon = t.lastIndexOf(": Add ");
        if (colon >= 0) {
            t = t.substring(colon + 2); // "Pay 1: Add W" (filter-land stage label)
        }
        if (!t.startsWith("Add ")) {
            return 0;
        }
        String sym = t.substring(4).replace("{", "").replace("}", "").trim();
        if (sym.length() != 1) {
            return 0;
        }
        return forge.card.MagicColor.fromName(sym.toLowerCase(java.util.Locale.ROOT));
    }

    /** A card-name pick ("name" kind). */
    String chooseName(int seat) {
        Decision d = take(seat, x -> x.kind.equals("choose_n") && x.pickKinds.contains("name") && x.picks.size() == 1);
        if (d == null) {
            return null;
        }
        note("name step " + stepIdx + ": " + d.picks.get(0));
        return d.picks.get(0);
    }

    /** Scry/surveil: gorge's "arrange" is the ordered-subset ask (Ruling J0):
     * the picked cards stay on top, in pick order, and every card not picked
     * goes away (to the bottom for scry, the graveyard for surveil). The pick
     * kinds name the away destination; they are the same for every option.
     * Measured on Diresight/Lightshell Duo (surveil 2, both picked, gorge's
     * graveyard stays empty). Returns {top, away}, or null when gorge logged
     * no arrangement. */
    List<List<Card>> arrange(int seat, List<Card> cards) {
        Decision d = take(seat, x -> x.kind.equals("order") && x.gorgeKind.equals("arrange"));
        if (d == null) {
            return null;
        }
        List<Card> rest = new ArrayList<>(cards);
        List<Card> top = new ArrayList<>();
        List<String> refs = d.refs();
        for (int i = 0; i < d.picks.size(); i++) {
            Card hit = null;
            for (Card c : rest) {
                if (SnapshotWriter.zoneName(c).equals(d.picks.get(i))
                        || (i < refs.size() && SnapshotWriter.zoneName(c).equals(RefTable.parse(refs.get(i)).name))) {
                    hit = c;
                    break;
                }
            }
            if (hit == null) {
                miss("arrange", "gorge card " + d.picks.get(i) + " is not among the cards looked at");
                return null;
            }
            rest.remove(hit);
            top.add(hit);
        }
        note("arrange step " + stepIdx + ": top " + top.size() + ", away " + rest.size());
        return List.of(top, rest);
    }

    /** A clone-style "may enter as a copy" replacement: gorge's clone pick is
     * the copied object, or "Enter as itself". The copied object's own pick is
     * left for the choice that follows. */
    Boolean cloneReplacement(int seat) {
        Decision d = dq.peek(stepIdx, x -> x.seat == seat && x.pickKinds.contains("clone"));
        if (d == null) {
            return null;
        }
        if (!d.picks.isEmpty() && d.picks.get(0).equalsIgnoreCase("Enter as itself")) {
            d.consumed = true;
            return false;
        }
        return true;
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
        Decision d = take(seat, x -> x.kind.equals("choose_n") && x.picks.size() == 1 && !DecisionQueue.isX(x) && number(x.picks.get(0)) != null);
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
