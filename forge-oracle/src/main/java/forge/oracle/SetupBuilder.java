// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterKeywordType;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.item.PaperToken;

/**
 * Scenario setup with gorge's xmageFixture placement contract (DESIGN 6.3,
 * rules/oracle_run.go build and rules/oracle_setup_triggers.go):
 *
 * <ul>
 * <li>Cards are created in gorge's deck order (per seat: battlefield, hand,
 *     graveyard, library, exile, library_top, then the Wastes filler), so card
 *     ids, which unbound refs count in, follow gorge's object order; each named
 *     card binds its setup ref.</li>
 * <li>Battlefield placement fires NO entry triggers: ChangesZone and
 *     ChangesZoneAll are suppressed while placing. A Saga keeps its entry
 *     lore counter and chapter I, front face or back (gorge f788f3c31 and
 *     setupBackFaceDropsChapter: only a back-face placement whose entering
 *     face is not itself a Saga has no chapter to fire). Every other trigger
 *     the placement causes STAYS live: a planeswalker's entry loyalty counters
 *     fire CounterAdded/CounterAddedOnce triggers on permanents already
 *     placed (Inspired Tethermage watching Ajani Goldmane), as gorge keeps
 *     them (P0-REPORT: suppressing all triggers would drop them). The active
 *     player is set before placement, because Forge's TriggerHandler runs no
 *     trigger while there is no active player.</li>
 * <li>Placement keeps every entry counter replacement the scenario's counters
 *     field is stated net of: the real battlefield move grants them (loyalty,
 *     defense, a front-face Saga's lore, K:etbCounter, K:ETBReplacement and a
 *     printed R:Event$ Moved body), exactly as gorge's placement re-runs them.
 *     A tap an entry replacement applies is undone and the scenario's tapped
 *     field re-states it.</li>
 * <li>A Room placed by setup enters with neither unlocked designation: it is
 *     not cast as either door (CR 709.5d), so its name is the empty string,
 *     as gorge's fieldName and the XMage driver place it. The scenario name
 *     only binds the card; it does not unlock the door it names. An explicit
 *     unlock is the CR 709.5e special action, which no scenario step drives.</li>
 * <li>Then, per placement as gorge emits them: back face, tapped, setup
 *     counters (through the counter table, triggers live), speed. Every seat's life is
 *     written first, before any permanent exists (placeLife).</li>
 * <li>Setup {@code tokens} (forge-gui res/tokenscripts stems) are minted at the
 *     end of their seat's placement, after its library filler, in list order,
 *     and added to the battlefield silently: no CreateToken and no
 *     ChangesZone event, so no "whenever you create a token" trigger fires
 *     (gorge's setup drops the triggers it queues). Their {@code counters} are
 *     a complete set written straight on, with no counter-table replacement
 *     and no CounterAdded trigger (XMage's placeSetupToken).</li>
 * <li>A battlefield card that this seat's {@code attached} list names is not
 *     moved in through the hand and GameAction.moveTo (an Aura with no chosen
 *     host would land in hand): it is added to the zone directly, then bound,
 *     back-faced, tapped and counted like any other battlefield card. After
 *     every seat is placed, each entry's {@code to} ref (any seat: a
 *     battlefield card or a setup token) is attached with Attached suppressed,
 *     in seat then list order (XMage's placeSetupAttached).</li>
 * <li>A seat's {@code tapped_after_untap} refs (seat-relative: a name, a
 *     #k ordinal, or token:Sub) are tapped by the raw setter at the first
 *     priority of the scenario turn (StepMachine, after its untap step, which
 *     would otherwise untap them): gorge taps them after the turn's untap step
 *     and drops every trigger the taps queue (CR 502.3).</li>
 * <li>Commander (a {@code "format": "commander"} scenario, CR 903; gorge's
 *     oracleSeat Command, Commanders, CommanderCasts, CommanderDamage): a
 *     seat's {@code command} cards are created after its exile cards and
 *     before library_top (gorge's placement order), put straight into the
 *     command zone and seated as its commanders, which creates the Commander
 *     Effect (castable from there, the CR 903.9b replacement). A
 *     {@code commanders} entry names one of the seat's other placements, which
 *     stays where it is placed but is a commander too (gorge seats it in the
 *     command zone at genesis and moves it out; the move is placement, not a
 *     CR 903.9a arrival). {@code commander_casts} seeds the tax count (CR
 *     903.8) and {@code commander_damage} the combat damage taken from a
 *     commander, keyed by its full "pN:Name" ref (CR 903.10a), after every
 *     seat is placed. In a constructed scenario the {@code command} cards go
 *     to the command zone with no commander status, as gorge's runner puts
 *     them, and the other three fields are harness errors.</li>
 * <li>Library: the XMage driver's layout, top first: library_top, the filler
 *     (40 - named cards, at least turn/2+1 after turn 1), then the named
 *     library cards. gorge's own library order comes from its genesis deal,
 *     so a fixture naming library cards compares with library_top ignored.</li>
 * </ul>
 */
final class SetupBuilder {
    static final String FILLER = "Wastes";
    static final int DECK = 40;

    private SetupBuilder() {
    }

    static void place(StepMachine m) {
        Game game = m.game;
        TriggerHandler th = game.getTriggerHandler();
        game.getPhaseHandler().setPlayerTurn(m.seats[0]);
        int turn = m.req.turn();
        th.suppressMode(TriggerType.ChangesZone);
        th.suppressMode(TriggerType.ChangesZoneAll);
        Map<Card, String> links = new LinkedHashMap<>();
        try {
            // Every seat's life goes in before any permanent exists (see placeLife).
            for (int i = 0; i < m.seats.length; i++) {
                placeLife(m.seats[i], m.req.seat(i));
            }
            for (int i = 0; i < m.seats.length; i++) {
                placeSeat(m, i, turn, links);
            }
            // Every seat's Aura links: gorge's runner places them after all
            // seats and their tokens.
            for (Map.Entry<Card, String> e : links.entrySet()) {
                linkAttached(m, e.getKey(), e.getValue());
            }
            // Commander damage keys are full refs, possibly to a later seat's
            // commander, so they bind once every seat is placed (gorge's
            // placeCommanderDamage runs after placeAttached).
            for (int i = 0; i < m.seats.length; i++) {
                placeCommanderDamage(m, i);
            }
        } finally {
            th.clearSuppression(TriggerType.ChangesZone);
            th.clearSuppression(TriggerType.ChangesZoneAll);
            th.clearSuppression(TriggerType.CounterAdded);
        }
        for (Player p : m.seats) {
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                c.setSickness(false);
            }
        }
        // Setup restates the board as it stood before the scenario turn; it is
        // not events of that turn. Run Forge's own end-of-turn bookkeeping
        // (Game.onCleanupPhase drains it into Player/Card.onCleanupPhase) so
        // every per-turn history starts empty exactly as a real game leaves
        // it: counters put (Lasting Tarfire's X), life gained and how many
        // times (the "first time each turn" triggers), zone changes, damage,
        // activations -- and whatever cleanup grows to cover next. Resetting
        // the fields by hand missed lifeGainedTimesThisTurn,
        // lifeGainedByTeamThisTurn and countersAddedThisTurn.
        //
        // The first pass also rolls setup events into the *last*-turn
        // mirrors: Player.onCleanupPhase copies lifeLostThisTurn to
        // lifeLostLastTurn, and Card.onCleanupPhase copies this turn's
        // damage received to last turn's. Setup life below 20 goes through
        // loseLife, so one pass made it "life lost last turn" for First
        // Response, Paladin of Atonement and Feast on the Fallen. A second
        // pass, with this turn's history now empty, overwrites those mirrors
        // with empty too. Both passes are Forge's own cleanup, so this keeps
        // covering every last-turn mirror cleanup grows later instead of
        // listing the fields here.
        game.onCleanupPhase();
        game.onCleanupPhase();
        game.getAction().checkStaticAbilities();
    }

    /** A seat's setup "life" is a state, not an event (CR 103.4: it is the
     * starting life total). Player.setLife turns a raise into gainLife and a
     * drop into loseLife (CR 119.5), so with a permanent already in play an
     * Angel of Vitality's replacement (CR 614) made 25 read 26 and a life-loss
     * trigger could fire. It is written for every seat before the first
     * permanent is placed, while no replacement effect or trigger exists to
     * see it; a setup life below 20 still leaves lifeLostThisTurn behind, which
     * the cleanup passes in place() clear. Called before the seat loop, not per
     * seat, so a later seat's battlefield cannot see an earlier seat's life
     * either. */
    private static void placeLife(Player p, JsonObject s) {
        JsonElement life = s.get("life");
        if (life != null && life.isJsonPrimitive()) {
            p.setLife(life.getAsInt(), null);
        }
    }

    private static void placeSeat(StepMachine m, int i, int turn, Map<Card, String> links) {
        JsonObject s = m.req.seat(i);
        Player p = m.seats[i];
        boolean commander = m.req.commander();
        validateCommanders(s, i, commander);
        List<String> listed = Request.strings(s, "commanders");
        // This seat's commanders in placement order (gorge's Config.Commanders
        // order): a commanders entry where it is placed, a command card at its
        // command placement.
        List<Card> seated = new ArrayList<>();
        List<String> bf = Request.strings(s, "battlefield");
        List<String> backFace = Request.strings(s, "back_face");
        List<String> tapped = Request.strings(s, "tapped");
        Map<String, Map<String, Integer>> counters = counters(s);
        for (String b : backFace) {
            if (!containsName(bf, b)) {
                throw new HarnessError("setup back_face " + b + " is not on p" + i + "'s battlefield");
            }
        }
        for (String c : counters.keySet()) {
            if (!containsName(bf, c)) {
                throw new HarnessError("counters name " + c + ", which is not on p" + i + "'s battlefield");
            }
        }
        // gorge places an Aura attached: it goes straight onto the battlefield
        // and is linked after every seat is placed.
        Map<Integer, String> auraHost = attachedHosts(s, "p" + i, bf);
        int named = 0;
        int occurrence = 0;
        // battlefield
        for (String n : bf) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            boolean back = containsName(backFace, n);
            if (auraHost.containsKey(++occurrence)) {
                // A back-face Aura is added straight to the battlefield (no
                // GameAction.moveTo to carry the face), so resolve it here.
                if (back) {
                    flipToBack(c, n);
                }
                p.getZone(ZoneType.Battlefield).add(c);
                links.put(c, auraHost.get(occurrence));
            } else {
                c = putOntoBattlefield(m.game, p, c, back);
            }
            if (containsName(tapped, n)) {
                c.setTapped(true);
            }
            Map<String, Integer> cs = counterFor(counters, n);
            if (cs != null) {
                addCounters(m.game, p, c, cs);
            }
            if (containsName(listed, n)) {
                seated.add(c);
            }
            named++;
        }
        // hand, graveyard (zone order = setup order, oldest first), library, exile
        for (String n : Request.strings(s, "hand")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            p.getZone(ZoneType.Hand).add(c);
            if (containsName(listed, n)) {
                seated.add(c);
            }
            named++;
        }
        for (String n : Request.strings(s, "graveyard")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            p.getZone(ZoneType.Graveyard).add(c);
            if (containsName(listed, n)) {
                seated.add(c);
            }
            named++;
        }
        List<Card> libNamed = new ArrayList<>();
        for (String n : Request.strings(s, "library")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            libNamed.add(c);
            if (containsName(listed, n)) {
                seated.add(c);
            }
            named++;
        }
        for (String n : Request.strings(s, "exile")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            p.getZone(ZoneType.Exile).add(c);
            if (containsName(listed, n)) {
                seated.add(c);
            }
            named++;
        }
        // command: after exile, before library_top (gorge's placement order).
        for (String n : Request.strings(s, "command")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            p.getZone(ZoneType.Command).add(c);
            if (commander) {
                seated.add(c);
            }
            named++;
        }
        List<Card> top = new ArrayList<>();
        for (String n : Request.strings(s, "library_top")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            top.add(c);
            if (containsName(listed, n)) {
                seated.add(c);
            }
            named++;
        }
        int filler = Math.max(0, DECK - named);
        if (turn > 1) {
            filler = Math.max(filler, turn / 2 + 1);
        }
        List<Card> fill = new ArrayList<>();
        for (int k = 0; k < filler; k++) {
            fill.add(create(m, FILLER, p));
        }
        List<Card> lib = new ArrayList<>(top);
        lib.addAll(fill);
        for (int k = libNamed.size() - 1; k >= 0; k--) {
            lib.add(libNamed.get(k));
        }
        for (Card c : lib) {
            p.getZone(ZoneType.Library).add(c);
        }
        JsonElement speed = s.get("speed");
        if (speed != null && speed.isJsonPrimitive() && speed.getAsInt() > 0) {
            int v = speed.getAsInt();
            if (v > 4) {
                throw new HarnessError("speed " + v + " out of range 0..4");
            }
            p.setSpeed(v);
            p.createSpeedEffect();
        }
        seatCommanders(p, i, seated, Request.counts(s, "commander_casts"));
        placePlayerCounters(p, i, s.get("player_counters"));
        placeTokens(m, i);
    }

    /** A seat's setup "player_counters" ([{kind, n}], gorge's
     * SetupPlayerCounter): each entry is the COMPLETE count of its kind
     * (also 0), written as recorded state with no counter event and no
     * trigger (CR 122.1; energy is a player counter, CR 107.14), like the
     * life read beside it. Only an enum counter can sit on a player (ENERGY,
     * POISON, EXPERIENCE, RAD, ...): counterType files an unrecognised kind
     * as a custom counter, which a typo must not silently become. */
    static void placePlayerCounters(Player p, int seat, JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return;
        }
        for (JsonElement el : e.getAsJsonArray()) {
            JsonObject pc = el.getAsJsonObject();
            String kind = pc.get("kind").getAsString();
            int n = pc.get("n").getAsInt();
            if (n < 0) {
                throw new HarnessError("setup player_counters " + kind + " on p" + seat + " is negative: " + n);
            }
            CounterType t = counterType(kind);
            if (!(t instanceof CounterEnumType)) {
                throw new HarnessError("setup player_counters: unknown player counter kind " + kind + " on p" + seat);
            }
            p.setCounters(t, n, null, false);
        }
    }

    /** CR 903.3c: a commander deck has one commander or a partner pair. */
    static final int MAX_COMMANDERS = 2;
    /** CR 704.6c: 21 combat damage from one commander loses at the first
     * state-based check, so no setup stands on more than 20. */
    static final int MAX_COMMANDER_DAMAGE = 20;

    /** gorge's validateSetupCommanders: the Commander fields a seat may carry,
     * checked before any card is created so a typo fails loudly. */
    static void validateCommanders(JsonObject s, int i, boolean commander) {
        List<String> listed = Request.strings(s, "commanders");
        Map<String, Integer> casts = Request.counts(s, "commander_casts");
        Map<String, Integer> damage = Request.counts(s, "commander_damage");
        if (!commander) {
            if (!listed.isEmpty()) {
                throw new HarnessError("setup: p" + i + " commanders needs a commander-format scenario");
            }
            if (!casts.isEmpty()) {
                throw new HarnessError("setup: p" + i + " commander_casts needs a commander-format scenario");
            }
            if (!damage.isEmpty()) {
                throw new HarnessError("setup: p" + i + " commander_damage needs a commander-format scenario");
            }
            return;
        }
        List<String> command = Request.strings(s, "command");
        List<String> seen = new ArrayList<>();
        for (String n : listed) {
            if (n.trim().isEmpty()) {
                throw new HarnessError("setup: p" + i + " commanders has an empty name");
            }
            if (containsName(seen, n)) {
                throw new HarnessError("setup: p" + i + " commanders lists \"" + n + "\" more than once");
            }
            seen.add(n);
            if (containsName(command, n)) {
                throw new HarnessError("setup: p" + i + " commanders names \"" + n + "\", which is already a command placement");
            }
            int placed = 0;
            for (String zone : new String[] {"battlefield", "hand", "graveyard", "library", "library_top", "exile"}) {
                for (String x : Request.strings(s, zone)) {
                    if (x.equalsIgnoreCase(n)) {
                        placed++;
                    }
                }
            }
            if (placed == 0) {
                throw new HarnessError("setup: p" + i + " commanders names \"" + n + "\", which is not one of its placements");
            }
            if (placed > 1) {
                throw new HarnessError("setup: p" + i + " commanders names \"" + n + "\", which is placed more than once");
            }
        }
        if (command.size() + listed.size() > MAX_COMMANDERS) {
            throw new HarnessError("setup: p" + i + " has " + (command.size() + listed.size())
                    + " commanders (want at most " + MAX_COMMANDERS + "; CR 903.3c)");
        }
        for (Map.Entry<String, Integer> e : casts.entrySet()) {
            if (e.getValue() < 1) {
                throw new HarnessError("setup: p" + i + " commander_casts \"" + e.getKey() + "\" = " + e.getValue() + " (want at least 1; CR 903.8)");
            }
            if (!containsName(command, e.getKey()) && !containsName(listed, e.getKey())) {
                throw new HarnessError("setup: p" + i + " commander_casts names \"" + e.getKey() + "\", which is not one of its commanders");
            }
        }
        for (Map.Entry<String, Integer> e : damage.entrySet()) {
            if (e.getValue() < 1 || e.getValue() > MAX_COMMANDER_DAMAGE) {
                throw new HarnessError("setup: p" + i + " commander_damage \"" + e.getKey() + "\" = " + e.getValue()
                        + " (want 1.." + MAX_COMMANDER_DAMAGE + "; CR 903.10a)");
            }
        }
    }

    /** Seats a seat's commanders, in placement order, and seeds each one's
     * tax count (commander_casts, by front-face name). addCommander creates the
     * seat's Commander Effect in its command zone on the first one. */
    private static void seatCommanders(Player p, int i, List<Card> seated, Map<String, Integer> casts) {
        for (Card c : seated) {
            p.addCommander(c);
        }
        for (Map.Entry<String, Integer> e : casts.entrySet()) {
            Card cmd = null;
            for (Card c : seated) {
                if (frontFaceName(c).equalsIgnoreCase(e.getKey()) || c.getName().equalsIgnoreCase(e.getKey())) {
                    cmd = c;
                    break;
                }
            }
            if (cmd == null) {
                throw new HarnessError("setup: p" + i + " commander_casts names \"" + e.getKey() + "\", which is not one of its commanders");
            }
            for (int k = 0; k < e.getValue(); k++) {
                p.incCommanderCast(cmd);
            }
        }
    }

    /** A seat's commander_damage: combat damage it has taken from the
     * commander each full ref names, with no life change (CR 903.10a). */
    private static void placeCommanderDamage(StepMachine m, int i) {
        for (Map.Entry<String, Integer> e : Request.counts(m.req.seat(i), "commander_damage").entrySet()) {
            GameEntity g;
            try {
                g = m.entity(e.getKey());
            } catch (HarnessError err) {
                throw new HarnessError("setup: p" + i + " commander_damage names \"" + e.getKey() + "\", which is not one of the setup's placements");
            }
            if (!(g instanceof Card) || !((Card) g).isRealCommander()) {
                throw new HarnessError("setup: p" + i + " commander_damage names \"" + e.getKey() + "\", which is not a commander");
            }
            m.seats[i].addCommanderDamage((Card) g, e.getValue());
        }
    }

    /** A commander's identity in the snapshot tallies: its printed front face,
     * so a transformed or face-down commander keeps one key. */
    static String frontFaceName(Card c) {
        if (c.hasState(CardStateName.Original)) {
            String n = c.getState(CardStateName.Original).getName();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        return c.getName();
    }

    /** Every seat's setup "tapped_after_untap" refs, tapped by the raw setter
     * (no Tapped trigger). Called by StepMachine at the first priority of the
     * scenario turn, i.e. after that turn's untap step: tapping them in place()
     * would be undone by the untap step setupFirstTurn runs. gorge taps them
     * after the untap step and drops the triggers the taps queue (CR 502.3). */
    static void tapAfterUntap(StepMachine m) {
        for (int i = 0; i < m.seats.length; i++) {
            for (String ref : Request.strings(m.req.seat(i), "tapped_after_untap")) {
                m.card("p" + i + ":" + ref).setTapped(true);
            }
        }
    }

    /** A seat's setup "tokens": Forge token scripts (res/tokenscripts stems),
     * minted after the seat's cards and added silently, as gorge's setup puts
     * them there (no CreateToken, no ChangesZone, no CounterAdded trigger). */
    private static void placeTokens(StepMachine m, int i) {
        JsonObject s = m.req.seat(i);
        Player p = m.seats[i];
        JsonElement e = s.get("tokens");
        if (e == null || !e.isJsonArray()) {
            return;
        }
        for (JsonElement el : e.getAsJsonArray()) {
            JsonObject spec = el.getAsJsonObject();
            String stem = Request.str(spec, "script");
            if (stem.isEmpty()) {
                throw new HarnessError("setup token without a script on p" + i);
            }
            PaperToken pt = StaticData.instance().getAllTokens().getToken(stem, CardEdition.UNKNOWN_CODE);
            if (pt == null) {
                throw new HarnessError("setup token " + stem + " has no Forge token script");
            }
            Card t = CardFactory.getCard(pt, p, m.game);
            t.setGameTimestamp(m.game.getNextTimestamp());
            p.getZone(ZoneType.Battlefield).add(t);
            JsonElement cs = spec.get("counters");
            if (cs != null && cs.isJsonObject()) {
                Map<String, Integer> kinds = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> kind : cs.getAsJsonObject().entrySet()) {
                    kinds.put(kind.getKey(), kind.getValue().getAsInt());
                }
                setTokenCounters(t, kinds);
            }
        }
    }

    /** Sets a setup token's counters outright. Card.setCounters(Multiset) is
     * the one setter that also builds a keyword counter's static ability and
     * refreshes keywords (CR 122.1b: a lifelink counter grants lifelink); the
     * GameEntity setter used before only wrote the counter table. It still
     * does no counter-table replacement and runs no trigger. */
    static void setTokenCounters(Card t, Map<String, Integer> kinds) {
        Multiset<CounterType> all = HashMultiset.create();
        for (Map.Entry<String, Integer> kind : kinds.entrySet()) {
            all.setCount(counterType(kind.getKey()), kind.getValue());
        }
        t.setCounters(all);
    }

    /** The Forge counter type for a gorge counter kind. Gorge names a keyword
     * counter in capitals with no spaces (LIFELINK, FIRSTSTRIKE); Forge's
     * CounterType.getType matches the keyword's own spelling ("Lifelink",
     * "First Strike") and files anything else as a custom counter, which
     * grants nothing. Match the keyword counters first, ignoring case, spaces
     * and underscores. */
    static CounterType counterType(String kind) {
        String want = kind.replace(" ", "").replace("_", "");
        for (CounterType ct : CounterKeywordType.getValues()) {
            if (ct.isKeywordCounter() && ct.toString().replace(" ", "").equalsIgnoreCase(want)) {
                return ct;
            }
        }
        CounterType ct = CounterType.getType(kind);
        if (ct == null) {
            throw new HarnessError("unknown counter kind " + kind);
        }
        return ct;
    }

    /** Links an Aura (or Equipment) placed by setup to its bearer, with the
     * Attached trigger held off: gorge's setup places it attached and drops
     * the triggers it queues. overwrite=true skips canBeAttached, the setup
     * state is authoritative. */
    static void linkAttached(StepMachine m, Card aura, String toRef) {
        GameEntity host = m.entity(toRef);
        TriggerHandler th = m.game.getTriggerHandler();
        th.suppressMode(TriggerType.Attached);
        try {
            aura.attachToEntity(host, null, true);
        } finally {
            th.clearSuppression(TriggerType.Attached);
        }
        if (aura.getEntityAttachedTo() != host) {
            throw new HarnessError("setup attached: " + aura.getName() + " to " + toRef + " refused");
        }
    }

    /** The battlefield occurrence (1-based, the k-th placement of that name) of
     * each card the seat's attached list names -> the full "to" ref. A card ref
     * that names no battlefield placement of the seat is a harness error. */
    private static Map<Integer, String> attachedHosts(JsonObject s, String seatRef, List<String> bf) {
        Map<Integer, String> out = new LinkedHashMap<>();
        JsonElement e = s.get("attached");
        if (e == null || !e.isJsonArray()) {
            return out;
        }
        for (JsonElement el : e.getAsJsonArray()) {
            JsonObject spec = el.getAsJsonObject();
            String ref = seatRef + ":" + Request.str(spec, "card");
            String to = Request.str(spec, "to");
            if (to.isEmpty()) {
                throw new HarnessError("setup attached " + ref + " names no \"to\" ref");
            }
            int k = occurrenceOf(bf, ref);
            if (k < 0) {
                throw new HarnessError("setup attached " + ref + " is not on " + seatRef + "'s battlefield");
            }
            out.put(k, to);
        }
        return out;
    }

    /** The 1-based occurrence of the battlefield card a seat-relative ref names
     * ("Name" = first, "Name#k" = k-th), or -1. A token ref never names a
     * battlefield card. */
    static int occurrenceOf(List<String> battlefield, String ref) {
        RefTable.Ref r = RefTable.parse(ref);
        if (r.token || r.player) {
            return -1;
        }
        int seen = 0;
        for (int i = 0; i < battlefield.size(); i++) {
            if (!battlefield.get(i).equalsIgnoreCase(r.name)) {
                continue;
            }
            if (++seen == r.nth) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Resolve the back face first, then a real move to the battlefield, so the
     * move carries the face that stands. A modal DFC whose front is an instant
     * or sorcery cannot enter at all (CR 307.4), so flipping after the move
     * left it in hand (CR 712.8f); flipping first also makes the entering
     * face's own entry replacements and counters apply (a back-face Saga's
     * entry lore and chapter I, or Sea Gate, Reborn's Moved replacement), as
     * gorge's stageBackFace placement does. Every entry counter replacement the
     * setup contract restates is applied by that move (loyalty, a Saga's lore,
     * a Battle's defense, a K:etbCounter or K:ETBReplacement body, a printed
     * R:Event$ Moved body such as Tonberry's stun counter); the scenario's
     * counters field is stated net of those grants, so no counter the move
     * applied is removed. A tap an entry replacement applies is undone because
     * the tapped field re-states it. Returns the permanent on the battlefield,
     * which may be a new object.
     *
     * <p>A {@code back_face} request whose resolved face is itself an instant or
     * sorcery is stated straight onto the battlefield, as the attached-Aura
     * path and gorge's setup MoveZone fold do: setup restates a board, and CR
     * 307.4 (which makes a real {@code GameAction.moveTo} refuse the card and
     * leave it in hand) governs a game, not the fixture. This keeps the same
     * rule for both face layouts, front-spell/back-permanent and
     * front-permanent/back-spell.</p> */
    static Card putOntoBattlefield(Game game, Player p, Card c, boolean backFace) {
        if (backFace) {
            flipToBack(c, c.getName());
            if (c.isInstant() || c.isSorcery()) {
                p.getZone(ZoneType.Battlefield).add(c);
                return c;
            }
        }
        p.getZone(ZoneType.Hand).add(c);
        TriggerHandler th = game.getTriggerHandler();
        // A tap an entry replacement applies is not an event of the scenario.
        th.suppressMode(TriggerType.Taps);
        Card moved;
        try {
            moved = game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
        } finally {
            th.clearSuppression(TriggerType.Taps);
        }
        if (moved == null || !moved.isInPlay()) {
            moved = c;
        }
        moved.setTapped(false);
        return moved;
    }

    /** gorge's setup FlipFace: the card stands on its back face before the
     * placement move. Not a transform action, so no trigger; the move then
     * enters the back face itself (its own entry replacements, and a Saga's
     * entry lore counter and chapter I, are the real move's, as gorge's
     * stageBackFace placement). */
    static void flipToBack(Card c, String name) {
        if (!c.isDoubleFaced() && !c.isModal()) {
            throw new HarnessError("setup back_face: " + name + " is not double-faced");
        }
        c.setState(CardStateName.Backside, true);
        c.setBackSide(true);
    }

    static void addCounters(Game game, Player p, Card c, Map<String, Integer> kinds) {
        GameEntityCounterTable table = new GameEntityCounterTable();
        for (Map.Entry<String, Integer> e : kinds.entrySet()) {
            if (e.getValue() == 0) {
                throw new HarnessError("setup counters " + e.getKey() + " on " + c.getName() + " is zero");
            }
            CounterType t = counterType(e.getKey());
            c.addCounter(t, e.getValue(), p, table);
        }
        // etb: the counters are the board as stated, so no AddCounter
        // replacement (Doc Samson's +1, a doubler) applies; the triggers stay.
        table.replaceCounterEffect(game, null, false, true, null);
        table.triggerCountersPutAll(game);
    }

    private static Card create(StepMachine m, String name, Player p) {
        PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
        if (pc == null) {
            StaticData.instance().attemptToLoadCard(name);
            pc = StaticData.instance().getCommonCards().getCard(name);
        }
        if (pc == null) {
            throw new HarnessError("Couldn't find a card: " + name);
        }
        Card c = Card.fromPaperCard(pc, p);
        c.setGameTimestamp(m.game.getNextTimestamp());
        return c;
    }

    /** card name -> counter kind -> amount, kinds sorted as gorge emits them. */
    private static Map<String, Map<String, Integer>> counters(JsonObject s) {
        Map<String, Map<String, Integer>> out = new TreeMap<>();
        JsonElement e = s.get("counters");
        if (e == null || !e.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> byCard : e.getAsJsonObject().entrySet()) {
            Map<String, Integer> kinds = new TreeMap<>();
            if (byCard.getValue().isJsonObject()) {
                for (Map.Entry<String, JsonElement> k : byCard.getValue().getAsJsonObject().entrySet()) {
                    kinds.put(k.getKey(), k.getValue().getAsInt());
                }
            }
            out.put(byCard.getKey(), kinds);
        }
        return out;
    }

    private static Map<String, Integer> counterFor(Map<String, Map<String, Integer>> counters, String name) {
        for (Map.Entry<String, Map<String, Integer>> e : counters.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean containsName(List<String> names, String name) {
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
