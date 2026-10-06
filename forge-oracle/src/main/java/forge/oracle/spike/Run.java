package forge.oracle.spike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.StaticData;
import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.card.mana.ManaAtom;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** One scenario: build, place, drive the step machine, snapshot. */
public class Run {
    final JsonObject item;
    final JsonObject req;
    final JsonArray steps;
    final boolean noMana;
    Game game;
    final Player[] seats = new Player[2];
    final List<JsonObject> snaps = new ArrayList<>();
    final Map<String, Integer> setupRefs = new HashMap<>();
    int stepIdx = -1; // -1: setup, waiting for turn 1 main 1
    boolean acted; // the current step's action has been handed to the engine
    boolean done;
    String harness;
    int played, payCalls;
    final List<String> notes = new ArrayList<>();
    // Target answers for the step being cast/activated.
    List<String> pendingTargets = new ArrayList<>();
    SpellAbility actedSa;

    Run(JsonObject req, boolean noMana) {
        this.req = req;
        this.item = req.has("item") ? req.getAsJsonObject("item") : req;
        this.steps = item.has("steps") ? item.getAsJsonArray("steps") : new JsonArray();
        this.noMana = noMana;
    }

    // ---- build and placement ------------------------------------------------

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
        JsonObject setup = item.has("setup") ? item.getAsJsonObject("setup") : new JsonObject();
        game.getTriggerHandler().setSuppressAllTriggers(true);
        for (int i = 0; i < 2; i++) {
            JsonObject s = setup.has("p" + i) ? setup.getAsJsonObject("p" + i) : new JsonObject();
            Player p = seats[i];
            int named = 0;
            Map<String, Integer> nth = new HashMap<>();
            // gorge's runner order: battlefield, hand, graveyard, library, exile, command, library_top
            for (String n : names(s, "battlefield")) {
                Card c = create(n, p);
                bind(i, n, c, nth);
                p.getZone(ZoneType.Hand).add(c);
                game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
                named++;
            }
            for (String z : new String[] {"hand", "graveyard", "library", "exile"}) {
                ZoneType zt = z.equals("hand") ? ZoneType.Hand : z.equals("graveyard") ? ZoneType.Graveyard
                        : z.equals("library") ? ZoneType.Library : ZoneType.Exile;
                for (String n : names(s, z)) {
                    Card c = create(n, p);
                    bind(i, n, c, nth);
                    p.getZone(zt).add(c);
                    named++;
                }
            }
            List<String> top = names(s, "library_top");
            named += top.size();
            int filler = Math.max(0, 40 - named);
            for (int k = 0; k < filler; k++) {
                p.getZone(ZoneType.Library).add(create("Wastes", p));
            }
            for (int k = top.size() - 1; k >= 0; k--) {
                Card c = create(top.get(k), p);
                p.getZone(ZoneType.Library).add(c, 0); // index 0 = top
            }
            for (String n : top) {
                // bind in top-first order after the rest (gorge binds library_top last)
                for (Card c : p.getZone(ZoneType.Library).getCards()) {
                    if (c.getName().equals(n) && !setupRefs.containsValue(c.getId())) {
                        bind(i, n, c, nth);
                        break;
                    }
                }
            }
            if (s.has("life")) {
                p.setLife(s.get("life").getAsInt(), null);
            }
            if (s.has("tapped")) {
                for (String n : names(s, "tapped")) {
                    for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                        if (c.getName().equals(n)) {
                            c.setTapped(true);
                        }
                    }
                }
            }
        }
        game.getTriggerHandler().setSuppressAllTriggers(false);
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            c.setSickness(false);
        }
        game.getAction().checkStaticAbilities();
    }

    private void bind(int seat, String name, Card c, Map<String, Integer> nth) {
        int k = nth.merge(name, 1, Integer::sum);
        setupRefs.put("p" + seat + ":" + name + "#" + k, c.getId());
    }

    private Card create(String name, Player p) {
        PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
        if (pc == null) {
            StaticData.instance().attemptToLoadCard(name);
            pc = StaticData.instance().getCommonCards().getCard(name);
        }
        if (pc == null) {
            throw new IllegalArgumentException("Couldn't find a card: " + name);
        }
        Card c = Card.fromPaperCard(pc, p);
        c.setGameTimestamp(game.getNextTimestamp());
        return c;
    }

    private static List<String> names(JsonObject s, String k) {
        List<String> out = new ArrayList<>();
        if (s.has(k)) {
            for (JsonElement e : s.getAsJsonArray(k)) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    // ---- refs ---------------------------------------------------------------

    GameEntity resolve(String ref) {
        if (ref.matches("p\\d+")) {
            return seats[Integer.parseInt(ref.substring(1))];
        }
        String key = ref.matches(".*#\\d+$") ? ref : ref + "#1";
        Integer id = setupRefs.get(key);
        if (id == null) {
            throw new IllegalStateException("harness: ref " + ref + " names no setup object");
        }
        Card c = game.findById(id);
        if (c == null) {
            throw new IllegalStateException("harness: ref " + ref + " (id " + id + ") not found");
        }
        return c;
    }

    // ---- the drive ----------------------------------------------------------

    void drive() {
        game.setAge(GameStage.Play);
        PhaseHandler ph = game.getPhaseHandler();
        ph.setupFirstTurn(seats[0], null);
        int guard = 0;
        while (!done && harness == null && !game.isGameOver()) {
            if (++guard > 500) {
                throw new IllegalStateException("harness: no progress after 500 loop steps at step " + stepIdx);
            }
            ph.mainLoopStep();
            if (ph.getTurn() > 1) {
                throw new IllegalStateException("harness: passed turn 1 at step " + stepIdx);
            }
        }
        if (harness != null) {
            throw new IllegalStateException(harness);
        }
    }

    /** Called by a seat's controller every time it holds priority. */
    List<SpellAbility> onPriority(ScriptedController ctl) {
        if (done || harness != null) {
            return null;
        }
        PhaseHandler ph = game.getPhaseHandler();
        Player p = ctl.getPlayer();
        if (stepIdx < 0) {
            if (ph.getTurn() == 1 && ph.is(PhaseType.MAIN1) && p == ph.getPlayerTurn() && game.getStack().isEmpty()) {
                snaps.add(snapshot("setup", p));
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
            String op = st.get("op").getAsString();
            int seat = st.has("seat") ? st.get("seat").getAsInt() : 0;
            switch (op) {
                case "cast":
                case "activate":
                    if (!acted) {
                        if (p != seats[seat]) {
                            return null;
                        }
                        if (st.has("mana") && !noMana) {
                            addMana(p, st.get("mana").getAsString());
                        }
                        SpellAbility sa = op.equals("cast") ? spellFor(st) : abilityFor(st);
                        pendingTargets = new ArrayList<>();
                        if (st.has("targets")) {
                            for (JsonElement e : st.getAsJsonArray("targets")) {
                                pendingTargets.add(e.getAsString());
                            }
                        }
                        sa.setActivatingPlayer(p);
                        acted = true;
                        actedSa = sa;
                        return Lists.newArrayList(sa);
                    }
                    // Priority came back to the caster: the action completed or failed.
                    if (game.getStack().isEmpty() || !actedSa.getHostCard().equals(game.getStack().peekAbility().getHostCard())) {
                        if (!actedSa.isManaAbility()) {
                            harness = "harness: step " + stepIdx + " (" + op + ") " + str(st, "card") + " was not put on the stack (cost unpaid or illegal)";
                            return null;
                        }
                    }
                    if (!pendingTargets.isEmpty()) {
                        notes.add("leftover targets at step " + stepIdx + ": " + pendingTargets);
                    }
                    snaps.add(snapshot("step " + stepIdx + " (" + op + ")", p));
                    stepIdx++;
                    acted = false;
                    continue;
                case "resolve":
                    // Completion is checked first: a resolve over an empty stack is a no-op (gorge's runner).
                    if (game.getStack().isEmpty() && p == ph.getPlayerTurn()) {
                        snaps.add(snapshot("step " + stepIdx + " (resolve)", p));
                        stepIdx++;
                        acted = false;
                        continue;
                    }
                    acted = true;
                    return null; // pass
                default:
                    harness = "harness: op " + op + " unsupported by the P0 spike";
                    return null;
            }
        }
    }

    boolean chooseTargets(ScriptedController ctl, SpellAbility sa) {
        if (pendingTargets.isEmpty()) {
            throw new IllegalStateException("Missing target answer at step " + stepIdx + " for " + sa);
        }
        int min = sa.getMinTargets();
        int max = sa.getMaxTargets();
        int added = 0;
        while (!pendingTargets.isEmpty() && added < Math.max(max, 1)) {
            String ref = pendingTargets.get(0);
            GameEntity o = resolve(ref);
            if (!sa.canTarget(o)) {
                throw new IllegalStateException("harness: step " + stepIdx + " target " + ref + " is not a legal target");
            }
            sa.getTargets().add(o);
            notes.add("chooseTargetsFor step " + stepIdx + ": " + ref + " -> " + o + " (sa api " + sa.getApi() + ")");
            pendingTargets.remove(0);
            added++;
        }
        return added >= min;
    }

    private void addMana(Player p, String letters) {
        // A dummy source, as GameState.updateManaPool does for puzzle pools.
        Card dummy = new Card(-777777, game);
        dummy.setOwner(p);
        for (char ch : letters.toUpperCase().toCharArray()) {
            byte b;
            switch (ch) {
                case 'W': b = (byte) ManaAtom.WHITE; break;
                case 'U': b = (byte) ManaAtom.BLUE; break;
                case 'B': b = (byte) ManaAtom.BLACK; break;
                case 'R': b = (byte) ManaAtom.RED; break;
                case 'G': b = (byte) ManaAtom.GREEN; break;
                case 'C': b = (byte) ManaAtom.COLORLESS; break;
                default: throw new IllegalArgumentException("mana letter " + ch);
            }
            p.getManaPool().addMana(new forge.game.mana.Mana(b, dummy, null, p));
        }
    }

    private SpellAbility spellFor(JsonObject st) {
        Card c = (Card) resolve(str(st, "card"));
        for (SpellAbility sa : c.getSpells()) {
            return sa;
        }
        throw new IllegalStateException("harness: " + str(st, "card") + " has no spell ability");
    }

    /** Ability identity: the gorge ability line's params against each SA's params. */
    private SpellAbility abilityFor(JsonObject st) {
        Card c = (Card) resolve(str(st, "card"));
        String line = null;
        if (req.has("abilities")) {
            JsonObject ab = req.getAsJsonObject("abilities");
            if (ab.has(String.valueOf(stepIdx))) {
                line = ab.get(String.valueOf(stepIdx)).getAsString();
            }
        }
        if (line == null) {
            throw new IllegalStateException("harness: no ability line for step " + stepIdx);
        }
        Map<String, String> want = AbilityFactory.getMapParams(line);
        String kind = line.substring(0, 2);
        List<SpellAbility> exact = new ArrayList<>();
        List<SpellAbility> loose = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell() || sa.isLandAbility()) {
                continue;
            }
            Map<String, String> got = new LinkedHashMap<>(sa.getMapParams());
            seen.add(String.valueOf(got.keySet()));
            if (got.equals(want)) {
                exact.add(sa);
                continue;
            }
            // keyword-derived fallback: API + cost (+ Keyword param when the gorge line names one)
            String api = sa.getApi() == null ? "" : sa.getApi().name();
            String wantApi = want.getOrDefault(kind, "");
            String cost = sa.getPayCosts() == null ? "" : sa.getPayCosts().toSimpleString();
            String wantCost = want.getOrDefault("Cost", "");
            if (api.equalsIgnoreCase(wantApi) && normCost(cost).equals(normCost(wantCost))) {
                loose.add(sa);
            }
        }
        if (exact.size() == 1) {
            notes.add("identity: exact params match at step " + stepIdx);
            return exact.get(0);
        }
        if (exact.isEmpty() && loose.size() == 1) {
            notes.add("identity: api+cost fallback at step " + stepIdx);
            return loose.get(0);
        }
        throw new IllegalStateException("harness: ability identity at step " + stepIdx + ": exact=" + exact.size() + " loose=" + loose.size());
    }

    private static String normCost(String s) {
        return s.replaceAll("[{}\\s]", "").toUpperCase();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    // ---- snapshot -----------------------------------------------------------

    static String stepName(PhaseType pt) {
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
            default: return pt.name();
        }
    }

    private int seatOf(Player p) {
        return p == seats[0] ? 0 : p == seats[1] ? 1 : -1;
    }

    JsonObject snapshot(String checkpoint, Player prio) {
        PhaseHandler ph = game.getPhaseHandler();
        JsonObject s = new JsonObject();
        s.addProperty("checkpoint", checkpoint);
        s.addProperty("turn", ph.getTurn());
        s.addProperty("step", stepName(ph.getPhase()));
        s.addProperty("active", seatOf(ph.getPlayerTurn()));
        s.addProperty("priority", seatOf(prio));
        s.addProperty("over", game.isGameOver());
        JsonArray players = new JsonArray();
        for (int i = 0; i < 2; i++) {
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
                        ex.add(c.getName());
                    }
                }
            }
            Collections.sort(ex);
            JsonArray exa = new JsonArray();
            ex.forEach(exa::add);
            po.add("exile", exa);
            po.add("command", new JsonArray());
            List<Card> lib = Lists.newArrayList(p.getCardsIn(ZoneType.Library));
            po.addProperty("library_count", lib.size());
            JsonArray top = new JsonArray();
            for (int k = 0; k < lib.size() && k < 5; k++) {
                top.add(lib.get(k).getName());
            }
            po.add("library_top", top);
            po.addProperty("pool", pool(p));
            players.add(po);
        }
        s.add("players", players);
        JsonArray perms = new JsonArray();
        Combat combat = game.getCombat();
        for (Player owner : seats) {
            for (Card c : owner.getCardsIn(ZoneType.Battlefield)) {
                JsonObject o = new JsonObject();
                o.addProperty("name", c.getName());
                o.addProperty("controller", seatOf(c.getController()));
                o.addProperty("owner", seatOf(c.getOwner()));
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
                List<String> types = new ArrayList<>();
                for (CardType.CoreType ct : t.getCoreTypes()) {
                    types.add(ct.name());
                }
                for (CardType.Supertype st : t.getSupertypes()) {
                    types.add(st.name());
                }
                for (String st : t.getSubtypes()) {
                    types.add(st);
                }
                Collections.sort(types);
                JsonArray ta = new JsonArray();
                types.forEach(ta::add);
                o.add("types", ta);
                if (t.hasAllCreatureTypes()) {
                    o.addProperty("all_creature_types", true);
                }
                o.addProperty("colors", colors(c.getColor()));
                TreeSet<String> kws = new TreeSet<>();
                for (KeywordInterface ki : c.getKeywords()) {
                    kws.add(ki.getTitle());
                }
                JsonArray ka = new JsonArray();
                kws.forEach(ka::add);
                o.add("keywords", ka);
                GameEntity at = c.getEntityAttachedTo();
                if (at instanceof Card) {
                    o.addProperty("attached_to", ((Card) at).getName());
                } else if (at instanceof Player) {
                    o.addProperty("attached_to", "p" + seatOf((Player) at));
                }
                o.addProperty("attacking", combat != null && combat.isAttacking(c));
                o.addProperty("blocking", combat != null && combat.isBlocking(c));
                perms.add(o);
            }
        }
        s.add("permanents", perms);
        JsonArray stack = new JsonArray();
        for (SpellAbilityStackInstance si : game.getStack()) { // top first (addFirst)
            JsonObject o = new JsonObject();
            o.addProperty("kind", si.isSpell() ? "spell" : "ability");
            o.addProperty("source", si.getSourceCard() == null ? "?" : si.getSourceCard().getName());
            o.addProperty("controller", seatOf(si.getActivatingPlayer()));
            stack.add(o);
        }
        s.add("stack", stack);
        return s;
    }

    private static JsonArray names(Iterable<Card> cs, boolean sorted) {
        List<String> ns = new ArrayList<>();
        for (Card c : cs) {
            ns.add(c.getName());
        }
        if (sorted) {
            Collections.sort(ns);
        }
        JsonArray a = new JsonArray();
        ns.forEach(a::add);
        return a;
    }

    private static JsonObject counters(Multiset<CounterType> m) {
        JsonObject o = new JsonObject();
        TreeSet<String> keys = new TreeSet<>();
        Map<String, Integer> agg = new HashMap<>();
        for (Multiset.Entry<CounterType> e : m.entrySet()) {
            String k = e.getElement().getName();
            keys.add(k);
            agg.merge(k, e.getCount(), Integer::sum);
        }
        for (String k : keys) {
            o.addProperty(k, agg.get(k));
        }
        return o;
    }

    private static String colors(ColorSet cs) {
        StringBuilder b = new StringBuilder();
        if (cs.hasWhite()) b.append('W');
        if (cs.hasBlue()) b.append('U');
        if (cs.hasBlack()) b.append('B');
        if (cs.hasRed()) b.append('R');
        if (cs.hasGreen()) b.append('G');
        return b.toString();
    }

    private static String pool(Player p) {
        StringBuilder b = new StringBuilder();
        char[] letters = {'W', 'U', 'B', 'R', 'G', 'C'};
        for (int i = 0; i < 6; i++) {
            int n = p.getManaPool().getAmountOfColor(ManaAtom.MANATYPES[i]);
            for (int k = 0; k < n; k++) {
                b.append(letters[i]);
            }
        }
        return b.toString();
    }
}
