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
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.ability.ApiType;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController.BinaryChoiceType;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

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
    /** forgebridge.Version on the Go side: v2 adds the turn/step/stack facts,
     * per-card tapped/produces/instant facts and the own decklist; v3 adds the
     * target, mulligan and trigger_optional asks and the facts their gorge
     * arms read (foreign battlefield cards, colour, type words, the public
     * colour census). Field map: four-yay
     * docs/design/2026-10-20-bridge-v3-fieldmap.md. */
    static final int WIRE_VERSION = 3;

    /** The spell whose targets are being chosen while it is still in its
     * origin zone (Forge chooses a cast's targets before moving it to the
     * stack; gorge poses the ask with the spell already ON the stack, CR
     * 601.2b/c). While set, board() leaves it out of the zone facts and the
     * hand size and puts it on top of the stack facts. */
    private Card pendingCast;

    /** True while the pregame mulligan asks are posed: there is no phase yet,
     * and gorge's mulligan round runs before turn 1 in its untap step. */
    private boolean pregame;

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

    private final Map<String, Integer> asksByKind = new LinkedHashMap<>();
    private long optionsTotal;
    private int optionsMax;

    /** Asks answered by the bridge, by kind, with the offered option counts. */
    public JsonObject askStats() {
        JsonObject o = new JsonObject();
        JsonObject by = new JsonObject();
        int n = 0;
        for (var e : asksByKind.entrySet()) {
            by.addProperty(e.getKey(), e.getValue());
            n += e.getValue();
        }
        o.addProperty("asks", n);
        o.add("by_kind", by);
        o.addProperty("options_total", optionsTotal);
        o.addProperty("options_max", optionsMax);
        return o;
    }

    /** One ask, one answer. A refused or broken ask is a hard failure of the
     * bridged seat: falling back silently would report a match we did not
     * play. */
    private JsonObject ask(JsonObject req) {
        if (broken) {
            throw new IllegalStateException("bridge is broken; the match cannot continue");
        }
        asksByKind.merge(req.get("kind").getAsString(), 1, Integer::sum);
        askCount++;
        int nOpts = req.getAsJsonArray("options").size();
        optionsTotal += nOpts;
        optionsMax = Math.max(optionsMax, nOpts);
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
        var ph = getGame().getPhaseHandler();
        // gorge fills IsMain from the step alone (any player's main phase,
        // botpolicy combat.go's g.Step.IsMain() and the view half's Phase),
        // and MyTurn separately; the old active-player-only is_main made
        // every opponent's-main window read as "not main".
        b.addProperty("is_main", ph.getPhase() == PhaseType.MAIN1 || ph.getPhase() == PhaseType.MAIN2);
        b.addProperty("first_main", ph.getPhase() == PhaseType.MAIN1);
        b.addProperty("my_turn", me.equals(ph.getPlayerTurn()));
        b.addProperty("step", pregame && ph.getPhase() == null ? "untap" : stepName(ph.getPhase()));
        JsonObject hand = new JsonObject();
        JsonObject library = new JsonObject();
        for (Player p : getGame().getRegisteredPlayers()) {
            int h = p.getCardsIn(ZoneType.Hand).size();
            if (pendingCast != null && p.equals(me) && pendingCast.isInZone(ZoneType.Hand)) {
                h--;
            }
            hand.addProperty(Integer.toString(seatOf(p)), h);
            library.addProperty(Integer.toString(seatOf(p)), p.getCardsIn(ZoneType.Library).size());
        }
        b.add("hand_size", hand);
        b.add("library_size", library);
        b.add("stack", stack());
        b.add("own_deck", ownDeck(me));
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
            if (c.equals(pendingCast)) {
                continue;
            }
            cards.add(cardFact(c, "hand", true));
        }
        for (Card c : me.getCardsIn(ZoneType.Graveyard)) {
            if (c.equals(pendingCast)) {
                continue;
            }
            // gorge's zone gate: a graveyard card is castable with Flashback.
            cards.add(cardFact(c, "graveyard", c.hasKeyword(Keyword.FLASHBACK)));
        }
        for (Card c : me.getCardsIn(ZoneType.Command)) {
            if (c.equals(pendingCast)) {
                continue;
            }
            cards.add(cardFact(c, "command", true));
        }
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            cards.add(cardFact(c, "battlefield", false));
        }
        // Exile is public; the seat's own exiled cards are what an
        // exile-zone cast (adventure, foretell, plot, impulse draw) names.
        for (Card c : me.getCardsIn(ZoneType.Exile)) {
            if (c.equals(pendingCast)) {
                continue;
            }
            cards.add(cardFact(c, "exile", false));
        }
        // v3: every other seat's battlefield permanents are public (CR
        // 400.2); gorge's adapter fills their worth facts for the A2 target
        // rules and the card-worth readers, with the seat-relative facts
        // left zero (the Go side enforces that from "controller").
        for (Player p : getGame().getRegisteredPlayers()) {
            if (p.equals(me)) {
                continue;
            }
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                cards.add(foreignFact(c));
            }
        }
        b.add("cards", cards);
        b.add("public_cards", publicCards());
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

    /** gorge's step names (state.Step's String form), the exact strings
     * the bridge's Go side parses back with state.ParseStep. Forge's
     * first-strike damage step is gorge's combat-damage step. */
    static String stepName(PhaseType t) {
        if (t == null) {
            throw new IllegalStateException("no current phase");
        }
        switch (t) {
            case UNTAP: return "untap";
            case UPKEEP: return "upkeep";
            case DRAW: return "draw";
            case MAIN1: return "main1";
            case COMBAT_BEGIN: return "begin-combat";
            case COMBAT_DECLARE_ATTACKERS: return "declare-attackers";
            case COMBAT_DECLARE_BLOCKERS: return "declare-blockers";
            case COMBAT_FIRST_STRIKE_DAMAGE: return "combat-damage";
            case COMBAT_DAMAGE: return "combat-damage";
            case COMBAT_END: return "end-combat";
            case MAIN2: return "main2";
            case END_OF_TURN: return "end";
            case CLEANUP: return "cleanup";
            default: throw new IllegalStateException("no gorge step for Forge phase " + t);
        }
    }

    /** The stack, TOP first (Forge's own iteration order: MagicStack pushes
     * with addFirst). Each entry is public: the source object, its
     * controller's seat, whether it is a spell, and the spell's printed
     * cost. */
    private JsonArray stack() {
        JsonArray out = new JsonArray();
        if (pendingCast != null) {
            // The spell whose targets are being chosen, where gorge has it:
            // on top of the stack, not yet paid.
            JsonObject e = new JsonObject();
            e.addProperty("id", pendingCast.getId());
            e.addProperty("controller", seat);
            e.addProperty("is_spell", true);
            e.addProperty("is_creature_spell", pendingCast.isCreature());
            e.addProperty("cmc", pendingCast.getCMC());
            e.addProperty("mana_cost", pendingCast.getManaCost() == null ? "" : pendingCast.getManaCost().toString());
            e.addProperty("name", pendingCast.getName());
            out.add(e);
        }
        for (SpellAbilityStackInstance si : getGame().getStack()) {
            Card src = si.getSourceCard();
            JsonObject e = new JsonObject();
            e.addProperty("id", src == null ? 0 : src.getId());
            e.addProperty("controller", seatOf(si.getActivatingPlayer()));
            boolean spell = si.isSpell();
            e.addProperty("is_spell", spell);
            e.addProperty("is_creature_spell", spell && src != null && src.isCreature());
            if (spell && src != null) {
                e.addProperty("cmc", src.getCMC());
                e.addProperty("mana_cost", src.getManaCost() == null ? "" : src.getManaCost().toString());
            }
            e.addProperty("name", src == null ? "" : src.getName());
            out.add(e);
        }
        return out;
    }

    /** The seat's OWN decklist, as gorge's deck.Manifest shape: rows sorted
     * by name (count, land) and the curve sorted by mana value, every copy at
     * its front face's mana value. A seat knows its own list; this is what
     * botpolicy's deck-shape posture (A4) reads. */
    private static JsonObject ownDeck(Player me) {
        java.util.TreeMap<String, int[]> rows = new java.util.TreeMap<>();
        java.util.TreeMap<Integer, Integer> curve = new java.util.TreeMap<>();
        var reg = me.getRegisteredPlayer();
        JsonObject d = new JsonObject();
        if (reg == null || reg.getDeck() == null) {
            return d;
        }
        for (var e : reg.getDeck().getMain()) {
            forge.item.PaperCard pc = e.getKey();
            int n = e.getValue();
            var rules = pc.getRules();
            boolean land = rules.getType().isLand();
            int[] r = rows.computeIfAbsent(pc.getName(), k -> new int[2]);
            r[0] += n;
            if (land) {
                r[1] = 1;
            }
            int cmc = rules.getManaCost() == null ? 0 : rules.getManaCost().getCMC();
            curve.merge(cmc, n, Integer::sum);
        }
        JsonArray main = new JsonArray();
        for (var e : rows.entrySet()) {
            JsonObject r = new JsonObject();
            r.addProperty("name", e.getKey());
            r.addProperty("count", e.getValue()[0]);
            r.addProperty("land", e.getValue()[1] == 1);
            main.add(r);
        }
        JsonArray cv = new JsonArray();
        for (var e : curve.entrySet()) {
            JsonObject r = new JsonObject();
            r.addProperty("cmc", e.getKey());
            r.addProperty("count", e.getValue());
            cv.add(r);
        }
        d.add("main", main);
        d.add("curve", cv);
        return d;
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
        o.addProperty("toughness", c.isCreature() ? c.getNetToughness() : 0);
        o.addProperty("land", c.isLand());
        o.addProperty("tapped", c.isTapped());
        o.addProperty("colour", gorgeColour(c));
        o.add("types", typeWords(c));
        if ("battlefield".equals(zone)) {
            o.addProperty("sick", c.isSick());
            Card att = c.getAttachedTo();
            if (att != null) {
                o.addProperty("attached_to", att.getId());
            }
        }
        o.addProperty("instant_speed", c.isInstant() || c.hasKeyword(Keyword.FLASH));
        SpellAbility first = c.getFirstSpellAbility();
        ApiType api = first == null ? null : first.getApi();
        o.addProperty("counter", api == ApiType.Counter);
        o.addProperty("draw", api == ApiType.Draw);
        o.addProperty("token", api == ApiType.Token);
        // The AI hints botpolicy's A3/A5 rules read, as runtime values (no
        // script text): the PlayMain1 SVar raw (the Go side canonicalises it
        // with gorge's own reader), any RemoveDeck hint, and the
        // AttachAILogic SVar of an Aura spell aimed at a creature.
        String pm1 = c.hasSVar("PlayMain1") ? c.getSVar("PlayMain1") : "";
        if (!pm1.isEmpty()) {
            o.addProperty("play_main1", pm1);
        }
        boolean remove = forge.ai.ComputerUtilCard.isCardRemAIDeck(c)
                || forge.ai.ComputerUtilCard.isCardRemRandomDeck(c)
                || forge.ai.ComputerUtilCard.isCardRemNonCommanderDeck(c);
        o.addProperty("remove_deck", remove);
        if (api == ApiType.Attach && c.hasSVar("AttachAILogic")) {
            String valid = first.hasParam("ValidTgts") ? first.getParam("ValidTgts") : "";
            if (valid.startsWith("Creature")) {
                o.addProperty("aura_logic", c.getSVar("AttachAILogic"));
            }
        }
        // The raw Produced$/Amount$ of every mana ability; the Go side folds
        // them with gorge's own cards.ProducedCounts, so the production a
        // policy reads is gorge's parse, not a second one.
        JsonArray prod = new JsonArray();
        for (SpellAbility ma : c.getManaAbilities()) {
            if (ma.getManaPart() == null) {
                continue;
            }
            JsonObject m = new JsonObject();
            m.addProperty("produced", ma.getManaPart().getOrigProduced());
            m.addProperty("amount", ma.hasParam("Amount") ? ma.getParam("Amount") : "");
            prod.add(m);
        }
        if (prod.size() > 0) {
            o.add("produces", prod);
        }
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

    /** Another seat's battlefield permanent: the worth facts gorge's view
     * adapter fills for it (name, creature/P/T, CMC, basic, land, colour,
     * type words, mana cost, activations, AI hints). A face-down permanent
     * keeps only its public face-down characteristics. */
    private JsonObject foreignFact(Card c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        o.addProperty("zone", "battlefield");
        o.addProperty("controller", seatOf(c.getController()));
        o.addProperty("creature", c.isCreature());
        o.addProperty("power", c.isCreature() ? c.getNetPower() : 0);
        o.addProperty("toughness", c.isCreature() ? c.getNetToughness() : 0);
        if (c.isFaceDown()) {
            o.addProperty("name", "");
            return o;
        }
        o.addProperty("name", c.getName());
        o.addProperty("cmc", c.getCMC());
        o.addProperty("basic", c.isBasicLand());
        o.addProperty("land", c.isLand());
        o.addProperty("mana_cost", c.getManaCost() == null ? "" : c.getManaCost().toString());
        o.addProperty("colour", gorgeColour(c));
        o.add("types", typeWords(c));
        int n = 0;
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && !sa.isManaAbility()) {
                n += sa.getActivationsThisTurn();
            }
        }
        o.addProperty("activated", n);
        String pm1 = c.hasSVar("PlayMain1") ? c.getSVar("PlayMain1") : "";
        if (!pm1.isEmpty()) {
            o.addProperty("play_main1", pm1);
        }
        return o;
    }

    /** The public-card colour census botpolicy's A2 fold reads: every
     * player's battlefield, graveyard and exile, then the stack's spells. A
     * face-down card carries its controller and a zero colour. */
    private JsonArray publicCards() {
        JsonArray out = new JsonArray();
        for (Player p : getGame().getRegisteredPlayers()) {
            for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile}) {
                for (Card c : p.getCardsIn(z)) {
                    if (c.equals(pendingCast)) {
                        continue;
                    }
                    out.add(publicCard(c, c.getController()));
                }
            }
        }
        if (pendingCast != null) {
            out.add(publicCard(pendingCast, player));
        }
        for (SpellAbilityStackInstance si : getGame().getStack()) {
            Card src = si.getSourceCard();
            if (!si.isSpell() || src == null) {
                continue;
            }
            out.add(publicCard(src, si.getActivatingPlayer()));
        }
        return out;
    }

    private JsonObject publicCard(Card c, Player controller) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        o.addProperty("controller", seatOf(controller));
        if (c.isFaceDown()) {
            o.addProperty("face_down", true);
        } else {
            o.addProperty("colour", gorgeColour(c));
        }
        return o;
    }

    /** gorge's colour identity (engine/cards face.go deriveColourIdentity)
     * applied to the card's current Forge face: the mana cost, the colour
     * indicator, brace mana symbols in the Oracle text outside every
     * parenthesis, and a characteristic-defining SetColor$. Forge's own
     * getColorIdentity() also counts a reminder-only Oracle text (a basic
     * land's), which gorge does not, so it is not used. A face without
     * rules (some tokens) falls back to its current colour. */
    static int gorgeColour(Card c) {
        forge.card.CardRules rules = c.getRules();
        forge.card.ICardFace face = null;
        if (rules != null) {
            var st = c.getCurrentStateName();
            face = (st == forge.card.CardStateName.Original || rules.getOtherPart() == null)
                    ? rules.getMainPart() : rules.getOtherPart();
        }
        if (face == null) {
            return c.getColor().getColor() & 0x1f;
        }
        int m = 0;
        if (face.getManaCost() != null) {
            m |= forge.card.ColorSet.fromManaCost(face.getManaCost()).getColor();
        }
        if (face.getColor() != null) {
            m |= face.getColor().getColor();
        }
        String text = face.getOracleText() == null ? "" : face.getOracleText();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') {
                depth++;
                start = -1;
            } else if (ch == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (ch == '{') {
                if (depth == 0) {
                    start = i + 1;
                }
            } else if (ch == '}') {
                if (depth == 0 && start >= 0) {
                    m |= symbolColours(text.substring(start, i));
                    start = -1;
                }
            }
        }
        for (String st : face.getStaticAbilities()) {
            if (st.contains("CharacteristicDefining$ True") && st.contains("SetColor$ All")) {
                m |= 0x1f;
            }
        }
        return m & 0x1f;
    }

    /** The colours one brace token names (W/U/B/R/G letters: hybrid and
     * Phyrexian tokens name every letter they carry). */
    private static int symbolColours(String tok) {
        int m = 0;
        for (int i = 0; i < tok.length(); i++) {
            switch (Character.toUpperCase(tok.charAt(i))) {
                case 'W': m |= 1; break;
                case 'U': m |= 2; break;
                case 'B': m |= 4; break;
                case 'R': m |= 8; break;
                case 'G': m |= 16; break;
                default: break;
            }
        }
        return m;
    }

    /** The card's type-line words (supertypes, card types, subtypes), which
     * the Go side hashes with gorge's botpolicy.TypeSigOf. */
    static JsonArray typeWords(Card c) {
        JsonArray out = new JsonArray();
        var t = c.getType();
        for (var s : t.getSupertypes()) {
            out.add(s.name());
        }
        for (var s : t.getCoreTypes()) {
            out.add(s.name());
        }
        for (String s : t.getSubtypes()) {
            out.add(s);
        }
        return out;
    }

    private JsonObject ask(String kind, Player me, JsonArray options) {
        JsonObject req = new JsonObject();
        req.addProperty("v", WIRE_VERSION);
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
        return br("chooseSpellAbilityToPlay", this::pickToPlay);
    }

    private List<SpellAbility> pickToPlay() {
        // The FULL legal action set, not the parent's pick: the parent's
        // chooseSpellAbilityToPlay returns at most one ability (its answer),
        // so asking the bridge about it would never pose a choice.
        List<SpellAbility> all = offer();
        if (System.getenv("BRIDGE_TRACE") != null) {
            var ph = getGame().getPhaseHandler();
            System.err.println("BRIDGE prio turn=" + ph.getTurn() + " active=" + seatOf(ph.getPlayerTurn())
                    + " phase=" + ph.getPhase() + " stack=" + getGame().getStack().size() + " offer=" + all.size());
        }
        if (all.isEmpty()) {
            // Forge's "pass" is NULL (PhaseHandler: "chosenSa == null ...
            // that means 'I pass'"). An empty list is "I played nothing, ask
            // me again": the priority loop re-asks up to 999 times, stashing
            // the game state each round, before it gives up with "AI looped
            // too much". That loop was the ~30 s per game.
            unavailable.clear();
            return null;
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
        // Forge's controller declines by returning null; gorge's
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
            unavailable.clear();
            return null; // Forge's pass (see above)
        }
        SpellAbility chosen = all.get(pick.get(0));
        if (chosen.costHasManaX() && chosen.getXManaCostPaid() == null) {
            // Forge AI picks X inside its own canPlaySa, which the bridge's
            // pick never runs, so an {X} spell was paid with X = 0 (a
            // Walking Ballista entered as a 0/0 and died). No X ask exists on
            // the wire yet: take the largest X Forge can pay, counted as a
            // fallback.
            fallback("xValue");
            coverage("xValue")[1]++;
            chosen.setXManaCostPaid(forge.ai.ComputerUtilMana.determineLeftoverMana(chosen, player, false));
        }
        if (needsTargets(chosen)) {
            // Forge AI chooses targets inside its own canPlaySa, which the
            // bridge's pick never runs: without this a chosen targeted
            // spell or ability went to the stack with no targets at all.
            // Targets stay Forge's (the counted chooseTargetsFor fallback)
            // until a target ask exists on the wire.
            chosen.resetTargets();
            handOff(() -> chooseTargetsFor(chosen));
            if (!targetsValid(chosen)) {
                // The offer filter admitted it (every mandatory target had a
                // candidate) but Forge's targeting found no legal set. Do not
                // turn that into a pass: drop the option and ask again.
                fallback("targetsUnavailable");
                chosen.resetTargets();
                unavailable.add(chosen);
                return pickToPlay();
            }
        }
        unavailable.clear();
        return List.of(chosen);
    }

    /** Options dropped for this priority window because Forge found no legal
     * target set for them (see chooseSpellAbilityToPlay). */
    private final List<SpellAbility> unavailable = new ArrayList<>();

    private static boolean needsTargets(SpellAbility sa) {
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.usesTargeting()) {
                return true;
            }
        }
        return false;
    }

    private static boolean targetsValid(SpellAbility sa) {
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.usesTargeting() && !cur.isTargetNumberValid()) {
                return false;
            }
        }
        return true;
    }

    /** Every mandatory target slot in the chain has at least one candidate. */
    private static boolean targetsPossible(SpellAbility sa) {
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting()) {
                continue;
            }
            var tr = cur.getTargetRestrictions();
            Card host = cur.getHostCard();
            if (tr.getMinTargets(host, cur) > 0 && tr.getNumCandidates(cur) == 0) {
                return false;
            }
        }
        return true;
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
        out.removeIf(sa -> !playable(sa) || unavailable.contains(sa));
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
        return affordable(sa) && targetsPossible(sa);
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
        br("declareAttackers", () -> {
            attack(attacker, combat);
            return null;
        });
    }

    private void attack(Player attacker, Combat combat) {
        // getLegalAttackers() is not the set of legal attackers: it returns the
        // single best COMPLETE attack map (fewest restriction violations), and
        // the empty map is often cheapest. Offering it asked the bridge
        // "attack with nobody?" every combat, which is no question at all.
        // Build the offer from the creatures that can attack and let the
        // bridge choose the subset.
        // The controllers this defender must answer for: the player, plus the
        // planeswalkers and battles they protect (each is its own defender in
        // Forge, and each gets its own option), in a stable order.
        // `attacker` is the ATTACKING player (this seat), not a defender:
        // building the defender list from it declared every attack at the
        // bridge's own seat, so each gorge attacker hit gorge. The defenders
        // are Combat's attackable entities (opponents first, then their
        // planeswalkers and battles), in Forge's order.
        List<GameEntity> targets = new ArrayList<>();
        for (GameEntity e : combat.getDefenders()) {
            if (e instanceof Player) {
                targets.add(e);
            }
        }
        for (GameEntity e : combat.getDefenders()) {
            if (!(e instanceof Player)) {
                targets.add(e);
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
                if (target instanceof Player) {
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
        req.addProperty("v", WIRE_VERSION);
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
        br("declareBlockers", () -> {
            block(defender, combat);
            return null;
        });
    }

    private void block(Player defender, Combat combat) {
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
        // Reached inside a fallback (the AI parent's own pass) or while the
        // bridge builds an offer: that is not a decision gorge would be
        // asked, so the parent answers, counted nested/probe as before.
        if (fbDepth > 0 || brDepth > 0) {
            return fb("chooseTargetsFor", () -> super.chooseTargetsFor(sa));
        }
        String why = unbridgeableTargets(sa);
        if (why != null) {
            fallback("chooseTargetsFor:" + why);
            return fb("chooseTargetsFor", () -> super.chooseTargetsFor(sa));
        }
        return br("chooseTargetsFor", () -> askTargets(sa));
    }

    /** Why the chain from sa has a target slot the wire cannot express, or
     * null when every slot can be asked. The named reason is the counted
     * fallback key. */
    private String unbridgeableTargets(SpellAbility sa) {
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.getApi() == ApiType.Charm) {
                return "charm"; // the modes are Forge's, chosen with the targets
            }
            if (!cur.usesTargeting()) {
                continue;
            }
            if (cur.isDividedAsYouChoose()) {
                return "divided";
            }
            if (cur.hasParam("TargetingPlayer")) {
                return "targetingPlayer";
            }
            for (String k : new String[] {"TargetsAtRandom", "RandomNumTargets"}) {
                if (cur.hasParam(k)) {
                    return "random";
                }
            }
            for (String k : new String[] {"TargetsWithDifferentControllers", "TargetsForEachPlayer",
                    "TargetsWithDifferentCMC", "TargetsWithDifferentNames", "TargetsWithEqualToughness",
                    "TargetsWithoutSameCreatureType", "TargetsWithSameCreatureType", "TargetsWithSameCardType",
                    "MaxTotalTargetCMC", "MaxTotalTargetPower"}) {
                if (cur.hasParam(k)) {
                    return "setConstraint";
                }
            }
        }
        return null;
    }

    /** True when some SpellAbility in the chain from sa targets. */
    private static boolean chainTargets(SpellAbility sa) {
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.usesTargeting()) {
                return true;
            }
        }
        return false;
    }

    /** One KTarget ask per target slot of the chain from sa, in chain order,
     * each answer applied before the next slot's candidates are listed (so
     * TargetUnique and the parent-target references see it). False when a
     * mandatory slot has fewer candidates than its minimum, which is what
     * the AI parent returns there; an answer Forge does not accept is a
     * hard failure, never a silent default. */
    private boolean askTargets(SpellAbility sa) {
        Card host = sa.getHostCard();
        SpellAbility root = sa.getRootAbility();
        boolean casting = root.isSpell() && host != null && !host.isInZone(ZoneType.Stack);
        Card saved = pendingCast;
        pendingCast = casting ? host : null;
        try {
            for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
                if (!cur.usesTargeting()) {
                    continue;
                }
                cur.resetTargets();
                if (!askSlot(cur, root, host)) {
                    return false;
                }
            }
        } finally {
            pendingCast = saved;
        }
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (cur.usesTargeting() && !cur.isTargetNumberValid()) {
                throw new IllegalStateException("bridge targets for " + label(sa) + " are not a valid target count");
            }
        }
        return true;
    }

    private record Candidate(String kind, GameObject target, int obj, int seat, String label) {}

    private boolean askSlot(SpellAbility cur, SpellAbility root, Card host) {
        var tr = cur.getTargetRestrictions();
        List<Candidate> cands = new ArrayList<>();
        if (tr.getZone().contains(ZoneType.Stack)) {
            for (SpellAbilityStackInstance si : getGame().getStack()) {
                SpellAbility on = si.getSpellAbility();
                if (!cur.canTargetSpellAbility(on)) {
                    continue;
                }
                Card src = si.getSourceCard();
                String kind = si.isSpell() ? "spell" : on.isTrigger() ? "trigger" : "ability";
                cands.add(new Candidate(kind, on, src == null ? 0 : src.getId(), seatOf(si.getActivatingPlayer()),
                        src == null ? "?" : src.getName()));
            }
        }
        for (GameEntity e : tr.getAllCandidates(cur, false)) {
            if (e instanceof Player) {
                Player p = (Player) e;
                cands.add(new Candidate("player", p, 0, seatOf(p), p.getName()));
            } else if (e instanceof Card) {
                Card c = (Card) e;
                if (c.isInZone(ZoneType.Stack)) {
                    continue; // a stack target is its spell ability, listed above
                }
                boolean bf = c.isInZone(ZoneType.Battlefield);
                Player who = bf ? c.getController() : c.getOwner();
                cands.add(new Candidate(bf ? "permanent" : "card", c, c.getId(), seatOf(who), c.getName()));
            } else {
                throw new IllegalStateException("target candidate the bridge cannot name: " + e);
            }
        }
        int min = tr.getMinTargets(host, cur);
        int max = Math.min(tr.getMaxTargets(host, cur), cands.size());
        if (cands.size() < min) {
            return false;
        }
        if (max <= 0) {
            return true;
        }
        JsonArray opts = new JsonArray();
        for (int i = 0; i < cands.size(); i++) {
            Candidate c = cands.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("index", i);
            o.addProperty("kind", c.kind());
            o.addProperty("label", c.label());
            if (c.obj() != 0) {
                o.addProperty("obj", c.obj());
            }
            o.addProperty("player", c.seat());
            opts.add(o);
        }
        JsonObject req = new JsonObject();
        req.addProperty("v", WIRE_VERSION);
        req.addProperty("kind", "target");
        req.addProperty("seat", seat);
        req.add("board", board(player));
        req.add("options", opts);
        req.addProperty("min", min);
        req.addProperty("max", max);
        req.addProperty("prompt", "Choose a target for " + (host == null ? "?" : host.getName()));
        // gorge's Source (cast_targets.go): the card for a spell, a trigger
        // and an Attach ability; 0 for another activated ability.
        boolean withSource = root.isSpell() || root.isTrigger() || cur.getApi() == ApiType.Attach;
        if (withSource && host != null) {
            req.addProperty("source", host.getId());
        }
        JsonObject te = targetEffect(cur, host);
        if (te != null) {
            req.add("target_effect", te);
        }
        if (tr.isSameController()) {
            req.addProperty("same_controller", true);
        }
        List<Integer> pick = choices(ask(req));
        if (pick.size() < min || pick.size() > max || pick.stream().distinct().count() != pick.size()) {
            throw new IllegalStateException("bridge target answer " + pick + " is not " + min + ".." + max
                    + " distinct options of " + cands.size());
        }
        for (int i : pick) {
            if (i < 0 || i >= cands.size()) {
                throw new IllegalStateException("bridge target answer " + pick + " names no offered option");
            }
            GameObject t = cands.get(i).target();
            boolean ok = t instanceof SpellAbility ? cur.canTargetSpellAbility((SpellAbility) t) : cur.canTarget(t);
            if (!ok) {
                throw new IllegalStateException("bridge target " + cands.get(i).label() + " is not legal for "
                        + (host == null ? "?" : host.getName()));
            }
            cur.getTargets().add(t);
        }
        return true;
    }

    /** decision.TargetEffect for the slot's own ability, from its ApiType,
     * params and the host's SVars (see the v3 field map). */
    private static JsonObject targetEffect(SpellAbility cur, Card host) {
        ApiType api = cur.getApi();
        if (api == null) {
            return null;
        }
        JsonObject te = new JsonObject();
        te.addProperty("api", api.name());
        switch (api) {
            case DealDamage:
            case DamageAll: {
                JsonObject dmg = new JsonObject();
                Integer amt = damageAmount(cur, host);
                if (amt == null) {
                    dmg.add("amount", com.google.gson.JsonNull.INSTANCE);
                } else {
                    dmg.addProperty("amount", amt);
                }
                te.add("damage", dmg);
                break;
            }
            case Destroy:
            case DestroyAll:
                te.add("removal", removal("destroy", null));
                break;
            case Sacrifice:
            case SacrificeAll:
                te.add("removal", removal("sacrifice", null));
                break;
            case ChangeZone:
            case ChangeZoneAll: {
                String dest = cur.hasParam("Destination") ? cur.getParam("Destination") : "";
                String kind = switch (dest) {
                    case "Exile" -> "exile";
                    case "Hand" -> "bounce";
                    case "Graveyard" -> "graveyard";
                    case "Library" -> "library";
                    case "Command" -> "command";
                    default -> null;
                };
                if (kind != null) {
                    te.add("removal", removal(kind, dest.toLowerCase()));
                }
                break;
            }
            case Effect: {
                JsonArray statics = new JsonArray();
                if (cur.hasParam("StaticAbilities") && host != null) {
                    for (String name : cur.getParam("StaticAbilities").split(",")) {
                        String body = host.hasSVar(name.trim()) ? host.getSVar(name.trim()) : "";
                        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("Mode\\$\\s*([A-Za-z]+)").matcher(body);
                        if (mm.find()) {
                            statics.add(mm.group(1));
                        }
                    }
                }
                if (statics.size() > 0) {
                    te.add("statics", statics);
                }
                break;
            }
            case Attach:
                if (host != null && host.hasSVar("AttachAILogic")) {
                    te.addProperty("attach_logic", host.getSVar("AttachAILogic"));
                }
                break;
            default:
                break;
        }
        return te;
    }

    private static JsonObject removal(String kind, String dest) {
        JsonObject r = new JsonObject();
        r.addProperty("kind", kind);
        if (dest != null) {
            r.addProperty("destination", dest);
        }
        return r;
    }

    /** The nominal damage at target time: a literal NumDmg$, else Forge's
     * own evaluation of the expression when it does not read the pending
     * target (gorge refuses those too); otherwise unknown. */
    private static Integer damageAmount(SpellAbility cur, Card host) {
        if (!cur.hasParam("NumDmg")) {
            return null;
        }
        String nd = cur.getParam("NumDmg").trim();
        if (nd.matches("\\d+")) {
            return Integer.parseInt(nd);
        }
        String body = host != null && host.hasSVar(nd) ? host.getSVar(nd) : nd;
        if (body.contains("Targeted") || body.contains("Remembered") || body.contains("Imprinted")) {
            return null;
        }
        try {
            int n = forge.game.ability.AbilityUtils.calculateAmount(host, nd, cur);
            return n < 0 ? null : n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- decision coverage ------------------------------------------------
    //
    // Every PlayerController decision method a game can reach is overridden
    // below. A bridged method bumps "bridged" when it asked the wire and
    // "trivial" when there was nothing to ask; every other one bumps
    // "fallback" and delegates to the AI parent unchanged, so the counters
    // never change a game. A decision method the AI parent reaches from
    // INSIDE another fallback (an overload delegating to its sibling, a
    // mana-ability colour choice during the AI's payment) is part of that
    // outer decision: it is counted as "nested", not as a second fallback.
    // One reached while a bridged method builds its own offer is a "probe".
    //
    // Not overridden (not choices): the reveal overloads, notifyOfValue,
    // revealAnte, revealAISkipCards and revealUnsupported (notifications);
    // autoPassCancel, awaitNextInput, cancelAwaitNextInput and
    // resetAtEndOfTurn (GUI/turn hooks); isAI, isGuiPlayer, getFullControl,
    // isFullControl, canPlayUnlimitedLands, isOrderedZone, getAnteResult and
    // complainCardsCantPlayWell (queries); tempShowCards/endTempShowCards
    // (display); cheatShuffle (the AI profile's mana-weighting shuffle hook,
    // a no-op here: GameRules.allowCheatShuffle defaults false and the driver
    // never sets it). getAbilityToPlay(Card, List), reveal(DelayedReveal),
    // chooseSomeType/3, chooseSector/2, chooseSprocket/1 and chooseBinary/3
    // are final in the base and reach the counted overloads.

    /** method -> {bridged, fallback, nested, trivial, probe}. */
    private final Map<String, int[]> coverage = new java.util.TreeMap<>();
    private int fbDepth;
    /** Inside a bridged method's own body (building its offer). */
    private int brDepth;
    private int askCount;

    int[] coverage(String method) {
        return coverage.computeIfAbsent(method, k -> new int[5]);
    }

    /** A bridged method: "bridged" when it put at least one ask on the wire,
     * "trivial" when it returned without one (no legal action but a pass, no
     * creature able to attack or block): Forge posed no choice there. */
    private <R> R br(String method, java.util.function.Supplier<R> body) {
        int before = askCount;
        brDepth++;
        try {
            return body.get();
        } finally {
            brDepth--;
            coverage(method)[askCount > before ? 0 : 3]++;
        }
    }

    /** A decision the bridged pick hands to the AI parent on purpose (its
     * targets): a real fallback, not one of the offer's probes. */
    private <R> R handOff(java.util.function.Supplier<R> parent) {
        int saved = brDepth;
        brDepth = 0;
        try {
            return parent.get();
        } finally {
            brDepth = saved;
        }
    }

    /** A parent step that carries out a bridged decision (playing the
     * trigger or no-stack ability whose targets the bridge chose, which pays
     * its costs): not a decision of its own, so anything it reaches is
     * counted nested, never as a separate fallback or probe. */
    private <R> R inner(java.util.function.Supplier<R> parent) {
        int saved = brDepth;
        brDepth = 0;
        fbDepth++;
        try {
            return parent.get();
        } finally {
            fbDepth--;
            brDepth = saved;
        }
    }

    private <R> R fb(String method, java.util.function.Supplier<R> parent) {
        // probe: reached while the bridge builds its own offer (the D1
        // affordability check runs Forge's cost adjustment in test mode,
        // which asks chooseCardsToDelve); Forge posed nothing there.
        coverage(method)[brDepth > 0 && fbDepth == 0 ? 4 : fbDepth == 0 ? 1 : 2]++;
        fbDepth++;
        try {
            return parent.get();
        } finally {
            fbDepth--;
        }
    }

    private void fbv(String method, Runnable parent) {
        fb(method, () -> {
            parent.run();
            return null;
        });
    }

    /** {method: {bridged, fallback[, nested][, trivial][, probe]}}, sorted by method name. */
    public JsonObject decisionCoverage() {
        JsonObject o = new JsonObject();
        for (var e : coverage.entrySet()) {
            JsonObject m = new JsonObject();
            m.addProperty("bridged", e.getValue()[0]);
            m.addProperty("fallback", e.getValue()[1]);
            if (e.getValue()[2] != 0) {
                m.addProperty("nested", e.getValue()[2]);
            }
            if (e.getValue()[3] != 0) {
                m.addProperty("trivial", e.getValue()[3]);
            }
            if (e.getValue()[4] != 0) {
                m.addProperty("probe", e.getValue()[4]);
            }
            o.add(e.getKey(), m);
        }
        return o;
    }

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, forge.util.ITriggerEvent triggerEvent) {
        return fb("getAbilityToPlay", () -> super.getAbilityToPlay(hostCard, abilities, triggerEvent));
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        // The parent sets targets with brains.doTrigger, not chooseTargetsFor;
        // a targeted no-stack ability gets the bridge's slots instead, then
        // the parent's own playNoStack.
        if (fbDepth > 0 || brDepth > 0 || !canSetupTargets || !chainTargets(effectSA)
                || unbridgeableTargets(effectSA) != null) {
            if (canSetupTargets && fbDepth == 0 && brDepth == 0 && chainTargets(effectSA)) {
                fallback("playSpellAbilityNoStack:" + unbridgeableTargets(effectSA));
            }
            fbv("playSpellAbilityNoStack", () -> super.playSpellAbilityNoStack(effectSA, canSetupTargets));
            return;
        }
        br("playSpellAbilityNoStack", () -> {
            askTargets(effectSA);
            inner(() -> forge.ai.ComputerUtil.playNoStack(player, effectSA, getGame(), true));
            return null;
        });
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        return fb("orderSimultaneousSa", () -> super.orderSimultaneousSa(activePlayerSAs));
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        // The parent prepares each trigger with brains.doTrigger, which sets
        // its targets without chooseTargetsFor. A targeted trigger the wire
        // can express gets the bridge's slots and the parent's own playStack;
        // every other SpellAbility (untargeted, Charm, copies) goes through
        // the parent one at a time, in the parent's (Forge's) order.
        boolean any = false;
        for (SpellAbility sa : activePlayerSAs) {
            any |= sa.isTrigger() && !sa.isCopied() && chainTargets(sa) && unbridgeableTargets(sa) == null;
        }
        if (fbDepth > 0 || brDepth > 0 || !any) {
            for (SpellAbility sa : activePlayerSAs) {
                if (fbDepth == 0 && brDepth == 0 && sa.isTrigger() && !sa.isCopied() && chainTargets(sa)) {
                    fallback("orderAndPlaySimultaneousSa:" + unbridgeableTargets(sa));
                }
            }
            fbv("orderAndPlaySimultaneousSa", () -> super.orderAndPlaySimultaneousSa(activePlayerSAs));
            return;
        }
        br("orderAndPlaySimultaneousSa", () -> {
            // The order is Forge's (no trigger_order ask on the wire): a real
            // fallback when there is more than one to order.
            List<SpellAbility> ordered = activePlayerSAs.size() > 1
                    ? handOff(() -> orderSimultaneousSa(activePlayerSAs)) : activePlayerSAs;
            for (SpellAbility sa : ordered) {
                if (sa.isTrigger() && !sa.isCopied() && chainTargets(sa) && unbridgeableTargets(sa) == null) {
                    if (askTargets(sa)) {
                        inner(() -> forge.ai.ComputerUtil.playStack(sa, player, getGame()));
                    }
                } else {
                    if (sa.isTrigger() && !sa.isCopied() && chainTargets(sa)) {
                        fallback("orderAndPlaySimultaneousSa:" + unbridgeableTargets(sa));
                    }
                    handOff(() -> {
                        fbv("orderAndPlaySimultaneousSa", () -> super.orderAndPlaySimultaneousSa(List.of(sa)));
                        return null;
                    });
                }
            }
            return null;
        });
    }

    @Override
    public boolean playTrigger(Card host, forge.game.trigger.WrappedAbility wrapperAbility, boolean isMandatory) {
        return fb("playTrigger", () -> super.playTrigger(host, wrapperAbility, isMandatory));
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        return fb("playSaFromPlayEffect", () -> super.playSaFromPlayEffect(tgtSA));
    }

    @Override
    public List<forge.item.PaperCard> sideboard(forge.deck.Deck deck, forge.game.GameType gameType, String message) {
        return fb("sideboard", () -> super.sideboard(deck, gameType, message));
    }

    @Override
    public List<forge.item.PaperCard> chooseCardsYouWonToAddToDeck(List<forge.item.PaperCard> losses) {
        return fb("chooseCardsYouWonToAddToDeck", () -> super.chooseCardsYouWonToAddToDeck(losses));
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
            int damageDealt, GameEntity defender, boolean overrideOrder) {
        return fb("assignCombatDamage",
                () -> super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder));
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return fb("divideShield", () -> super.divideShield(effectSource, affected, shieldAmount));
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, forge.card.ColorSet colorSet, int manaAmount, boolean different) {
        return fb("specifyManaCombo", () -> super.specifyManaCombo(sa, colorSet, manaAmount, different));
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets,
            String message) {
        return fb("choosePermanentsToSacrifice", () -> super.choosePermanentsToSacrifice(sa, min, max, validTargets, message));
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets,
            String message) {
        return fb("choosePermanentsToDestroy", () -> super.choosePermanentsToDestroy(sa, min, max, validTargets, message));
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        return fb("announceRequirements", () -> super.announceRequirements(ability, min, max, announce));
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, java.util.function.Predicate<GameObject> filter,
            boolean optional) {
        return fb("chooseNewTargetsFor", () -> super.chooseNewTargetsFor(ability, filter, optional));
    }

    @Override
    public org.apache.commons.lang3.tuple.Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility saSrc,
            List<org.apache.commons.lang3.tuple.Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return fb("chooseTarget", () -> super.chooseTarget(saSrc, allTargets));
    }

    @Override
    public boolean helpPayForAssistSpell(forge.game.mana.ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return fb("helpPayForAssistSpell", () -> super.helpPayForAssistSpell(cost, sa, max, requested));
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return fb("choosePlayerToAssistPayment", () -> super.choosePlayerToAssistPayment(optionList, sa, title, max));
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min,
            int max, boolean isOptional, Map<String, Object> params) {
        return fb("chooseCardsForEffect",
                () -> super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params));
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title,
            boolean isOptional) {
        return fb("chooseCardsForEffectMultiple", () -> super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional));
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal,
            SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        return fb("chooseSingleEntityForEffect", () -> super.chooseSingleEntityForEffect(optionList, delayedReveal, sa,
                title, isOptional, targetedPlayer, params));
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max,
            DelayedReveal delayedReveal, SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        return fb("chooseEntitiesForEffect", () -> super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa,
                title, targetedPlayer, params));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num,
            Map<String, Object> params) {
        return fb("chooseSpellAbilitiesForEffect", () -> super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params));
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title,
            Map<String, Object> params) {
        return fb("chooseSingleSpellForEffect", () -> super.chooseSingleSpellForEffect(spells, sa, title, params));
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
            Card cardToShow, Map<String, Object> params) {
        return fb("confirmAction", () -> super.confirmAction(sa, mode, message, options, cardToShow, params));
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String string, int bid, Player winner) {
        return fb("confirmBidAction", () -> super.confirmBidAction(sa, mode, string, bid, winner));
    }

    @Override
    public boolean confirmReplacementEffect(forge.game.replacement.ReplacementEffect replacementEffect, SpellAbility effectSA,
            GameEntity affected, String question) {
        return fb("confirmReplacementEffect",
                () -> super.confirmReplacementEffect(replacementEffect, effectSA, affected, question));
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return fb("confirmStaticApplication", () -> super.confirmStaticApplication(hostCard, mode, message, logic));
    }

    @Override
    public boolean confirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
        return fb("confirmTrigger", () -> super.confirmTrigger(wrapper));
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return fb("exertAttackers", () -> super.exertAttackers(attackers));
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return fb("enlistAttackers", () -> super.enlistAttackers(attackers));
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return fb("orderBlockers", () -> super.orderBlockers(attacker, blockers));
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return fb("orderBlocker", () -> super.orderBlocker(attacker, blocker, oldBlockers));
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return fb("orderAttackers", () -> super.orderAttackers(blocker, attackers));
    }

    @Override
    public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return fb("arrangeForScry", () -> super.arrangeForScry(topN));
    }

    @Override
    public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return fb("arrangeForSurveil", () -> super.arrangeForSurveil(topN));
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return fb("willPutCardOnTop", () -> super.willPutCardOnTop(c));
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return fb("orderMoveToZoneList", () -> super.orderMoveToZoneList(cards, destinationZone, source));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max) {
        return fb("chooseCardsToDiscardFrom", () -> super.chooseCardsToDiscardFrom(p, sa, validCards, min, max));
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max,
            CardCollectionView visibleToChooser) {
        return fb("chooseCardsToDiscardFrom", () -> super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand, String[] uTypes, SpellAbility sa) {
        return fb("chooseCardsToDiscardUnlessType", () -> super.chooseCardsToDiscardUnlessType(num, hand, uTypes, sa));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return fb("chooseCardsToDiscardToMaximumHandSize", () -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard));
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return fb("chooseCardsToDelve", () -> super.chooseCardsToDelve(genericAmount, grave));
    }

    @Override
    public Map<Card, forge.card.mana.ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa,
            forge.card.mana.ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures,
            Integer maxReduction) {
        return fb("chooseCardsForConvokeOrImprovise", () -> super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards,
                artifacts, creatures, maxReduction));
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return fb("chooseCardsForSplice", () -> super.chooseCardsForSplice(sa, cards));
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return fb("chooseCardsToRevealFromHand", () -> super.chooseCardsToRevealFromHand(min, max, valid));
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return fb("chooseSaToActivateFromOpeningHand", () -> super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand));
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return fb("chooseStartingPlayer", () -> super.chooseStartingPlayer(isFirstGame));
    }

    @Override
    public forge.game.zone.PlayerZone chooseStartingHand(List<forge.game.zone.PlayerZone> zones) {
        return fb("chooseStartingHand", () -> super.chooseStartingHand(zones));
    }

    @Override
    public forge.game.mana.Mana chooseManaFromPool(List<forge.game.mana.Mana> manaChoices) {
        return fb("chooseManaFromPool", () -> super.chooseManaFromPool(manaChoices));
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, java.util.Collection<String> validTypes, boolean isOptional) {
        return fb("chooseSomeType", () -> super.chooseSomeType(kindOfType, sa, validTypes, isOptional));
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return fb("chooseSector", () -> super.chooseSector(assignee, ai, sectors));
    }

    @Override
    public forge.game.card.sticker.Sticker chooseSticker(List<forge.game.card.sticker.Sticker> options, Card target,
            SpellAbility sa, boolean isOptional) {
        return fb("chooseSticker", () -> super.chooseSticker(options, target, sa, isOptional));
    }

    @Override
    public int chooseStickerNamePosition(forge.game.card.sticker.Sticker sticker, Card target) {
        return fb("chooseStickerNamePosition", () -> super.chooseStickerNamePosition(sticker, target));
    }

    @Override
    public Card chooseCardToKeepStickers(CardCollectionView options) {
        return fb("chooseCardToKeepStickers", () -> super.chooseCardToKeepStickers(options));
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return fb("chooseContraptionsToCrank", () -> super.chooseContraptionsToCrank(contraptions));
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        return fb("chooseSprocket", () -> super.chooseSprocket(assignee, sprockets));
    }

    @Override
    public forge.game.PlanarDice choosePDRollToIgnore(List<forge.game.PlanarDice> rolls) {
        return fb("choosePDRollToIgnore", () -> super.choosePDRollToIgnore(rolls));
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return fb("chooseRollToIgnore", () -> super.chooseRollToIgnore(rolls));
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return fb("chooseDiceToReroll", () -> super.chooseDiceToReroll(rolls));
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return fb("chooseRollToModify", () -> super.chooseRollToModify(rolls));
    }

    @Override
    public forge.game.ability.effects.RollDiceEffect.DieRollResult chooseRollToSwap(
            List<forge.game.ability.effects.RollDiceEffect.DieRollResult> rolls) {
        return fb("chooseRollToSwap", () -> super.chooseRollToSwap(rolls));
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return fb("chooseRollSwapValue", () -> super.chooseRollSwapValue(swapChoices, currentResult, power, toughness));
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
            com.google.common.collect.ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return fb("vote", () -> super.vote(sa, prompt, options, votes, forPlayer, optional));
    }

    // ---- mulligan ---------------------------------------------------------
    //
    // gorge decides keep/mulligan on the full seven knowing how many it will
    // bottom, then bottoms (engine/rules/mulligan.go). This fork's London
    // mulligan draws seven and calls tuckCardsViaMulligan at once, then asks
    // mulliganKeepHand on the reduced hand. The bridge keeps gorge's order:
    // at the tuck it asks the keep question over the seven, then (on keep)
    // the bottoming question, and the following mulliganKeepHand returns that
    // verdict without asking again. On a mulligan verdict the tuck returns
    // the first n cards: the mulligan shuffles the whole hand back, so which
    // n is immaterial. The opening seven (nothing to bottom) is asked in
    // mulliganKeepHand directly.

    /** The keep verdict the last tuck asked for, consumed by the next
     * mulliganKeepHand. */
    private Boolean tuckVerdict;

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (fbDepth > 0 || brDepth > 0) {
            return fb("mulliganKeepHand", () -> super.mulliganKeepHand(firstPlayer, cardsToReturn));
        }
        if (tuckVerdict != null) {
            boolean keep = tuckVerdict;
            tuckVerdict = null;
            coverage("mulliganKeepHand")[0]++; // the bridge's verdict, asked at the tuck
            return keep;
        }
        return br("mulliganKeepHand", () -> askKeep(cardsToReturn));
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (fbDepth > 0 || brDepth > 0 || cardsToReturn <= 0) {
            return fb("tuckCardsViaMulligan", () -> super.tuckCardsViaMulligan(hand, cardsToReturn));
        }
        if (cardsToReturn > hand.size()) {
            throw new IllegalStateException("tuck " + cardsToReturn + " of a " + hand.size() + "-card hand");
        }
        return br("tuckCardsViaMulligan", () -> {
            boolean keep = askKeep(cardsToReturn);
            tuckVerdict = keep;
            List<Card> cards = new ArrayList<>();
            for (Card c : hand) {
                cards.add(c);
            }
            if (!keep) {
                return CardCollection.getView(new CardCollection(cards.subList(0, cardsToReturn)));
            }
            return askBottom(cards, cardsToReturn);
        });
    }

    /** gorge's keep/mulligan ask: "keep" then "mulligan", with the number of
     * cards a keep bottoms. Forge only asks while a mulligan is allowed, so
     * both are offered. */
    private boolean askKeep(int toBottom) {
        JsonArray opts = new JsonArray();
        JsonObject k = new JsonObject();
        k.addProperty("index", 0);
        k.addProperty("kind", "keep");
        k.addProperty("label", "keep");
        opts.add(k);
        JsonObject m = new JsonObject();
        m.addProperty("index", 1);
        m.addProperty("kind", "mulligan");
        m.addProperty("label", "mulligan");
        opts.add(m);
        JsonObject req = mulliganReq(opts);
        req.addProperty("mulligans", toBottom);
        List<Integer> pick = choices(ask(req));
        if (pick.size() != 1 || (pick.get(0) != 0 && pick.get(0) != 1)) {
            throw new IllegalStateException("bridge keep answer " + pick + " is not keep or mulligan");
        }
        return pick.get(0) == 0;
    }

    /** gorge's bottoming ask: one "bottom" option per hand card, choose
     * exactly n. */
    private CardCollectionView askBottom(List<Card> hand, int n) {
        JsonArray opts = new JsonArray();
        for (int i = 0; i < hand.size(); i++) {
            JsonObject o = new JsonObject();
            o.addProperty("index", i);
            o.addProperty("kind", "bottom");
            o.addProperty("label", hand.get(i).getName());
            o.addProperty("obj", hand.get(i).getId());
            o.addProperty("player", seat);
            opts.add(o);
        }
        JsonObject req = mulliganReq(opts);
        req.addProperty("mulligans", n);
        req.addProperty("min", n);
        req.addProperty("max", n);
        List<Integer> pick = choices(ask(req));
        if (pick.size() != n || pick.stream().distinct().count() != n) {
            throw new IllegalStateException("bridge bottoming answer " + pick + " is not " + n + " distinct cards");
        }
        CardCollection out = new CardCollection();
        for (int i : pick) {
            if (i < 0 || i >= hand.size()) {
                throw new IllegalStateException("bridge bottoming answer " + pick + " names no hand card");
            }
            out.add(hand.get(i));
        }
        return out;
    }

    private JsonObject mulliganReq(JsonArray opts) {
        JsonObject req = new JsonObject();
        req.addProperty("v", WIRE_VERSION);
        req.addProperty("kind", "mulligan");
        req.addProperty("seat", seat);
        pregame = true;
        try {
            req.add("board", board(player));
        } finally {
            pregame = false;
        }
        req.add("options", opts);
        return req;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        // Not a separate question: it carries out a pick (gorge's, over the
        // bridge) and is where the AI parent pays its costs -- mana sources,
        // and any sacrifice/discard/tap cost part via its own AiCostDecision,
        // which never comes back through the controller.
        return fb("playChosenSpellAbility", () -> super.playChosenSpellAbility(sa));
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num,
            boolean allowRepeat) {
        return fb("chooseModeForAbility", () -> super.chooseModeForAbility(sa, possible, min, num, allowRepeat));
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return fb("chooseNumberForCostReduction", () -> super.chooseNumberForCostReduction(sa, min, max));
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, forge.game.cost.Cost cost, KeywordInterface keyword, String prompt,
            int max) {
        return fb("chooseNumberForKeywordCost", () -> super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max));
    }

    @Override
    public boolean addKeywordCost(SpellAbility sa, forge.game.cost.Cost cost, KeywordInterface keyword, String prompt) {
        return fb("addKeywordCost", () -> super.addKeywordCost(sa, cost, keyword, prompt));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return fb("chooseNumber", () -> super.chooseNumber(sa, title, min, max));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String string, int min, int max, Map<String, Object> params) {
        return fb("chooseNumber", () -> super.chooseNumber(sa, string, min, max, params));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> options, Player relatedPlayer) {
        return fb("chooseNumber", () -> super.chooseNumber(sa, title, options, relatedPlayer));
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        return fb("chooseBinary", () -> super.chooseBinary(sa, question, kindOfChoice, defaultVal));
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Map<String, Object> params) {
        return fb("chooseBinary", () -> super.chooseBinary(sa, question, kindOfChoice, params));
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return fb("chooseFlipResult", () -> super.chooseFlipResult(sa, flipper, call));
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, forge.card.ColorSet colors) {
        return fb("chooseColor", () -> super.chooseColor(message, sa, colors));
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card card, forge.card.ColorSet colors) {
        return fb("chooseColorAllowColorless", () -> super.chooseColorAllowColorless(message, card, colors));
    }

    @Override
    public forge.card.ColorSet chooseColors(String message, SpellAbility sa, int min, int max, forge.card.ColorSet options) {
        return fb("chooseColors", () -> super.chooseColors(message, sa, min, max, options));
    }

    @Override
    public forge.card.ICardFace chooseSingleCardFace(SpellAbility sa, String message,
            java.util.function.Predicate<forge.card.ICardFace> cpp, String name) {
        return fb("chooseSingleCardFace", () -> super.chooseSingleCardFace(sa, message, cpp, name));
    }

    @Override
    public forge.card.ICardFace chooseSingleCardFace(SpellAbility sa, List<forge.card.ICardFace> faces, String message) {
        return fb("chooseSingleCardFace", () -> super.chooseSingleCardFace(sa, faces, message));
    }

    @Override
    public forge.game.card.CardState chooseSingleCardState(SpellAbility sa, List<forge.game.card.CardState> states,
            String message, Map<String, Object> params) {
        return fb("chooseSingleCardState", () -> super.chooseSingleCardState(sa, states, message, params));
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        return fb("chooseCardsPile", () -> super.chooseCardsPile(sa, pile1, pile2, faceUp));
    }

    @Override
    public forge.game.card.CounterType chooseCounterType(List<forge.game.card.CounterType> options, SpellAbility sa,
            String prompt, Map<String, Object> params) {
        return fb("chooseCounterType", () -> super.chooseCounterType(options, sa, prompt, params));
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        return fb("chooseKeywordForPump", () -> super.chooseKeywordForPump(options, sa, prompt, tgtCard));
    }

    @Override
    public boolean confirmPayment(forge.game.cost.CostPart costPart, String prompt, SpellAbility sa) {
        return fb("confirmPayment", () -> super.confirmPayment(costPart, prompt, sa));
    }

    @Override
    public forge.game.replacement.ReplacementEffect chooseSingleReplacementEffect(
            List<forge.game.replacement.ReplacementEffect> possibleReplacers) {
        return fb("chooseSingleReplacementEffect", () -> super.chooseSingleReplacementEffect(possibleReplacers));
    }

    @Override
    public forge.game.staticability.StaticAbility chooseSingleStaticAbility(
            List<forge.game.staticability.StaticAbility> possibleStatics) {
        return fb("chooseSingleStaticAbility", () -> super.chooseSingleStaticAbility(possibleStatics));
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        return fb("chooseProtectionType", () -> super.chooseProtectionType(sa, choices));
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        return fb("chooseOptionalCosts", () -> super.chooseOptionalCosts(chosen, optionalCostValues));
    }

    @Override
    public List<forge.game.cost.CostPart> orderCosts(List<forge.game.cost.CostPart> costs) {
        return fb("orderCosts", () -> super.orderCosts(costs));
    }

    @Override
    public boolean payCostToPreventEffect(forge.game.cost.Cost cost, SpellAbility sa, boolean alreadyPaid,
            FCollectionView<Player> allPayers) {
        return fb("payCostToPreventEffect", () -> super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers));
    }

    @Override
    public boolean payCostDuringRoll(forge.game.cost.Cost cost, SpellAbility sa) {
        return fb("payCostDuringRoll", () -> super.payCostDuringRoll(cost, sa));
    }

    @Override
    public boolean payCombatCost(Card c, forge.game.cost.Cost cost, SpellAbility sa, String prompt) {
        return fb("payCombatCost", () -> super.payCombatCost(c, cost, sa, prompt));
    }

    @Override
    public boolean payManaCost(forge.card.mana.ManaCost toPay, forge.game.cost.CostPartMana costPartMana, SpellAbility sa,
            String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
        return fb("payManaCost", () -> super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect));
    }

    @Override
    public boolean applyManaToCost(forge.game.mana.ManaCostBeingPaid toPay, SpellAbility ability, String prompt,
            forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
        return fb("applyManaToCost", () -> super.applyManaToCost(toPay, ability, prompt, matrix, effect));
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa,
            forge.game.cost.CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        return fb("chooseCardsForCost", () -> super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt));
    }

    @Override
    public forge.game.cost.CostDecisionMakerBase getCostDecisionMaker(Player p, SpellAbility ability, boolean effect) {
        return fb("getCostDecisionMaker", () -> super.getCostDecisionMaker(p, ability, effect));
    }

    @Override
    public forge.game.cost.CostDecisionMakerBase getCostDecisionMaker(Player p, SpellAbility ability, boolean effect,
            String prompt) {
        return fb("getCostDecisionMaker", () -> super.getCostDecisionMaker(p, ability, effect, prompt));
    }

    @Override
    public String chooseCardName(SpellAbility sa, java.util.function.Predicate<forge.card.ICardFace> cpp, String valid,
            String message) {
        return fb("chooseCardName", () -> super.chooseCardName(sa, cpp, valid, message));
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<forge.card.ICardFace> faces, String message) {
        return fb("chooseCardName", () -> super.chooseCardName(sa, faces, message));
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        return fb("chooseSingleCardForZoneChange", () -> super.chooseSingleCardForZoneChange(destination, origin, sa,
                fetchList, delayedReveal, selectPrompt, isOptional, decider));
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        return fb("chooseCardsForZoneChange", () -> super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min,
                max, delayedReveal, selectPrompt, decider));
    }

    /** Starts the bridge process. The caller owns it and must close it. */
    public static Process start(String binary, List<String> args, long seed) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.addAll(args);
        cmd.add("-seed");
        cmd.add(Long.toString(seed));
        ProcessBuilder pb = new ProcessBuilder(cmd);
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
