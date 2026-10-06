// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.StaticData;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

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
 *     ChangesZoneAll are suppressed while placing, and so is CounterAdded
 *     while a Saga enters (its chapter abilities are CounterAdded triggers with
 *     Chapter$, CardFactoryUtil "Chapter"). Every other trigger the placement
 *     causes STAYS live: a planeswalker's entry loyalty counters fire
 *     CounterAdded/CounterAddedOnce triggers on permanents already placed
 *     (Inspired Tethermage watching Ajani Goldmane), as gorge keeps them
 *     (P0-REPORT: suppressing all triggers would drop them). The active
 *     player is set before placement, because Forge's TriggerHandler runs no
 *     trigger while there is no active player.</li>
 * <li>Then, per placement as gorge emits them: back face, tapped, setup
 *     counters (through the counter table, triggers live), speed, life.</li>
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
        try {
            for (int i = 0; i < m.seats.length; i++) {
                placeSeat(m, i, turn);
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
            // Setup permanents were present before turn 1: clear the
            // entered-this-turn (and -last-turn) provenance placement recorded.
            for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Library, ZoneType.Exile}) {
                p.getZone(z).resetCardsAddedThisTurn();
                p.getZone(z).resetCardsAddedThisTurn();
            }
        }
        game.getAction().checkStaticAbilities();
    }

    private static void placeSeat(StepMachine m, int i, int turn) {
        JsonObject s = m.req.seat(i);
        Player p = m.seats[i];
        if (s.has("command") && !Request.strings(s, "command").isEmpty()) {
            throw new HarnessError("command zone setup unsupported");
        }
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
        int named = 0;
        // battlefield
        for (String n : bf) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            putOntoBattlefield(m.game, p, c);
            unlockNamedDoor(m.game, p, c, n);
            if (containsName(backFace, n)) {
                flipToBack(c, n);
            }
            if (containsName(tapped, n)) {
                c.setTapped(true);
            }
            Map<String, Integer> cs = counterFor(counters, n);
            if (cs != null) {
                addCounters(m.game, p, c, cs);
            }
            named++;
        }
        // hand, graveyard (zone order = setup order, oldest first), library, exile
        for (String n : Request.strings(s, "hand")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            p.getZone(ZoneType.Hand).add(c);
            named++;
        }
        for (String n : Request.strings(s, "graveyard")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            p.getZone(ZoneType.Graveyard).add(c);
            named++;
        }
        List<Card> libNamed = new ArrayList<>();
        for (String n : Request.strings(s, "library")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            libNamed.add(c);
            named++;
        }
        for (String n : Request.strings(s, "exile")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            p.getZone(ZoneType.Exile).add(c);
            named++;
        }
        List<Card> top = new ArrayList<>();
        for (String n : Request.strings(s, "library_top")) {
            Card c = create(m, n, p);
            m.refs.bind(i, n, c.getId());
            top.add(c);
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
        JsonElement life = s.get("life");
        if (life != null && life.isJsonPrimitive()) {
            p.setLife(life.getAsInt(), null);
        }
    }

    /** Hand first, then a real move to the battlefield, so ETB replacement
     * effects (enters tapped, as-enters choices, entry counters) apply as
     * gorge's MoveZone applies them. A Saga's own chapter trigger is held off. */
    static void putOntoBattlefield(Game game, Player p, Card c) {
        p.getZone(ZoneType.Hand).add(c);
        boolean saga = c.getType().hasSubtype("Saga");
        if (saga) {
            game.getTriggerHandler().suppressMode(TriggerType.CounterAdded);
        }
        try {
            game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
        } finally {
            if (saga) {
                game.getTriggerHandler().clearSuppression(TriggerType.CounterAdded);
            }
        }
    }

    /** A Room named by one door's name stands with that door unlocked, as
     * gorge's setup places it (name and colour of the door). The unlock
     * triggers are held off: setup fires no entry-like triggers. */
    static void unlockNamedDoor(Game game, Player p, Card c, String name) {
        if (!c.getType().hasSubtype("Room")) {
            return;
        }
        for (CardStateName sn : new CardStateName[] {CardStateName.LeftSplit, CardStateName.RightSplit}) {
            if (c.hasState(sn) && name.equalsIgnoreCase(c.getState(sn).getName())) {
                TriggerHandler th = game.getTriggerHandler();
                th.suppressMode(TriggerType.UnlockDoor);
                th.suppressMode(TriggerType.FullyUnlock);
                try {
                    c.unlockRoom(p, sn);
                } finally {
                    th.clearSuppression(TriggerType.UnlockDoor);
                    th.clearSuppression(TriggerType.FullyUnlock);
                }
                return;
            }
        }
    }

    /** gorge's setup FlipFace: the permanent, already placed on its front face,
     * now stands on its back face. Not a transform action, so no trigger. */
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
            CounterType t = CounterType.getType(e.getKey());
            if (t == null) {
                throw new HarnessError("unknown counter kind " + e.getKey());
            }
            c.addCounter(t, e.getValue(), p, table);
        }
        table.replaceCounterEffect(game, null, false, false, null);
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
