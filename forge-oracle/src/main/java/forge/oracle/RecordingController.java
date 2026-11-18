// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * A Forge AI seat that writes every decision it makes to the game's
 * {@link ShadowLog} ({@code forge-shadow-1}; the line grammar is there).
 *
 * The decision is the AI parent's: each hook lists the options Forge offers
 * (for priority, the list {@link BridgeController} sends its bridge), calls
 * the parent, and logs the answer. The hooks are BridgeController's
 * overrides (priority, attackers, blockers, targets) plus the pregame
 * mulligan, and the parent's {@code cheatShuffle}, which
 * {@code Player.shuffle} calls with the shuffled library on every shuffle,
 * opening included. Questions the AI asks itself while it deliberates (a
 * target it tries inside its own priority search) are probes, not decisions,
 * and are not logged.
 *
 * <p>In <b>random</b> mode (section 6 of the Commander-diff design) the seat
 * answers priority, attack, block and mulligan questions with a seeded
 * uniform pick over the options it lists, and the AI parent answers
 * everything it does not list (targets of a random pick, costs, every other
 * question). An invalid random attack or block falls back to the parent,
 * logged with policy "ai".
 */
public final class RecordingController extends PlayerControllerAi {
    private final ShadowLog log;
    private final int seat;
    private final Random rnd;
    private int probe;
    private JsonObject playing;
    private int playsLeft;
    private CardCollectionView tucked;
    private final List<SpellAbility> unavailable = new ArrayList<>();

    /** {@code rnd} non-null makes this a random-mode seat. */
    public RecordingController(Game game, Player p, LobbyPlayerAi lp, ShadowLog log, int seat, Random rnd) {
        super(game, p, lp);
        this.log = log;
        this.seat = seat;
        this.rnd = rnd;
    }

    private <T> T parent(Supplier<T> f) {
        probe++;
        try {
            return f.get();
        } finally {
            probe--;
        }
    }

    private void parentRun(Runnable r) {
        parent(() -> {
            r.run();
            return null;
        });
    }

    private String policy() {
        return rnd == null ? "ai" : "random";
    }

    // ---- shuffle and mulligan -------------------------------------------------

    @Override
    public CardCollectionView cheatShuffle(CardCollectionView list) {
        CardCollection before = new CardCollection(player.getCardsIn(ZoneType.Library));
        CardCollectionView after = super.cheatShuffle(list);
        log.shuffle(seat, before, after);
        return after;
    }

    /** Forge's London mulligan draws, bottoms, then asks keep-or-mulligan
     * again; the log reads it in rules order, so the bottom rides on the
     * keep that follows it. */
    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        boolean keep = rnd != null ? rnd.nextBoolean() : parent(() -> super.mulliganKeepHand(firstPlayer, cardsToReturn));
        log.mull(seat, keep, keep ? tucked : null, player.getCardsIn(ZoneType.Hand));
        tucked = null;
        return keep;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        CardCollectionView bottom;
        if (rnd != null) {
            List<Card> pool = new ArrayList<>(hand);
            CardCollection pick = new CardCollection();
            for (int i = 0; i < cardsToReturn && !pool.isEmpty(); i++) {
                pick.add(pool.remove(rnd.nextInt(pool.size())));
            }
            bottom = pick;
        } else {
            bottom = parent(() -> super.tuckCardsViaMulligan(hand, cardsToReturn));
        }
        tucked = cardsToReturn > 0 ? new CardCollection(bottom) : null;
        return bottom;
    }

    // ---- priority ---------------------------------------------------------------

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (probe > 0) {
            return super.chooseSpellAbilityToPlay();
        }
        List<SpellAbility> all = BridgeController.offer(getGame(), player, unavailable);
        JsonArray opts = new JsonArray();
        for (int i = 0; i < all.size(); i++) {
            SpellAbility sa = all.get(i);
            JsonObject o = describe(sa);
            o.addProperty("index", i);
            o.addProperty("label", BridgeController.label(sa));
            String mode = BridgeController.mode(sa);
            if (!mode.isEmpty()) {
                o.addProperty("mode", mode);
            }
            if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null) {
                o.addProperty("mana_cost", sa.getPayCosts().getTotalMana().toString());
            }
            opts.add(o);
        }

        List<SpellAbility> pick = rnd != null ? randomPick(all) : parent(super::chooseSpellAbilityToPlay);
        unavailable.clear();
        if (pick == null || pick.isEmpty()) {
            JsonObject ans = new JsonObject();
            ans.addProperty("action", "pass");
            log.ask(seat, "priority", policy(), opts, ans);
            return pick;
        }
        SpellAbility sa = pick.get(0);
        JsonObject ans = describe(sa);
        ans.addProperty("index", indexOf(all, sa));
        ans.addProperty("label", BridgeController.label(sa));
        String mode = BridgeController.mode(sa);
        if (!mode.isEmpty()) {
            ans.addProperty("mode", mode);
        }
        if (pick.size() > 1) {
            ans.addProperty("count", pick.size());
        }
        log.hold();
        log.ask(seat, "priority", policy(), opts, ans);
        playing = ans;
        playsLeft = pick.size();
        return pick;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        JsonObject ans = playing;
        if (ans == null) {
            return super.playChosenSpellAbility(sa);
        }
        boolean first = !ans.has("played");
        log.startTaps();
        boolean ok = false;
        try {
            ok = super.playChosenSpellAbility(sa);
        } finally {
            JsonArray taps = log.stopTaps();
            if (first) {
                JsonArray targets = new JsonArray();
                JsonArray slots = log.targets(sa, targets);
                ans.add("tapped", taps);
                ans.add("targets", targets);
                ans.add("target_slots", slots);
                ans.addProperty("played", ok);
            }
            if (--playsLeft <= 0) {
                playing = null;
                log.release();
            }
        }
        return ok;
    }

    /** A uniform pick over the listed options plus pass. A pick whose
     * targets Forge cannot fill is dropped for this window and the pick is
     * made again, as the bridge does. */
    private List<SpellAbility> randomPick(List<SpellAbility> all) {
        while (true) {
            int i = rnd.nextInt(all.size() + 1);
            if (i == all.size()) {
                return null;
            }
            SpellAbility chosen = all.get(i);
            if (chosen.costHasManaX() && chosen.getXManaCostPaid() == null) {
                chosen.setXManaCostPaid(forge.ai.ComputerUtilMana.determineLeftoverMana(chosen, player, false));
            }
            if (BridgeController.needsTargets(chosen)) {
                chosen.resetTargets();
                parent(() -> super.chooseTargetsFor(chosen));
                if (!BridgeController.targetsValid(chosen)) {
                    chosen.resetTargets();
                    unavailable.add(chosen);
                    all = BridgeController.offer(getGame(), player, unavailable);
                    continue;
                }
            }
            return List.of(chosen);
        }
    }

    /** The reader's (action, card, ability) of one priority action. */
    private static JsonObject describe(SpellAbility sa) {
        JsonObject o = new JsonObject();
        o.addProperty("action", action(sa));
        Card host = sa.getHostCard();
        if (host != null) {
            o.addProperty("card", host.getId());
        }
        int ab = abilityIndex(sa);
        if (!sa.isLandAbility() && ab >= 0) {
            o.addProperty("ability", ab);
        }
        return o;
    }

    private static String action(SpellAbility sa) {
        switch (BridgeController.actionKind(sa)) {
            case "play_land": return "land";
            case "cast": return "cast";
            case "ability": return "activate";
            default: return "other";
        }
    }

    /** The index of sa (or the ability it was copied from) in its host's
     * spell abilities; -1 when none matches. */
    static int abilityIndex(SpellAbility sa) {
        Card host = sa.getHostCard();
        if (host == null) {
            return -1;
        }
        var list = host.getSpellAbilities();
        SpellAbility cur = sa;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) == cur) {
                    return i;
                }
            }
            cur = cur.getOriginalAbility();
        }
        return -1;
    }

    /** The offered option Forge's pick is: the same object, else the first
     * option with the same (action, card, ability, label, mode, description),
     * since the AI builds its own copies of alternative-cost variants. */
    private static int indexOf(List<SpellAbility> all, SpellAbility sa) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == sa) {
                return i;
            }
        }
        String key = key(sa);
        for (int i = 0; i < all.size(); i++) {
            if (key(all.get(i)).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static String key(SpellAbility sa) {
        return describe(sa) + "|" + BridgeController.label(sa) + "|" + BridgeController.mode(sa) + "|" + sa.getDescription();
    }

    // ---- combat -----------------------------------------------------------------

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (probe > 0) {
            super.declareAttackers(attacker, combat);
            return;
        }
        // Players first, then planeswalkers and battles, in Forge's order.
        List<GameEntity> defenders = new ArrayList<>();
        for (GameEntity e : combat.getDefenders()) {
            if (e instanceof Player) {
                defenders.add(e);
            }
        }
        for (GameEntity e : combat.getDefenders()) {
            if (!(e instanceof Player)) {
                defenders.add(e);
            }
        }
        JsonArray opts = new JsonArray();
        List<Card> cands = new ArrayList<>();
        List<List<GameEntity>> legal = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            List<GameEntity> ok = new ArrayList<>();
            for (GameEntity d : defenders) {
                if (CombatUtil.canAttack(c, d)) {
                    ok.add(d);
                    JsonObject o = new JsonObject();
                    o.addProperty("card", c.getId());
                    defender(o, "player", d);
                    o.addProperty("label", c.getName());
                    opts.add(o);
                }
            }
            if (!ok.isEmpty()) {
                cands.add(c);
                legal.add(ok);
            }
        }
        if (cands.isEmpty()) {
            parentRun(() -> super.declareAttackers(attacker, combat));
            return;
        }
        String pol = "ai";
        if (rnd != null) {
            // Each attacker in turn: stay home, or one of its defenders.
            for (int i = 0; i < cands.size(); i++) {
                List<GameEntity> ok = legal.get(i);
                int k = rnd.nextInt(ok.size() + 1);
                if (k > 0) {
                    combat.addAttacker(cands.get(i), ok.get(k - 1));
                }
            }
            if (CombatUtil.validateAttackers(combat)) {
                pol = "random";
            } else {
                combat.clearAttackers();
            }
        }
        if (pol.equals("ai")) {
            parentRun(() -> super.declareAttackers(attacker, combat));
        }
        JsonArray attacks = new JsonArray();
        for (Card a : combat.getAttackers()) {
            JsonObject o = new JsonObject();
            o.addProperty("card", a.getId());
            defender(o, "defender", combat.getDefenderByAttacker(a));
            attacks.add(o);
        }
        JsonObject ans = new JsonObject();
        ans.add("attacks", attacks);
        log.ask(seat, "attack", pol, opts, ans);
    }

    /** A player defender under {@code key}; a permanent as -1 (answers only)
     * and its id under {@code defender_card}. */
    private void defender(JsonObject o, String key, GameEntity d) {
        if (d instanceof Player p) {
            o.addProperty(key, log.seatOf(p));
            return;
        }
        if (key.equals("defender")) {
            o.addProperty(key, -1);
        }
        if (d instanceof Card c) {
            o.addProperty("defender_card", c.getId());
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        if (probe > 0) {
            super.declareBlockers(defender, combat);
            return;
        }
        CardCollection attackers = combat.getAttackers();
        JsonArray opts = new JsonArray();
        List<Card> pairB = new ArrayList<>();
        List<Card> pairA = new ArrayList<>();
        for (Card b : defender.getCreaturesInPlay()) {
            if (b.isTapped() || !CombatUtil.canBlock(b, combat)) {
                continue;
            }
            for (Card a : attackers) {
                if (!CombatUtil.canBlock(a, b, combat)) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("card", b.getId());
                o.addProperty("attacker", a.getId());
                o.addProperty("label", b.getName() + " blocks " + a.getName());
                opts.add(o);
                pairB.add(b);
                pairA.add(a);
            }
        }
        if (pairB.isEmpty()) {
            parentRun(() -> super.declareBlockers(defender, combat));
            return;
        }
        String pol = "ai";
        if (rnd != null) {
            // Each blocker, in offer order: no block or one of its attackers.
            List<Card> added = new ArrayList<>();
            int i = 0;
            while (i < pairB.size()) {
                Card b = pairB.get(i);
                int j = i;
                while (j < pairB.size() && pairB.get(j) == b) {
                    j++;
                }
                int k = rnd.nextInt(j - i + 1);
                if (k > 0) {
                    combat.addBlocker(pairA.get(i + k - 1), b);
                    added.add(b);
                }
                i = j;
            }
            if (CombatUtil.validateBlocks(combat, defender) == null) {
                pol = "random";
            } else {
                for (Card b : added) {
                    combat.undoBlockingAssignment(b);
                }
            }
        }
        if (pol.equals("ai")) {
            parentRun(() -> super.declareBlockers(defender, combat));
        }
        JsonArray blocks = new JsonArray();
        for (Card a : attackers) {
            for (Card b : combat.getBlockers(a)) {
                JsonObject o = new JsonObject();
                o.addProperty("card", b.getId());
                o.addProperty("attacker", a.getId());
                blocks.add(o);
            }
        }
        JsonObject ans = new JsonObject();
        ans.add("blocks", blocks);
        log.ask(seat, "block", pol, opts, ans);
    }

    // ---- targets ----------------------------------------------------------------

    @Override
    public boolean chooseTargetsFor(SpellAbility sa) {
        if (probe > 0 || !BridgeController.needsTargets(sa)) {
            return super.chooseTargetsFor(sa);
        }
        JsonArray opts = new JsonArray();
        int slot = 0;
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting()) {
                continue;
            }
            List<JsonObject> cands = new ArrayList<>();
            if (cur.getActivatingPlayer() != null) {
                for (var e : log.refs(cur.getTargetRestrictions().getAllCandidates(cur))) {
                    cands.add(e.getAsJsonObject());
                }
                if (cur.getTargetRestrictions().getZone().contains(ZoneType.Stack)) {
                    for (SpellAbilityStackInstance si : getGame().getStack()) {
                        if (cur.canTargetSpellAbility(si.getSpellAbility())) {
                            JsonObject r = log.ref(si.getSpellAbility());
                            if (r != null) {
                                cands.add(r);
                            }
                        }
                    }
                }
            }
            for (JsonObject c : cands) {
                c.addProperty("slot", slot);
                opts.add(c);
            }
            slot++;
        }
        boolean ok = parent(() -> super.chooseTargetsFor(sa));
        JsonObject ans = new JsonObject();
        JsonArray targets = new JsonArray();
        JsonArray slots = log.targets(sa, targets);
        ans.add("targets", targets);
        ans.add("target_slots", slots);
        Card host = sa.getHostCard();
        if (host != null) {
            ans.addProperty("source", host.getId());
        }
        ans.addProperty("label", BridgeController.label(sa));
        ans.addProperty("ok", ok);
        log.ask(seat, "target", "ai", opts, ans);
        return ok;
    }
}
