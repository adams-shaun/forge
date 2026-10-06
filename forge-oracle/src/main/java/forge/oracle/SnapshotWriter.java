// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * The checkpoint snapshot: exactly the field set the XMage driver's
 * ScenarioReplay.snapshot emits (ScenarioReplay.java:2269-2381), with no
 * "offered" and no "decisions", so gorge's oraclediff.CompareOpts reads Forge
 * rows unchanged on either side (DESIGN 6.7). Forge's values are normalized
 * to gorge's vocabulary here: step names (main1, combat-damage, ...), WUBRG
 * colour letters, WUBRGC pool letters, counter kinds as gorge spells them
 * (P1P1, LOYALTY, CHARGE), the stack top first, a face-down permanent's name
 * empty (CR 708.2a). The static helpers take plain values and are unit-tested
 * without a Game.
 */
public final class SnapshotWriter {
    public static final int LIBRARY_TOP_N = 5;
    private static final char[] POOL_LETTERS = {'W', 'U', 'B', 'R', 'G', 'C'};

    private SnapshotWriter() {
    }

    // ---- pure normalization ----------------------------------------------

    /** gorge's step name (state/ids.go stepNames) for a Forge phase. */
    public static String stepName(PhaseType pt) {
        if (pt == null) {
            return "";
        }
        switch (pt) {
            case UNTAP: return "untap";
            case UPKEEP: return "upkeep";
            case DRAW: return "draw";
            case MAIN1: return "main1";
            case COMBAT_BEGIN: return "begin-combat";
            case COMBAT_DECLARE_ATTACKERS: return "declare-attackers";
            case COMBAT_DECLARE_BLOCKERS: return "declare-blockers";
            case COMBAT_FIRST_STRIKE_DAMAGE:
            case COMBAT_DAMAGE: return "combat-damage";
            case COMBAT_END: return "end-combat";
            case MAIN2: return "main2";
            case END_OF_TURN: return "end";
            case CLEANUP: return "cleanup";
            default: throw new IllegalArgumentException("unmapped phase " + pt);
        }
    }

    /** WUBRG letters in WUBRG order; colourless is "". */
    public static String colors(boolean w, boolean u, boolean b, boolean r, boolean g) {
        StringBuilder s = new StringBuilder();
        if (w) s.append('W');
        if (u) s.append('U');
        if (b) s.append('B');
        if (r) s.append('R');
        if (g) s.append('G');
        return s.toString();
    }

    /** Pool letters from per-type amounts in ManaAtom.MANATYPES order
     * (W, U, B, R, G, C). Restricted ("spend only on ...") mana is counted as
     * plain mana, as the XMage driver does: the restriction is not compared. */
    public static String pool(int[] amounts) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < POOL_LETTERS.length && i < amounts.length; i++) {
            for (int k = 0; k < amounts[i]; k++) {
                s.append(POOL_LETTERS[i]);
            }
        }
        return s.toString();
    }

    /** A counter kind as gorge spells it: an enum counter by its constant
     * name (P1P1, M1M1, LOYALTY, CHARGE, LORE), anything else (keyword and
     * custom counters) upper-cased with spaces removed, which is what the
     * comparator's normCounter does to both sides. */
    public static String counterName(String enumName, String displayName) {
        if (enumName != null && !enumName.isEmpty()) {
            return enumName;
        }
        if ("+1/+1".equals(displayName)) {
            return "P1P1";
        }
        if ("-1/-1".equals(displayName)) {
            return "M1M1";
        }
        return displayName.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    }

    public static String counterName(CounterType t) {
        return counterName(t instanceof CounterEnumType ? ((CounterEnumType) t).name() : null, t.getName());
    }

    /** Sorted type words: core types, supertypes and subtypes, as named. The
     * comparator lowercases them. */
    public static List<String> sortedTypes(Collection<String> core, Collection<String> sup, Collection<String> sub) {
        List<String> out = new ArrayList<>(core.size() + sup.size() + sub.size());
        out.addAll(core);
        out.addAll(sup);
        out.addAll(sub);
        Collections.sort(out);
        return out;
    }

    /** The name gorge's snapshot gives a card outside the battlefield: its
     * printed (face 0) name. A face-down card (hideaway, foretell) is still
     * named, as gorge and the XMage driver name it there; a split or Room card
     * is named by its first half, gorge's face 0. */
    public static String zoneName(Card c) {
        if (c.isFaceDown() && c.hasState(CardStateName.Original)) {
            return c.getState(CardStateName.Original).getName();
        }
        if (c.isSplitCard() && c.getCurrentStateName() == CardStateName.Original && c.hasState(CardStateName.LeftSplit)) {
            return c.getState(CardStateName.LeftSplit).getName();
        }
        return c.getName();
    }

    // ---- the snapshot ------------------------------------------------------

    public static JsonObject snapshot(String checkpoint, Game game, Player[] seats) {
        PhaseHandler ph = game.getPhaseHandler();
        JsonObject s = new JsonObject();
        s.addProperty("checkpoint", checkpoint);
        s.addProperty("turn", ph.getTurn());
        s.addProperty("step", stepName(ph.getPhase()));
        s.addProperty("active", seatOf(seats, ph.getPlayerTurn()));
        s.addProperty("priority", seatOf(seats, ph.getPriorityPlayer()));
        s.addProperty("over", game.isGameOver());
        JsonArray players = new JsonArray();
        for (int i = 0; i < seats.length; i++) {
            players.add(player(i, seats));
        }
        s.add("players", players);
        JsonArray perms = new JsonArray();
        Combat combat = game.getCombat();
        for (Player owner : seats) {
            for (Card c : owner.getCardsIn(ZoneType.Battlefield)) {
                perms.add(permanent(c, seats, combat));
            }
        }
        s.add("permanents", perms);
        JsonArray stack = new JsonArray();
        for (SpellAbilityStackInstance si : game.getStack()) { // top first (MagicStack.add is addFirst)
            JsonObject o = new JsonObject();
            o.addProperty("kind", si.isSpell() ? "spell" : "ability");
            Card src = si.getSourceCard();
            o.addProperty("source", src == null ? "?" : zoneName(src));
            o.addProperty("controller", seatOf(seats, si.getActivatingPlayer()));
            stack.add(o);
        }
        s.add("stack", stack);
        return s;
    }

    static int seatOf(Player[] seats, Player p) {
        for (int i = 0; i < seats.length; i++) {
            if (seats[i] == p) {
                return i;
            }
        }
        return -1;
    }

    private static JsonObject player(int i, Player[] seats) {
        Player p = seats[i];
        JsonObject po = new JsonObject();
        po.addProperty("seat", i);
        po.addProperty("life", p.getLife());
        JsonObject pc = counters(p.getCounters());
        if (pc.size() > 0) {
            po.add("counters", pc);
        }
        po.add("hand", names(p.getCardsIn(ZoneType.Hand), true));
        po.add("graveyard", names(p.getCardsIn(ZoneType.Graveyard), false));
        List<String> ex = new ArrayList<>();
        for (Player q : seats) {
            for (Card c : q.getCardsIn(ZoneType.Exile)) {
                if (c.getOwner() == p) {
                    ex.add(zoneName(c));
                }
            }
        }
        Collections.sort(ex);
        JsonArray exa = new JsonArray();
        ex.forEach(exa::add);
        po.add("exile", exa);
        po.add("command", new JsonArray());
        List<Card> lib = new ArrayList<>();
        p.getCardsIn(ZoneType.Library).forEach(lib::add);
        po.addProperty("library_count", lib.size());
        JsonArray top = new JsonArray();
        for (int k = 0; k < lib.size() && k < LIBRARY_TOP_N; k++) {
            top.add(zoneName(lib.get(k)));
        }
        po.add("library_top", top);
        int[] amounts = new int[6];
        for (int k = 0; k < 6; k++) {
            amounts[k] = p.getManaPool().getAmountOfColor((byte) ManaAtom.MANATYPES[k]);
        }
        po.addProperty("pool", pool(amounts));
        return po;
    }

    private static JsonObject permanent(Card c, Player[] seats, Combat combat) {
        JsonObject o = new JsonObject();
        o.addProperty("name", c.isFaceDown() ? "" : c.getName());
        o.addProperty("controller", seatOf(seats, c.getController()));
        o.addProperty("owner", seatOf(seats, c.getOwner()));
        o.addProperty("token", c.isToken());
        o.addProperty("tapped", c.isTapped());
        o.addProperty("face_down", c.isFaceDown());
        if (c.isCreature()) {
            o.addProperty("pt", c.getNetPower() + "/" + c.getNetToughness());
        }
        o.addProperty("damage", c.getDamage());
        JsonObject cs = counters(c.getCounters());
        if (cs.size() > 0) {
            o.add("counters", cs);
        }
        CardTypeView t = c.getType();
        List<String> core = new ArrayList<>();
        for (CardType.CoreType ct : t.getCoreTypes()) {
            core.add(ct.name());
        }
        List<String> sup = new ArrayList<>();
        for (CardType.Supertype st : t.getSupertypes()) {
            sup.add(st.name());
        }
        List<String> sub = new ArrayList<>();
        for (String st : t.getSubtypes()) {
            sub.add(st);
        }
        JsonArray ta = new JsonArray();
        sortedTypes(core, sup, sub).forEach(ta::add);
        o.add("types", ta);
        if (t.hasAllCreatureTypes()) {
            o.addProperty("all_creature_types", true);
        }
        ColorSet cl = c.getColor();
        o.addProperty("colors", colors(cl.hasWhite(), cl.hasBlue(), cl.hasBlack(), cl.hasRed(), cl.hasGreen()));
        TreeSet<String> kws = new TreeSet<>();
        for (KeywordInterface ki : c.getKeywords()) {
            kws.add(ki.getTitle());
        }
        JsonArray ka = new JsonArray();
        kws.forEach(ka::add);
        o.add("keywords", ka);
        GameEntity at = c.getEntityAttachedTo();
        if (at instanceof Card) {
            Card host = (Card) at;
            o.addProperty("attached_to", host.isFaceDown() ? "" : host.getName());
        } else if (at instanceof Player) {
            o.addProperty("attached_to", "p" + seatOf(seats, (Player) at));
        }
        o.addProperty("attacking", combat != null && combat.isAttacking(c));
        o.addProperty("blocking", combat != null && combat.isBlocking(c));
        return o;
    }

    private static JsonArray names(Iterable<Card> cs, boolean sorted) {
        List<String> ns = new ArrayList<>();
        for (Card c : cs) {
            ns.add(zoneName(c));
        }
        if (sorted) {
            Collections.sort(ns);
        }
        JsonArray a = new JsonArray();
        ns.forEach(a::add);
        return a;
    }

    static JsonObject counters(Multiset<CounterType> m) {
        Map<String, Integer> agg = new TreeMap<>();
        for (Multiset.Entry<CounterType> e : m.entrySet()) {
            if (e.getCount() != 0) {
                agg.merge(counterName(e.getElement()), e.getCount(), Integer::sum);
            }
        }
        JsonObject o = new JsonObject();
        agg.forEach(o::addProperty);
        return o;
    }
}
