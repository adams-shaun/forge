// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * One Forge seat played by gorge's bot over a stdio bridge.
 *
 * The inverse of {@link ScriptedController}: that one scripts gorge's
 * decisions into Forge to check the rules, while this one lets Forge hold the
 * game and asks an external process (important: a gorge binary, not a link)
 * what gorge's policy would do. The two engines therefore play each other
 * with exactly one game state, which is what a head-to-head needs.
 *
 * Coverage is deliberately explicit: priority, attackers, blockers and
 * targets go over the wire; every other question falls through to the AI
 * parent and is counted in {@link #fallbacks()} by method name. A match that
 * leaned on the parent silently would be measuring the parent, so the count
 * is reported with every game.
 */
public final class BridgeController extends PlayerControllerAi {
    /** The seat's offer to the bridge: gorge's decision vocabulary. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Process proc;
    private final BufferedWriter to;
    private final BufferedReader from;
    private final int seat;
    private final Map<String, Integer> fallbacks = new LinkedHashMap<>();
    private boolean broken;

    public BridgeController(Game game, Player p, LobbyPlayerAi lp, int seat, Process bridge) {
        super(game, p, lp);
        this.seat = seat;
        this.proc = bridge;
        this.to = new BufferedWriter(new OutputStreamWriter(bridge.getOutputStream(), StandardCharsets.UTF_8));
        this.from = new BufferedReader(new InputStreamReader(bridge.getInputStream(), StandardCharsets.UTF_8));
    }

    public Map<String, Integer> fallbacks() {
        return fallbacks;
    }

    private void fallback(String what) {
        fallbacks.merge(what, 1, Integer::sum);
    }

    /** One ask, one answer. A refused or broken ask is a hard failure of the
     * bridged seat: falling back silently would report a match we did not
     * play. */
    private JsonObject ask(JsonObject req) {
        if (broken) {
            throw new IllegalStateException("bridge is broken; the match cannot continue");
        }
        try {
            to.write(GSON.toJson(req));
            to.write("\n");
            to.flush();
            String line = from.readLine();
            if (line == null) {
                broken = true;
                throw new IllegalStateException("bridge exited");
            }
            JsonObject ans = JsonParser.parseString(line).getAsJsonObject();
            if (System.getenv("BRIDGE_DEBUG") != null) {
                System.err.println("BRIDGE < " + GSON.toJson(req));
                System.err.println("BRIDGE > " + line);
            }
            if (ans.has("error")) {
                broken = true;
                throw new IllegalStateException("bridge refused: " + ans.get("error"));
            }
            return ans;
        } catch (IOException e) {
            broken = true;
            throw new IllegalStateException("bridge i/o: " + e.getMessage(), e);
        }
    }

    private static List<Integer> choices(JsonObject ans) {
        List<Integer> out = new ArrayList<>();
        JsonArray cs = ans.getAsJsonArray("choices");
        if (cs != null) {
            for (JsonElement e : cs) {
                out.add(e.getAsInt());
            }
        }
        return out;
    }

    // ---- seats ------------------------------------------------------------

    /** The wire's seat number for a player: its index in the game's
     * REGISTERED player list, which is the request's seat order and never
     * shrinks when a player loses. Every player reference that goes on the
     * wire (life keys, creature controllers, defenders, stack controllers)
     * goes through this one helper, so the bridge can never mix Forge's
     * player ids with seat indices. */
    private int seatOf(Player p) {
        int i = getGame().getRegisteredPlayers().indexOf(p);
        if (i < 0) {
            throw new IllegalStateException("player " + p + " is not a registered player of this game");
        }
        return i;
    }

    private boolean seatChecked;

    /** Asserts, on the first ask, that the deciding player is this
     * controller's seat and that its own creatures come out with
     * controller == me: a mismatch would make the policy see no attackers or
     * blockers of its own. */
    private void checkSeat(Player me) {
        if (seatChecked) {
            return;
        }
        if (seatOf(me) != seat || seatOf(player) != seat) {
            throw new IllegalStateException("bridge seat " + seat + " but the deciding player is seat "
                    + seatOf(me) + " (controller player seat " + seatOf(player) + ")");
        }
        for (Card c : me.getCreaturesInPlay()) {
            if (seatOf(c.getController()) != seat) {
                throw new IllegalStateException("own creature " + c + " reports controller seat "
                        + seatOf(c.getController()) + ", not " + seat);
            }
        }
        seatChecked = true;
    }

    // ---- board export -----------------------------------------------------

    /** The facts a seat may see: every battlefield, every life total, and the
     * deciding seat's own hand, graveyard, pool and command zone. An
     * opponent's hand never leaves this method. */
    private JsonObject board(Player me) {
        checkSeat(me);
        JsonObject b = new JsonObject();
        b.addProperty("me", seat);
        b.addProperty("is_main", isMainPhase(me));
        JsonObject life = new JsonObject();
        for (Player p : getGame().getRegisteredPlayers()) {
            life.addProperty(Integer.toString(seatOf(p)), p.getLife());
        }
        b.add("life", life);
        JsonArray cre = new JsonArray();
        for (Player p : getGame().getRegisteredPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isCreature()) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("id", c.getId());
                o.addProperty("controller", seatOf(c.getController()));
                o.addProperty("power", c.getNetPower());
                o.addProperty("toughness", c.getNetToughness());
                o.addProperty("damage", c.getDamage());
                o.addProperty("tapped", c.isTapped());
                JsonArray kw = new JsonArray();
                for (KeywordInterface k : c.getKeywords()) {
                    kw.add(k.toString());
                }
                o.add("keywords", kw);
                cre.add(o);
            }
        }
        b.add("creatures", cre);
        JsonArray cards = new JsonArray();
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            cards.add(cardFact(c, "hand", true));
        }
        for (Card c : me.getCardsIn(ZoneType.Graveyard)) {
            cards.add(cardFact(c, "graveyard", false));
        }
        for (Card c : me.getCardsIn(ZoneType.Command)) {
            cards.add(cardFact(c, "command", true));
        }
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            cards.add(cardFact(c, "battlefield", false));
        }
        // Exile is public; the seat's own exiled cards are what an
        // exile-zone cast (adventure, foretell, plot, impulse draw) names.
        for (Card c : me.getCardsIn(ZoneType.Exile)) {
            cards.add(cardFact(c, "exile", false));
        }
        b.add("cards", cards);
        JsonArray pool = new JsonArray();
        // gorge's pool is a fixed typed array; the bridge reports the plain
        // WUBRG+C counts, which is all the tap/priority gate reads.
        var mp = me.getManaPool();
        pool.add(mp.getAmountOfColor((byte) forge.card.MagicColor.WHITE));
        pool.add(mp.getAmountOfColor((byte) forge.card.MagicColor.BLUE));
        pool.add(mp.getAmountOfColor((byte) forge.card.MagicColor.BLACK));
        pool.add(mp.getAmountOfColor((byte) forge.card.MagicColor.RED));
        pool.add(mp.getAmountOfColor((byte) forge.card.MagicColor.GREEN));
        pool.add(mp.getAmountOfColor((byte) forge.card.MagicColor.COLORLESS));
        pool.add(0);
        b.add("pool", pool);
        return b;
    }

    /** Sorcery-speed iff the deciding seat is the active player in a main
     * phase: the same test gorge's seat adapter reads off the projected View. */
    private boolean isMainPhase(Player me) {
        var ph = getGame().getPhaseHandler();
        return me.equals(ph.getPlayerTurn())
                && (ph.getPhase() == forge.game.phase.PhaseType.MAIN1
                    || ph.getPhase() == forge.game.phase.PhaseType.MAIN2);
    }

    private static JsonObject cardFact(Card c, String zone, boolean castable) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        o.addProperty("zone", zone);
        o.addProperty("name", c.getName());
        o.addProperty("creature", c.isCreature());
        o.addProperty("power", c.isCreature() ? c.getNetPower() : 0);
        o.addProperty("cmc", c.getCMC());
        o.addProperty("basic", c.isBasicLand());
        o.addProperty("mana_cost", c.getManaCost() == null ? "" : c.getManaCost().toString());
        o.addProperty("castable", castable);
        o.addProperty("on_battlefield", "battlefield".equals(zone));
        if ("battlefield".equals(zone)) {
            // Non-mana activations this turn: botpolicy's per-source budget
            // (Card.Activated) is what ends a free repeatable ability's loop.
            int n = 0;
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.isActivatedAbility() && !sa.isManaAbility()) {
                    n += sa.getActivationsThisTurn();
                }
            }
            o.addProperty("activated", n);
        }
        return o;
    }

    private JsonObject ask(String kind, Player me, JsonArray options) {
        JsonObject req = new JsonObject();
        req.addProperty("v", 1);
        req.addProperty("kind", kind);
        req.addProperty("seat", seat);
        req.add("board", board(me));
        req.add("options", options);
        return ask(req);
    }

    /** The entity an attacker is attacking, in the wire's vocabulary: a seat,
     * plus the object id when it is a permanent (planeswalker or battle). */
    private JsonObject defenderFact(Card attacker, GameEntity defender) {
        JsonObject d = new JsonObject();
        d.addProperty("attacker", attacker.getId());
        if (defender instanceof Card) {
            Card c = (Card) defender;
            d.addProperty("seat", seatOf(c.getController()));
            d.addProperty("obj", c.getId());
        } else if (defender instanceof Player) {
            d.addProperty("seat", seatOf((Player) defender));
            d.addProperty("obj", 0);
        } else {
            throw new IllegalStateException("attacker " + attacker.getName()
                    + " has a defender this bridge cannot name: " + defender);
        }
        return d;
    }

    // ---- priority ---------------------------------------------------------

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // The FULL legal action set, not the parent's pick: the parent's
        // chooseSpellAbilityToPlay returns at most one ability (its answer),
        // so asking the bridge about it would never pose a choice.
        List<SpellAbility> all = offer();
        if (all.isEmpty()) {
            // Forge's "pass": an empty list is how a controller declines.
            return new ArrayList<>();
        }
        JsonArray opts = new JsonArray();
        for (int i = 0; i < all.size(); i++) {
            SpellAbility sa = all.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("index", i);
            o.addProperty("kind", actionKind(sa));
            Card host = sa.getHostCard();
            o.addProperty("label", label(sa));
            if (host != null) {
                o.addProperty("obj", host.getId());
            }
            String mode = mode(sa);
            if (!mode.isEmpty()) {
                o.addProperty("mode", mode);
            }
            if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null) {
                o.addProperty("mana_cost", sa.getPayCosts().getTotalMana().toString());
            }
            opts.add(o);
        }
        // Forge's controller declines by returning an empty list; gorge's
        // priority decision always offers a "pass" option and answers with an
        // index. Model the decline as a trailing pass option so the policy is
        // asked a question in its own vocabulary, then translate the answer.
        int passIndex = all.size();
        JsonObject pass = new JsonObject();
        pass.addProperty("index", passIndex);
        pass.addProperty("kind", "pass");
        pass.addProperty("label", "Pass priority");
        opts.add(pass);

        List<Integer> pick = choices(ask("priority", player, opts));
        if (pick.size() != 1 || pick.get(0) < 0 || pick.get(0) > passIndex) {
            throw new IllegalStateException("bridge priority answer " + pick + " is not one offered index (of "
                    + opts.size() + ")");
        }
        if (pick.get(0) == passIndex) {
            return new ArrayList<>(); // Forge's decline
        }
        return List.of(all.get(pick.get(0)));
    }

    /** The cards whose abilities the priority offer considers: Forge AI's own
     * candidate set (hand, own graveyard, every library's top card, own
     * command zone, every exile and every battlefield), so battlefield
     * activations and graveyard/exile/command casts are offered too. canPlay
     * then keeps only what this seat may actually activate or cast. */
    private CardCollection offerCards() {
        return forge.ai.ComputerUtilAbility.getAvailableCards(getGame(), player);
    }

    /** Every SpellAbility the seat can play AND pay for right now, in a stable
     * order: one option per distinct SpellAbility, so each alternative cost
     * (flashback, evoke, ...), each additional-cost variant and each single
     * optional cost (kicker) is its own option, deduped only on the identical
     * SpellAbility object. Affordability is Forge's own payment check
     * (ComputerUtilMana.canPayManaCost plus the non-mana additional costs),
     * so the bridge is never offered a cast Forge cannot pay for: before this
     * every hand card was offered whenever its timing allowed, botpolicy
     * picked the best one, the payment failed and the priority passed. */
    private List<SpellAbility> offer() {
        List<SpellAbility> base = new ArrayList<>();
        for (Card c : offerCards()) {
            base.addAll(c.getAllPossibleAbilities(player, false));
        }
        CardCollection lands = forge.ai.ComputerUtilAbility.getAvailableLandsToPlay(getGame(), player);
        if (lands != null) {
            for (Card land : lands) {
                for (SpellAbility sa : land.getAllPossibleAbilities(player, true)) {
                    if (sa.isLandAbility()) {
                        base.add(sa);
                    }
                }
            }
        }
        List<SpellAbility> out = new ArrayList<>();
        for (SpellAbility sa : base) {
            if (sa.isManaAbility()) {
                continue; // Forge pays; a mana ability is never a choice here
            }
            sa.setActivatingPlayer(player);
            if (sa.isLandAbility()) {
                // A land offers both a play ability and its mana abilities;
                // only the play ability is an action, once per land.
                addOnce(out, sa);
                continue;
            }
            for (SpellAbility v : forge.game.GameActionUtil.getAdditionalCostSpell(sa)) {
                v.setActivatingPlayer(player);
                addOnce(out, v);
                for (forge.game.spellability.OptionalCostValue ocv : forge.game.GameActionUtil.getOptionalCostValues(v)) {
                    SpellAbility k = forge.game.GameActionUtil.addOptionalCosts(v, List.of(ocv));
                    k.setActivatingPlayer(player);
                    addOnce(out, k);
                }
            }
        }
        out.removeIf(sa -> !playable(sa));
        // A land can be reported twice (hand ability and the land list);
        // keep the first.
        List<SpellAbility> unique = new ArrayList<>();
        java.util.Set<Integer> landHosts = new java.util.HashSet<>();
        for (SpellAbility sa : out) {
            if (sa.isLandAbility() && sa.getHostCard() != null && !landHosts.add(sa.getHostCard().getId())) {
                continue;
            }
            unique.add(sa);
        }
        return unique;
    }

    private static void addOnce(List<SpellAbility> out, SpellAbility sa) {
        for (SpellAbility o : out) {
            if (o == sa) {
                return;
            }
        }
        out.add(sa);
    }

    /** Legal now and payable now. */
    private boolean playable(SpellAbility sa) {
        if (!sa.canPlay()) {
            return false;
        }
        if (sa.isLandAbility()) {
            return true;
        }
        return affordable(sa);
    }

    private boolean affordable(SpellAbility sa) {
        return forge.ai.ComputerUtilMana.canPayManaCost(sa, player, 0, false)
                && forge.game.cost.CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, false, player);
    }

    /** gorge's option label shape: "Cast <name>" for a spell, "<name>: <text>"
     * for an ability (botpolicy reads an ability's first digit run as its
     * cost). */
    private static String label(SpellAbility sa) {
        Card host = sa.getHostCard();
        String name = host == null ? "?" : host.getName();
        if (sa.isLandAbility()) {
            return "Play " + name;
        }
        if (sa.isSpell()) {
            return "Cast " + name;
        }
        return name + ": " + sa.getDescription();
    }

    /** gorge's cast Mode for the variants botpolicy prices: a kicked cast and
     * a flashback cast. Every other alternative is an ordinary cast. */
    private static String mode(SpellAbility sa) {
        if (!sa.isSpell()) {
            return "";
        }
        if (sa.isFlashback()) {
            return "flashback";
        }
        for (forge.game.spellability.OptionalCost oc : sa.getOptionalCosts()) {
            String n = oc.name();
            if (n.startsWith("Kicker") || n.equals("Multikicker")) {
                return "kicked";
            }
        }
        return "";
    }

    private static String actionKind(SpellAbility sa) {
        if (sa.isLandAbility()) {
            return "play_land";
        }
        if (sa.isSpell()) {
            return "cast";
        }
        if (sa.isActivatedAbility()) {
            return "ability";
        }
        return "other";
    }

    // ---- combat -----------------------------------------------------------

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // getLegalAttackers() is not the set of legal attackers: it returns the
        // single best COMPLETE attack map (fewest restriction violations), and
        // the empty map is often cheapest. Offering it asked the bridge
        // "attack with nobody?" every combat, which is no question at all.
        // Build the offer from the creatures that can attack and let the
        // bridge choose the subset.
        // The controllers this defender must answer for: the player, plus the
        // planeswalkers and battles they protect (each is its own defender in
        // Forge, and each gets its own option), in a stable order.
        List<GameEntity> targets = new ArrayList<>();
        targets.add(attacker);
        for (Card c : attacker.getCardsIn(ZoneType.Battlefield)) {
            if (c.isPlaneswalker() || c.isBattle()) {
                targets.add(c);
            }
        }
        JsonArray opts = new JsonArray();
        JsonArray defs = new JsonArray();
        List<Card> list = new ArrayList<>();
        List<GameEntity> listDef = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            GameEntity chosen = null;
            for (GameEntity target : targets) {
                if (!forge.game.combat.CombatUtil.canAttack(c, target)) {
                    continue;
                }
                if (target == attacker) {
                    chosen = target; // a player attack is the default
                    break;
                }
                if (chosen == null) {
                    chosen = target;
                }
            }
            if (chosen == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("index", list.size());
            o.addProperty("kind", "attack");
            o.addProperty("obj", c.getId());
            o.addProperty("label", c.getName());
            opts.add(o);
            defs.add(defenderFact(c, chosen));
            list.add(c);
            listDef.add(chosen);
        }
        if (list.isEmpty()) {
            return;
        }
        JsonObject req = new JsonObject();
        req.addProperty("v", 1);
        req.addProperty("kind", "attackers");
        req.addProperty("seat", seat);
        req.add("board", board(attacker));
        req.add("options", opts);
        req.add("defenders", defs);
        List<Integer> pick = choices(ask(req));
        // One attacker is one declaration: the bridge answers option indices,
        // and an attacker offered against two defenders would arrive twice.
        java.util.Set<Card> declared = new java.util.HashSet<>();
        for (int i : pick) {
            if (i < 0 || i >= list.size()) {
                throw new IllegalStateException("bridge attacker index " + i + " out of range");
            }
            Card c = list.get(i);
            if (!declared.add(c)) {
                throw new IllegalStateException("bridge attacked with " + c.getName() + " twice");
            }
            combat.addAttacker(c, listDef.get(i));
        }
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            throw new IllegalStateException("bridge attack declaration invalid");
        }
    }


    /** One offered (blocker, attacker) pair, in the order the bridge saw it. */
    private record BlockPair(Card blocker, Card attacker) {
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        // A block is a (blocker, attacker) PAIR, so the offer is every legal
        // pair: one option per (blocker, attacker) combination, the same shape
        // gorge's own engine publishes (engine/rules/combat.go). Each option
        // carries the pair and the blocker's exclusivity Group, so the bridge
        // can answer a whole declaration -- including a multi-attacker combat
        // -- without knowing any rules beyond "one option per group".
        CardCollection attackers = combat.getAttackers();
        CardCollectionView blockers = defender.getCreaturesInPlay();
        if (attackers.isEmpty()) {
            return;
        }
        // canBlock(attacker, blocker, combat) resolves each block against the
        // current combat state, which is empty until the declaration lands --
        // so it answers false for every pair. Declaration is all-or-nothing
        // from gorge's side (one answer), so the state that check needs is the
        // one the declaration will produce: declare the attackers first, ask,
        // and restore the pre-ask state so the real declaration is the only
        // one that stands.
        var defenderByAttacker = new java.util.LinkedHashMap<Card, GameEntity>();
        for (Card a : attackers) {
            defenderByAttacker.put(a, combat.getDefenderByAttacker(a));
        }
        combat.clearAttackers();
        for (var e : defenderByAttacker.entrySet()) {
            combat.addAttacker(e.getKey(), e.getValue());
        }
        JsonArray opts = new JsonArray();
        List<BlockPair> pairs = new ArrayList<>();
        for (Card b : blockers) {
            if (b.isTapped() || !CombatUtil.canBlock(b, combat)) {
                continue;
            }
            for (Card a : attackers) {
                if (!CombatUtil.canBlock(a, b, combat)) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("index", pairs.size());
                o.addProperty("kind", "block");
                o.addProperty("obj", b.getId());
                o.addProperty("attacker", a.getId());
                o.addProperty("label", b.getName() + " blocks " + a.getName());
                // The exclusivity marker: this blocker may appear in at most
                // one chosen pair (CR 509.1a).
                o.addProperty("group", "blocker:" + b.getId());
                opts.add(o);
                pairs.add(new BlockPair(b, a));
            }
        }
        if (pairs.isEmpty()) {
            return;
        }
        List<Integer> pick = choices(ask("blockers", defender, opts));
        for (int i : pick) {
            if (i < 0 || i >= pairs.size()) {
                throw new IllegalStateException("bridge blocker index " + i + " out of range");
            }
        }
        // The pairs a declaration names must be a legal set: no blocker twice
        // and every pair one Forge actually offered. The bridge already
        // enforces its own answer's shape; this is the rules half.
        java.util.Set<Card> declared = new java.util.HashSet<>();
        for (int i : pick) {
            BlockPair p = pairs.get(i);
            if (!declared.add(p.blocker())) {
                throw new IllegalStateException("bridge used blocker " + p.blocker().getName() + " twice");
            }
        }
        for (int i : pick) {
            BlockPair p = pairs.get(i);
            combat.addBlocker(p.attacker(), p.blocker());
        }
    }

    // ---- target -----------------------------------------------------------

    @Override
    public boolean chooseTargetsFor(SpellAbility sa) {
        // Target selection needs Forge's candidate list per target slot; a
        // miss here is a rules difference, not a strategy one, so the parent
        // answers and the fallback is counted.
        fallback("chooseTargetsFor");
        return super.chooseTargetsFor(sa);
    }

    /** Starts the bridge process. The caller owns it and must close it. */
    public static Process start(String binary, long seed) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(binary, "-seed", Long.toString(seed));
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        return pb.start();
    }

    /** Closes stdin and waits briefly, then destroys. A bridge that will not
     * exit is not worth hanging a batch for. */
    public static void close(Process p) {
        if (p == null) {
            return;
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }
}
