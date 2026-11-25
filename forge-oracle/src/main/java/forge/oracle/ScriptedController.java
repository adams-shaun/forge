// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.ListMultimap;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameType;
import forge.game.PlanarDice;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CounterType;
import forge.game.card.sticker.Sticker;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPartWithList;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.DelayedReveal;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.oracle.DecisionQueue.Decision;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;

/**
 * The scripted seat (DESIGN 6.5). It extends the AI controller only for a
 * complete fallback: every abstract PlayerController method that DECIDES
 * something is overridden here (ControllerCensusTest enforces it), and either
 * <ul>
 * <li>is answered from the scenario: priority actions from the StepMachine,
 *     targets / X / yes-no / modes / object picks from gorge's decision log;</li>
 * <li>is answered deterministically where there is nothing to choose (one
 *     candidate, a mandatory all-of, a fixed order), as gorge's engine also
 *     does without posing a decision; or</li>
 * <li>is a strict miss: in strict mode the scenario fails with "Missing
 *     &lt;kind&gt; answer at step i" and is retried loose, where the AI parent
 *     answers and the row records strict_miss.</li>
 * </ul>
 * Mana is paid from the floating pool only, through the engine's own
 * payment code; no land is ever tapped for mana by the driver.
 */
public class ScriptedController extends PlayerControllerAi {
    final StepMachine m;
    final int seat;
    /** Guards {@link #payFromOwnSources} against a mana ability that itself
     * has a mana cost (no basic land does; a chain would re-enter). */
    private boolean payingOwnSources;
    /** True while payCostToPreventEffect pays on gorge's answer (an unless_pay, echo
     * or cumulative-upkeep election): that one answer IS the payer's choice (CR 118.12),
     * so the engine's per-part "do you want to pay" confirm inside
     * payCostDuringAbilityResolve (energy, counters, discard, exile, shards, ...) must
     * not look for a second decision. */
    private boolean payingUnlessCost;

    /** gorge's echo and cumulative-upkeep pay-or-sacrifice answers: each is ONE
     * KChoose whose chosen option kind is the pick kind (engine/rules/echo.go,
     * engine/rules/cumulative.go; CR 702.35a / 702.46a). Forge poses the same
     * election from the keyword's upkeep trigger (DB$ Sacrifice | Echo$ /
     * CumulativeUpkeep$) through SacrificeEffect's payCostToPreventEffect, so
     * the answer is taken here and matched by pick kind, never by its label. */
    private static final Set<String> ECHO_CUMULATIVE_ANSWERS = Set.of(
            "echo_pay", "echo_pay_manual", "echo_sac",
            "cumulative_pay", "cumulative_pay_manual", "cumulative_sac");

    /** gorge's Riot pick label for entering with the counter (engine/rules/cast_etbchoice.go). */
    private static final String RIOT_COUNTER = "Enter with a +1/+1 counter";

    /** Whether sa is Riot's haste-unless-counter Animate (CardFactoryUtil, keyword "Riot":
     * DB$ Animate | Keywords$ Haste | UnlessCost$ AddCounter<1/P1P1> | SpellDescription$ Riot). */
    static boolean isRiotChoice(SpellAbility sa) {
        return sa != null && sa.getApi() == ApiType.Animate && "Riot".equals(sa.getParam("SpellDescription"))
                && sa.getHostCard() != null && sa.getHostCard().hasKeyword(forge.game.keyword.Keyword.RIOT);
    }

    private static boolean isEchoCumulativeAnswer(Decision d) {
        return d.picks.size() == 1 && d.pickKinds.size() == 1
                && ECHO_CUMULATIVE_ANSWERS.contains(d.pickKinds.get(0));
    }

    /** The in-flight ChangeZoneFromAltSource search (OriginAlternative$):
     * gorge posed ONE zone election where Forge asks an initial "search the
     * library?" confirm and then one confirm per alternative zone. The
     * election is taken once and its picks answer the remaining confirms;
     * altZoneLeft is the number of per-zone confirms still to come (the
     * election has one option per origin zone, library first). */
    private int altZoneStep = -99;
    private int altZoneSeat = -1;
    private boolean altZoneActive;
    private Decision altZone;
    private boolean altZoneDeclined;
    private int altZoneLeft;

    public ScriptedController(Game game, Player p, LobbyPlayer lp, StepMachine m, int seat) {
        super(game, p, lp);
        this.m = m;
        this.seat = seat;
    }

    private Decision take(Predicate<Decision> match) {
        return m.take(seat, match);
    }

    private Decision peek(Predicate<Decision> match) {
        return m.peek(seat, match);
    }

    // ---- priority and playing ---------------------------------------------

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return m.onPriority(this);
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        return PlaySpellAbility.playSpellAbility(this, player, sa);
    }

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        if (abilities.size() == 1) {
            return abilities.get(0);
        }
        // An either-or additional cost (AlternateAdditionalCost: forage, ...):
        // gorge's altaddcost pick names the cost; Forge offers one SA per cost.
        Decision d = take(x -> x.pickKinds.contains("altaddcost") && x.picks.size() == 1);
        if (d != null) {
            String want = d.picks.get(0).toLowerCase(java.util.Locale.ROOT);
            SpellAbility hit = null;
            int n = 0;
            for (SpellAbility sa : abilities) {
                String cost = sa.getPayCosts() == null ? "" : sa.getPayCosts().toString().toLowerCase(java.util.Locale.ROOT);
                String desc = String.valueOf(sa).toLowerCase(java.util.Locale.ROOT);
                if (cost.contains(want) || desc.contains(want)) {
                    hit = sa;
                    n++;
                }
            }
            if (n != 1) {
                // gorge's label names the cost by its verb ("Discard 1 card",
                // "Sacrifice creature or enchantment", "Forage"): bind to the
                // one SA whose cost has that kind of part.
                String verb = want.split(" ")[0];
                hit = null;
                n = 0;
                for (SpellAbility sa : abilities) {
                    if (sa.getPayCosts() == null) {
                        continue;
                    }
                    for (CostPart part : sa.getPayCosts().getCostParts()) {
                        String cls = part.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
                        if (cls.equals("cost" + verb)) {
                            hit = sa;
                            n++;
                            break;
                        }
                    }
                }
            }
            if (n == 1) {
                m.note("ability choice step " + m.step() + ": " + d.picks.get(0));
                return hit;
            }
        }
        m.miss("ability choice", "getAbilityToPlay over " + abilities.size() + " abilities of " + hostCard);
        return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        PlaySpellAbility.playSpellAbilityNoStack(this, player, effectSA, !mayChoseNewTargets);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        return PlaySpellAbility.playSpellAbilityNoStack(this, player, wrapperAbility, false);
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        return PlaySpellAbility.playSpellAbility(this, player, tgtSA);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs.size() <= 1) {
            return activePlayerSAs;
        }
        List<SpellAbility> ordered = m.orderTriggers(seat, activePlayerSAs);
        return ordered != null ? ordered : super.orderSimultaneousSa(activePlayerSAs);
    }

    /** The human controller's loop (PlayerControllerHuman.orderAndPlaySimultaneousSa),
     * so trigger targets go through chooseTargetsFor below rather than the AI. */
    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        List<SpellAbility> ordered = orderSimultaneousSa(activePlayerSAs);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            SpellAbility next = ordered.get(i);
            if (next.isTrigger() && !next.isCopied()) {
                PlaySpellAbility.playSpellAbility(this, player, next);
            } else {
                if (next.isCopied()) {
                    if (next.isSpell()) {
                        if (!next.getHostCard().isInZone(ZoneType.Stack)) {
                            next.setHostCard(player.getGame().getAction().moveToStack(next.getHostCard(), next));
                        } else {
                            player.getGame().getStackZone().add(next.getHostCard());
                        }
                    }
                    if (next.isMayChooseNewTargets()) {
                        next.setupNewTargets(player);
                    }
                }
                player.getGame().getStack().add(next);
            }
        }
    }

    // ---- targets, X, modes ------------------------------------------------

    @Override
    public boolean chooseTargetsFor(SpellAbility sa) {
        return m.chooseTargets(this, sa);
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        // A copy's "you may choose new targets": gorge logs the copy's
        // targets as a target ask with resume copy_targets.
        TargetChoices t = m.copyTargets(seat, ability, filter);
        if (t != null || optional && !m.hasCopyTargets(seat)) {
            return t; // null keeps the old targets
        }
        m.miss("new targets", String.valueOf(ability));
        return super.chooseNewTargetsFor(ability, filter, optional);
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        if (allTargets.size() == 1) {
            return allTargets.get(0);
        }
        m.miss("target to change", String.valueOf(sa));
        return super.chooseTarget(sa, allTargets);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        if ("X".equalsIgnoreCase(announce)) {
            Decision d = take(DecisionQueue::isX);
            Integer v = d == null ? null : DecisionQueue.xValue(d);
            if (v != null) {
                m.note("announce X=" + v + " (range " + min + ".." + max + ")");
                return v;
            }
        }
        if (min == max) {
            return min;
        }
        if (!"X".equalsIgnoreCase(announce)) {
            // A "choose any number" ask that is not an {X} (Join Forces:
            // "amount of mana to pay"): gorge logs it as a number pick.
            Integer n = m.announceNumber(seat, min, max);
            if (n != null) {
                m.note("announce " + announce + "=" + n + " (range " + min + ".." + max + ")");
                return n;
            }
        }
        if (min == 0 && m.take(seat, x -> x.pickKinds.contains("trigger_cost_decline")) != null) {
            // "you may pay {1} up to three times": gorge declined paying at all.
            m.note("announce " + announce + "=0 (gorge: trigger_cost_decline)");
            return 0;
        }
        m.miss("x", announce + " for " + ability);
        return super.announceRequirements(ability, min, max, announce);
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        if (num == 0) {
            // CR 700.2/603.3c: "choose up to zero modes" has exactly one legal
            // answer, none, and a modal ability with no mode chosen is removed
            // from the stack. gorge poses no KModes decision for it (charm.go
            // returns before the ask when max < 1), so do not consume a mode
            // decision that is queued for a later ask.
            m.note("modes step " + m.step() + ": none (0 modes possible"
                    + (m.hasMode(seat) ? "; queued mode answer left for the next ask" : "") + ")");
            return new ArrayList<>();
        }
        List<AbilitySub> picked = m.chooseModes(seat, sa, possible, min, num, allowRepeat);
        if (picked != null) {
            return picked;
        }
        if (possible.size() == num && !allowRepeat) {
            return possible;
        }
        m.miss("mode", String.valueOf(sa));
        return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
    }

    // ---- yes / no ---------------------------------------------------------

    /** Answer a ChangeZoneFromAltSource confirm from gorge's search_zones
     * election. null means this is not that shape, so the generic yes/no path
     * runs (and reports a strict miss when nothing answers it). */
    private Boolean altSourceAnswer(int seat, String message) {
        int step = m.step();
        if (step != altZoneStep || seat != altZoneSeat) {
            altZoneStep = step;
            altZoneSeat = seat;
            altZoneActive = false;
            altZone = null;
            altZoneDeclined = false;
            altZoneLeft = 0;
        }
        String zone = DecisionQueue.zoneWord(message);
        boolean perZone = message != null
                && message.trim().toLowerCase(java.util.Locale.ROOT).startsWith("search your ")
                && !zone.isEmpty();
        if (!perZone && altZoneActive) {
            // Another effect's initial confirm at the same step.
            altZoneActive = false;
            altZone = null;
            altZoneDeclined = false;
            altZoneLeft = 0;
        }
        if (!perZone) {
            // The initial "search the library?" confirm is gorge's zone
            // election, not its optional search_confirm: Forge asks the
            // Optional$ gate later, as a ChangeZoneGeneral confirm, so the
            // search_confirm must stay for that ask.
            Decision z = take(DecisionQueue::isSearchZones);
            if (z != null) {
                startAltZones(z);
                boolean yes = DecisionQueue.searchesZone(z, "library");
                m.note("alt source step " + step + ": library " + (yes ? "yes" : "no"));
                return yes;
            }
            // An optional search declined: gorge logged search_confirm "No"
            // and no zone election. Answer the initial and every per-zone
            // confirm no, leaving the search_confirm for Forge's later gate.
            Decision sc = peek(x -> x.resume.equals("search_confirm") && DecisionQueue.isYesNo(x));
            if (sc != null && !DecisionQueue.yes(sc)) {
                altZoneActive = true;
                altZoneDeclined = true;
                m.note("alt source step " + step + ": declined (no zone election)");
                return false;
            }
            return null;
        }
        // A per-zone confirm ("Search your graveyard?"). A declined optional
        // search posed no election, and every zone is then unsearched.
        if (altZoneDeclined) {
            return false;
        }
        if (altZone == null) {
            return null; // the initial confirm found no election; name the ask
        }
        boolean yes = DecisionQueue.searchesZone(altZone, zone);
        m.note("alt source step " + step + ": " + zone + " " + (yes ? "yes" : "no"));
        altZoneLeft--;
        if (altZoneLeft <= 0) {
            altZoneActive = false;
        }
        return yes;
    }

    private void startAltZones(Decision z) {
        altZone = z;
        altZoneActive = true;
        altZoneDeclined = false;
        // Forge asks one confirm per OriginAlternative$ zone after the initial
        // library confirm; the election holds one option per origin zone.
        altZoneLeft = Math.max(0, z.max - 1);
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        if (mode == PlayerActionConfirmMode.ChangeZoneFromAltSource) {
            Boolean b = altSourceAnswer(seat, message);
            if (b != null) {
                return b;
            }
        }
        if (mode == PlayerActionConfirmMode.ChangeZoneToAltDestination && options != null && options.size() == 2) {
            // true keeps the first destination (ChangeZoneEffect): gorge's
            // changezone_alternative pick names it ("top" / "bottom", a zone).
            Decision d = take(x -> x.resume.equals("changezone_alternative") && x.picks.size() == 1);
            if (d != null) {
                // gorge's AlternativeDecider$ ask (engine/effects/zone_change.go)
                // has two kinds: "primary" is LibraryPosition$ (Forge's first
                // option, "Top (2nd)" when it is 1, while gorge labels it
                // "second from top") and "bottom" is the -1 alternative.
                String kind = d.pickKinds.size() == 1 ? d.pickKinds.get(0) : "";
                if (kind.equals("primary") || kind.equals("bottom")) {
                    m.note("alt destination step " + m.step() + ": " + d.picks.get(0) + " (" + kind + ")");
                    return kind.equals("primary");
                }
                String pick = d.picks.get(0).trim().toLowerCase(java.util.Locale.ROOT);
                boolean first = options.get(0).toLowerCase(java.util.Locale.ROOT).startsWith(pick);
                boolean second = options.get(1).toLowerCase(java.util.Locale.ROOT).startsWith(pick);
                if (first != second) {
                    m.note("alt destination step " + m.step() + ": " + d.picks.get(0));
                    return first;
                }
            }
        }
        boolean cancelSearch = mode == PlayerActionConfirmMode.ChangeZoneGeneral
                && message != null && message.startsWith("Cancel Search");
        if (cancelSearch) {
            // Forge asks after each searched card; gorge made one pick of up to
            // N. This prompt is not the Optional$ gate: when no search pick is
            // in flight it is a strict miss, never a zero-candidate decline, or
            // Forge's ChangeZoneEffect would loop (`i--; continue`) and its AI
            // could take a card the recorded pick declined. That includes a
            // recorded empty pick: pickObjects never arms the carry for one.
            Boolean more = m.searchWantsMore(seat);
            if (more != null) {
                return !more;
            }
        }
        if (mode == null && sa != null && sa.getApi() == ApiType.Sacrifice
                && sa.hasParam("Optional") && !sa.hasParam("Echo") && !sa.hasParam("CumulativeUpkeep")) {
            return sacrificeConfirm(sa);
        }
        if (mode == null && sa != null && sa.getApi() == ApiType.Attach && sa.hasParam("Optional") && cardToShow != null) {
            Boolean attach = attachConfirm(sa, cardToShow);
            if (attach != null) {
                return attach;
            }
        }
        if (!cancelSearch && (mode == null || mode == PlayerActionConfirmMode.ChangeZoneGeneral)
                && zeroCandidateHiddenConfirm(sa)) {
            m.note("zero-candidate confirm step " + m.step() + ": no (" + message + ")");
            return false;
        }
        if (mode == null && emptyOptionalAnimateConfirm(sa)) {
            m.note("empty animate confirm step " + m.step() + ": no (" + message + ")");
            return false;
        }
        Boolean b = m.yesNo(seat, "confirm " + mode + ": " + message);
        return b != null ? b : super.confirmAction(sa, mode, message, options, cardToShow, params);
    }

    /** Whether this is an Optional$ Animate gate with nothing to animate, which
     * gorge never poses: Sita Varma, Masked Racer's "you may have the base power
     * and toughness of each other creature you control become ..." with no other
     * creature is an election with no board effect either way (CR 608.2d), so
     * gorge logs no ask, while AnimateEffect still asks "It has base power and
     * toughness N/N until end of turn." Answering no changes nothing. It holds
     * only when no yes/no decision is queued for the seat and the cards the
     * effect would animate (the ones AnimateEffect.getCardsfromTargets finds:
     * the targets of a targeting ability, else every Defined$ part, plus
     * ThisDefinedAndTgts$) include none on the battlefield that is not phased
     * out (the ones its loop would skip, CR 702.26e). A target spell, or any
     * card still on the battlefield, keeps the strict miss. */
    private boolean emptyOptionalAnimateConfirm(SpellAbility sa) {
        if (sa == null || sa.getApi() != ApiType.Animate || !sa.hasParam("Optional")
                || peek(DecisionQueue::isYesNo) != null) {
            return false;
        }
        Card host = sa.getHostCard();
        if (host == null) {
            return false;
        }
        if (sa.getTargets().getTargetSpells().iterator().hasNext()) {
            return false;
        }
        List<Card> affected = new ArrayList<>();
        if (sa.hasParam("ThisDefinedAndTgts")) {
            affected.addAll(AbilityUtils.getDefinedCards(host, sa.getParam("ThisDefinedAndTgts"), sa));
            sa.getTargets().getTargetCards().forEach(affected::add);
        } else if (sa.usesTargeting()) {
            sa.getTargets().getTargetCards().forEach(affected::add);
        } else {
            for (String d : sa.getParamOrDefault("Defined", "Self").split(" & ")) {
                affected.addAll(AbilityUtils.getDefinedCards(host, d, sa));
            }
        }
        for (Card c : affected) {
            if (c.isInZone(ZoneType.Battlefield) && !c.isPhasedOut()) {
                return false;
            }
        }
        return true;
    }

    /** Whether this is an Optional$ hidden-origin ChangeZone/ChangeZoneAll gate
     * with nothing that could move, which gorge never poses: "you may put onto
     * the battlefield any number of Aura cards ... from your hand and/or
     * graveyard" (Bruna, Light of Alabaster) with none there, or Dawnbreak
     * Reclaimer's "you may return those cards" when both graveyards held no
     * creature card, is a choice of zero cards, so gorge logs no ask, while
     * Forge still asks. Answering no changes nothing on the board. It holds
     * only when no yes/no decision is queued for the seat, the origin is not
     * the battlefield or stack, ChangeNum$ does not evaluate to 0, and no card
     * in the Origin$ zones of any player passes the filter the effect itself
     * uses (a superset of the moved zones, so a candidate anywhere keeps the
     * strict miss). A "Cancel Search" prompt is a different confirm and never
     * reaches here. */
    private boolean zeroCandidateHiddenConfirm(SpellAbility sa) {
        if (sa == null || (sa.getApi() != ApiType.ChangeZone && sa.getApi() != ApiType.ChangeZoneAll)
                || !sa.hasParam("Optional")
                || !sa.hasParam("Origin") || peek(DecisionQueue::isYesNo) != null) {
            return false;
        }
        Card host = sa.getHostCard();
        if (host == null) {
            return false;
        }
        List<ZoneType> origins = ZoneType.listValueOf(sa.getParam("Origin"));
        if (origins.isEmpty() || origins.stream().anyMatch(z -> z == ZoneType.Battlefield || z == ZoneType.Stack)) {
            return false;
        }
        if (sa.hasParam("ChangeNum")
                && AbilityUtils.calculateAmount(host, sa.getParam("ChangeNum"), sa) <= 0) {
            return true;
        }
        if (sa.hasParam("Defined") || sa.hasParam("ChooseFromDefined")) {
            // The effect moves its Defined$ cards, not a ChangeType$ selection;
            // we cannot prove none can move, so keep the ask visible.
            return false;
        }
        // Mirror ChangeZoneEffect's own candidate filter, not Card.isValid on
        // the raw ChangeType$: it splits "EACH Creature & Land" per part and
        // resolves "Triggered.sameName" through the triggering card. A raw
        // filter reads both as card types no card has and would falsely
        // conclude nothing can move.
        String changeType = sa.getParamOrDefault("ChangeType", "");
        for (Player pl : host.getGame().getPlayers()) {
            for (ZoneType z : origins) {
                CardCollectionView inZone = pl.getCardsIn(z);
                if (changeType.isEmpty()) {
                    if (!inZone.isEmpty()) {
                        return false;
                    }
                } else if (changeType.startsWith("EACH")) {
                    for (String eachType : changeType.substring(5).split(" & ")) {
                        if (!AbilityUtils.filterListByType(inZone, eachType, sa).isEmpty()) {
                            return false;
                        }
                    }
                } else if (!AbilityUtils.filterListByType(inZone, changeType, sa).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Forge's Optional$ sacrifice is a yes/no first and then a pick of the
     * permanents (SacrificeEffect: confirmAction(sa, null, "Do you want to
     * sacrifice?") followed by choosePermanentsToSacrifice), while gorge logs
     * the whole "sacrifice any number" as ONE 0..N object pick (resume
     * "sacrifice"). A non-empty pick is the yes and must stay queued for
     * choosePermanentsToSacrifice; an empty pick is the recorded "none" and is
     * consumed here so it cannot be a leftover; no pick at all means nothing
     * was offered, so the only answer is no. A SacValid$ Self sacrifice is the
     * exception: Forge sacrifices the host on the yes and never asks again, so
     * the pick is consumed here. */
    private boolean sacrificeConfirm(SpellAbility sa) {
        // An Optional$ + StrictAmount$ sacrifice ("sacrifice two Foods", Giant
        // Opportunity) is not a 0..N pick: gorge logs the yes/no itself as a
        // mode decision (resume "sacrifice_optional": "Sacrifice N
        // permanent(s)" / "Don't sacrifice", an empty answer declines) before
        // any exact pick. That decision IS the confirm: consume it and answer
        // from it; a yes leaves the exact pick queued for
        // choosePermanentsToSacrifice, a no has none.
        Decision mode = take(x -> x.resume.equals("sacrifice_optional"));
        if (mode != null) {
            boolean yes = !mode.picks.isEmpty()
                    && !mode.picks.get(0).trim().toLowerCase(java.util.Locale.ROOT).startsWith("don't");
            m.note("sacrifice-confirm step " + m.step() + ": " + (yes ? "yes" : "no") + " (strict " + mode.picks + ")");
            return yes;
        }
        Predicate<Decision> isSacrifice = x -> x.resume.equals("sacrifice");
        boolean self = "Self".equals(sa.getParamOrDefault("SacValid", "Self"));
        if (self) {
            Decision d = take(isSacrifice);
            boolean yes = d != null && !d.picks.isEmpty();
            m.note("sacrifice-confirm step " + m.step() + ": " + (yes ? "yes" : "no")
                    + (d == null ? " (no pick)" : " " + d.picks));
            return yes;
        }
        Decision d = peek(isSacrifice);
        if (d == null || d.picks.isEmpty()) {
            if (d != null) {
                take(isSacrifice);
            }
            m.note("sacrifice-confirm step " + m.step() + ": no" + (d == null ? " (no pick)" : " (empty pick)"));
            return false;
        }
        m.note("sacrifice-confirm step " + m.step() + ": yes " + d.picks);
        return true;
    }

    // An Optional$ Attach asks one "Do you want to attach X to Y?" per attachment
    // (AttachEffect), while gorge logs ONE 0..N card pick naming the attachments
    // it attached (resume attach_subset / attach_choice); an empty pick is the
    // recorded "none". The pick is consumed at the first confirm and its refs
    // answer every confirm of that effect (same step, same host): yes for a
    // named attachment, no for the rest.
    private int attachStep = -99;
    private Card attachHost;
    private List<Card> attachYes = new ArrayList<>();
    /** The attachment a consumed entity pick chose (Choices$ form: Forge asks
     * the choice of the attachment first, and a lone one is taken by the
     * lone-forced shortcut, which consumes gorge's pick); its confirm is the
     * yes that pick already gave. */
    private Card attachChosen;
    private int attachChosenStep = -99;

    /** Answers one Attach confirm from gorge's attachment pick; null when
     * gorge logged none for it (the caller then misses as before). */
    private Boolean attachConfirm(SpellAbility sa, Card attachment) {
        if (attachChosen != null && attachChosenStep == m.step() && attachChosen.equals(attachment)) {
            attachChosen = null;
            m.note("attach-confirm step " + m.step() + ": yes " + attachment + " (chosen by the entity pick)");
            return true;
        }
        // Gorge's decisions bind in log order, so this confirm answers from the
        // FIRST attach decision of the step (an earlier effect's attach_subset /
        // attach_choice must not be skipped for a later effect's yes/no). A
        // per-attachment yes/no (attach_optional: Bruna, Light of Alabaster's
        // RepeatEach over the Auras that can enchant it, CR 303.4) carries no
        // card ref; as the head decision it is the answer to this very confirm,
        // unless a pick already consumed for this host and step is still
        // answering this effect's confirms.
        Decision head = peek(x -> x.kind.equals("choose_n") && (x.resume.startsWith("attach") || attachNames(x, attachment)));
        boolean headOptional = head != null && head.resume.equals("attach_optional") && DecisionQueue.isYesNo(head);
        boolean pickLive = attachStep == m.step() && attachHost == sa.getHostCard();
        if (headOptional && !pickLive) {
            take(x -> x == head);
            boolean yes = DecisionQueue.yes(head);
            m.note("attach-confirm step " + m.step() + ": " + (yes ? "yes " : "no ") + attachment + " (attach_optional)");
            return yes;
        }
        Decision d = headOptional ? null : take(x -> x.kind.equals("choose_n") && (x.resume.startsWith("attach") || attachNames(x, attachment)));
        if (d != null) {
            attachStep = m.step();
            attachHost = sa.getHostCard();
            attachYes = new ArrayList<>();
            for (String r : d.refs()) {
                if (!r.isBlank()) {
                    attachYes.add(m.card(r));
                }
            }
            m.note("attach pick step " + m.step() + ": " + d.refs());
        } else if (attachStep != m.step() || attachHost != sa.getHostCard()) {
            return null;
        }
        boolean yes = attachYes.remove(attachment);
        m.note("attach-confirm step " + m.step() + ": " + (yes ? "yes " : "no ") + attachment);
        return yes;
    }

    private boolean attachNames(Decision d, Card attachment) {
        if (!DecisionQueue.isObjectChoice(d)) {
            return false;
        }
        for (String r : d.refs()) {
            try {
                if (attachment.equals(m.entity(r))) {
                    return true;
                }
            } catch (HarnessError notFound) {
                // not a ref of this game
            }
        }
        return false;
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) {
        Boolean b = m.yesNo(seat, "bid " + string);
        return b != null ? b : super.confirmBidAction(sa, bidlife, string, bid, winner);
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        Boolean clone = m.cloneReplacement(seat);
        if (clone != null) {
            return clone;
        }
        // Dredge is an Optional$ replacement in Forge and a mode ask over
        // every dredger plus the ordinary draw in gorge (resume "dredge"). A
        // resume-specific peek, like cloneReplacement above, so the generic
        // yes/no path never mistakes it for one.
        Boolean dredge = m.dredgeReplacement(seat, replacementEffect.getHostCard(), replacementEffect.getOtherChoices());
        if (dredge != null) {
            return dredge;
        }
        // Unleash is an Optional$ ETB replacement in Forge and a choose_n over
        // "Enter with a +1/+1 counter" / "Enter without a counter" in gorge.
        Boolean unleash = m.unleashReplacement(seat, replacementEffect.getHostCard());
        if (unleash != null) {
            return unleash;
        }
        if (m.step() < 0 && !m.hasSetupYesNo(seat)) {
            // Setup placement: gorge's xmageFixture places a clone as itself
            // unless a setup answer (step -1) says otherwise.
            m.note("setup replacement declined (gorge logged no setup answer): " + question);
            return false;
        }
        Boolean b = m.yesNo(seat, "replacement " + question);
        return b != null ? b : super.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        Boolean b = m.yesNo(seat, "static " + message);
        return b != null ? b : super.confirmStaticApplication(hostCard, mode, message, logic);
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        Boolean b = m.yesNo(seat, "optional trigger " + sa, StepMachine.costFoldedTrigger(sa.getWrappedAbility()));
        return b != null ? b : super.confirmTrigger(sa);
    }

    @Override
    public CardCollectionView cheatShuffle(CardCollectionView list) {
        return m.forcedShuffle(seat, list);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        // The recorded flip's outcome is forced as the call being right or
        // wrong, so the call is heads: the generator's forced result is the
        // outcome itself (Request.coinFlips).
        if (kindOfChoice == BinaryChoiceType.HeadsOrTails && !m.req.coinFlips(m.step()).isEmpty()) {
            return true;
        }
        Decision d = binaryPick(kindOfChoice);
        if (d != null) {
            Boolean answer = binaryAnswer(kindOfChoice, d);
            if (answer != null) {
                return answer;
            }
        }
        m.miss("binary", question);
        return super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
    }

    /** The one gorge decision that answers a BinaryChoiceType, or null when
     * the kind has no mapping yet. A new kind is one case here plus its answer
     * in {@link #binaryAnswer}, and an unmapped kind still misses loudly,
     * never a silent AI answer. */
    private Decision binaryPick(BinaryChoiceType kind) {
        switch (kind) {
        case TapOrUntap:
            // By role (resume taporuntap / taporuntapall) so an empty answer,
            // which has no pick kinds, is found too; the pick kinds remain a
            // fallback for a decision logged without a resume.
            return take(x -> x.kind.equals("choose_n")
                    && (x.resume.equals("taporuntap") || x.resume.equals("taporuntapall")
                            || x.pickKinds.contains("tap") || x.pickKinds.contains("untap")));
        case LeftOrRight:
            // ChooseDirectionEffect: true means Left. gorge's pick kind is
            // "direction", the label "left" or "right".
            return take(x -> x.kind.equals("choose_n") && x.picks.size() == 1
                    && (x.pickKinds.contains("direction") || x.resume.equals("choosedirection")));
        case OddsOrEvens:
            // Extinction Event's resolution ask is choose_n[odd] "odd";
            // Gollum, Riddle Master's as-enters ask is choose_n[evenodd]
            // "Odd" (resume "etb"). Match the pick-kind role, not a resume
            // string, and fall back to the label only when gorge logged no
            // kinds, so another even/odd ask is still covered.
            return take(x -> {
                if (!x.kind.equals("choose_n") || x.picks.size() != 1) {
                    return false;
                }
                if (x.pickKinds.contains("odd") || x.pickKinds.contains("even")
                        || x.pickKinds.contains("evenodd")) {
                    return true;
                }
                if (!x.pickKinds.isEmpty()) {
                    return false;
                }
                String pick = x.picks.get(0).trim();
                return pick.equalsIgnoreCase("odd") || pick.equalsIgnoreCase("even");
            });
        default:
            return null;
        }
    }

    /** The boolean a mapped decision answers for kind, with the note naming
     * the answer; null when the decision cannot answer that kind. The
     * tap/untap default belongs to TapOrUntap only. */
    private Boolean binaryAnswer(BinaryChoiceType kind, Decision d) {
        switch (kind) {
        case TapOrUntap: {
            // An empty answer reads as "tap" (gorge's taporuntap/taporuntapall
            // contract: the first, state-changing option is the default).
            boolean tap = d.picks.isEmpty() || d.pickKinds.contains("tap");
            m.note("binary step " + m.step() + " (" + kind + "): " + (tap ? "tap" : "untap") + " " + d.picks);
            return tap;
        }
        case LeftOrRight: {
            boolean left = d.picks.get(0).trim().equalsIgnoreCase("left");
            m.note("binary step " + m.step() + " (" + kind + "): " + (left ? "left" : "right") + " " + d.picks);
            return left;
        }
        case OddsOrEvens: {
            // true means Odd, whatever the label's case (gorge logs "odd" in
            // a resolution and "Odd" at an as-enters choice).
            boolean odd = d.picks.get(0).trim().equalsIgnoreCase("odd");
            m.note("binary step " + m.step() + " (" + kind + "): " + (odd ? "odd" : "even") + " " + d.picks);
            return odd;
        }
        default:
            return null;
        }
    }

    /** The params overload is the one Forge's AddOrRemove asks
     * (CountersPutOrRemoveEffect, TimeTravelEffect) take. Left unoverridden,
     * PlayerControllerAi answers it through the AI, a silent decision. It
     * follows the plain overload exactly: a recorded coin flip or a mapped
     * decision answers, everything else is a strict miss, never a silent AI
     * answer. */
    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Map<String, Object> params) {
        return chooseBinary(sa, question, kindOfChoice, (Boolean) null);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        Boolean b = m.yesNoOptional(seat);
        if (b != null) {
            return b;
        }
        if (payingUnlessCost) {
            // The unless_pay answer already chose to pay; confirm the part.
            m.note("unless-pay confirm step " + m.step() + ": yes (" + string + ")");
            return true;
        }
        m.miss("pay", string);
        return super.confirmPayment(costPart, string, sa);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        // gorge poses "unless pays" as a mode ask (resume unless_pay): "Pay 2",
        // "Pay the cost", or "Don't pay". A pay answer pays through the
        // engine's own resolve-time payment (from this seat's pool).
        Decision d = take(x -> x.resume.equals("unless_pay") && x.picks.size() == 1);
        if (d != null) {
            String pick = d.picks.get(0).trim().toLowerCase(java.util.Locale.ROOT);
            // "Pay 2", "Pay the cost", "Sacrifice nonland permanent": every
            // answer but the decline pays. An unaffordable cost is not started
            // (a partial payment would spend life before the mana fails).
            boolean pay = !(pick.startsWith("don't") || pick.startsWith("do not") || pick.startsWith("dont"));
            boolean paid = false;
            if (pay && forge.ai.ComputerUtilCost.canPayCost(cost, sa, player, true)) {
                payingUnlessCost = true;
                try {
                    paid = PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, null);
                } finally {
                    payingUnlessCost = false;
                }
            }
            m.note("unless-pay step " + m.step() + ": " + d.picks.get(0) + (pay ? " paid=" + paid : ""));
            return paid;
        }
        // Echo and cumulative upkeep reach this same entry point: Forge builds
        // both keywords as an upkeep trigger whose DB$ Sacrifice asks
        // payCostToPreventEffect for Echo$/CumulativeUpkeep$ (CardFactoryUtil,
        // SacrificeEffect). The pick kind is the answer: *_sac declines and
        // lets Forge's own sacrifice happen; *_pay* pays through the engine's
        // resolve-time payment, whose cost choose then consumes the following
        // action pick (echo_action_pick / cumulative_action_sac / discard)
        // from ScriptedCostDecision and the sacrifice/discard overrides.
        Decision upkeep = take(ScriptedController::isEchoCumulativeAnswer);
        if (upkeep != null) {
            boolean pay = !upkeep.pickKinds.get(0).endsWith("_sac");
            boolean paid = false;
            if (pay && forge.ai.ComputerUtilCost.canPayCost(cost, sa, player, true)) {
                payingUnlessCost = true;
                try {
                    paid = PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, null);
                } finally {
                    payingUnlessCost = false;
                }
            }
            m.note("echo/cumulative upkeep step " + m.step() + ": " + upkeep.picks.get(0)
                    + (pay ? " paid=" + paid : ""));
            return paid;
        }
        // Riot (CR 702.136a) is the controller's choice of a +1/+1 counter or
        // haste. Forge builds it as an ETB replacement whose DB$ Animate grants
        // haste UnlessCost$ AddCounter<1/P1P1> | UnlessPayer$ You: paying the
        // unless cost is the counter choice, declining it is haste. gorge asks
        // one choose_n over two "riot" options ("Enter with a +1/+1 counter",
        // "Gain haste"), so the pick, matched by kind, answers this payment.
        if (isRiotChoice(sa)) {
            Decision riot = take(x -> x.pickKinds.contains("riot"));
            if (riot != null) {
                boolean pay = !riot.picks.isEmpty() && riot.picks.get(0).equalsIgnoreCase(RIOT_COUNTER);
                boolean paid = false;
                if (pay) {
                    payingUnlessCost = true;
                    try {
                        paid = PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, null);
                    } finally {
                        payingUnlessCost = false;
                    }
                }
                m.note("riot step " + m.step() + ": " + (riot.picks.isEmpty() ? "(no pick)" : riot.picks.get(0))
                        + (pay ? " paid=" + paid : ""));
                return paid;
            }
        }
        if (m.step() < 0) {
            // Setup placement (before the scenario's first step) restates the
            // board; a shock land's "you may pay 2 life" entry replacement is
            // answered by paying, as gorge's setup drive answers an unscripted
            // unless-pay with its first option. Nothing is spent: the
            // scenario's tapped and life fields state the board.
            m.note("unless-pay at setup: paid " + cost.toSimpleString());
            return true;
        }
        if (alreadyPaid) {
            // AbilityUtils.handleUnlessCost asks EVERY payer even after one
            // has paid, though its own comment says a later payment "won't
            // change anything"; gorge asks each payer in turn only until one
            // pays, so it logs no answer for the later seats. Decline this
            // payer without a miss. A seat gorge did log an answer for was
            // handled above.
            m.note("unless-pay step " + m.step() + ": p" + seat + " declines (an earlier payer paid)");
            return false;
        }
        m.miss("unless-pay", cost.toSimpleString() + " for " + sa);
        return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        m.miss("pay during roll", String.valueOf(sa));
        return super.payCostDuringRoll(cost, sa);
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        m.miss("combat cost", prompt);
        return super.payCombatCost(card, cost, sa, prompt);
    }

    // ---- mana and costs ----------------------------------------------------

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        return PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect);
    }

    /** Pays from the floating pool first: the engine's own exact-colour pass,
     * then the generic remainder in a fixed WUBRGC order (deterministic, P0 S1).
     * When the sidecar named this player as the decider of a play/cast in the
     * step (StepMachine.isPlayPayer), a shortfall is finished from that
     * player's own sources: gorge poses no per-source mana ask for a
     * controlled player's play, so the driver has to tap them itself. */
    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        ManaPool pool = player.getManaPool();
        int before = pool.totalMana();
        payPhyrexianWithLife(toPay, ability);
        // the spend gorge recorded for this step, in its order, before Forge's
        // own pass picks which units pay a hybrid or generic pip
        for (char c : m.takeRecordedPayment().toCharArray()) {
            byte color = switch (c) {
                case 'W' -> ManaAtom.WHITE;
                case 'U' -> ManaAtom.BLUE;
                case 'B' -> ManaAtom.BLACK;
                case 'R' -> ManaAtom.RED;
                case 'G' -> ManaAtom.GREEN;
                case 'C' -> ManaAtom.COLORLESS;
                default -> 0;
            };
            if (color != 0 && !toPay.isPaid()) {
                pool.tryPayCostWithColor(color, ability, toPay, ability.getPayingMana());
            }
        }
        payFromPool(toPay, ability);
        if (m.isPlayPayer(player) && !payingOwnSources) {
            if (!toPay.isPaid()) {
                payingOwnSources = true;
                try {
                    payFromOwnSources(toPay, ability);
                } finally {
                    payingOwnSources = false;
                }
            }
            // The decision authorised this payment whether the pool covered it
            // or the sources above finished it; consume it on both paths so a
            // pool-covered play cannot leave it behind as a leftover.
            m.consumePlayChoice(player);
        }
        m.note("pay mana: pool " + before + " -> " + pool.totalMana() + ", paid=" + toPay.isPaid() + " for " + ability.getHostCard().getName());
        return toPay.isPaid();
    }

    /** Pays each pip gorge paid with 2 life: its pay_life pick of this step, one
     * pick per pip. A Phyrexian pip (CR 107.4f, 118.3b) is paid with payPhyrexian;
     * a black pip is paid the same way while the player has K'rrik's
     * "PayLifeInsteadOf:B" (CR 601.2h, 118.9: "for each {B} in a cost, you may pay
     * 2 life rather than pay that mana"), which drops one black shard. Forge's AI
     * (ComputerUtilMana.payManaCost) and the GUI (InputPayManaOfCostPayment) both
     * pay that way, but the scripted controller paid only mana, so the pip stayed
     * unpaid and the cast was refused. A pip gorge recorded no pay_life pick for
     * stays a mana pip, so a mana payment stays a mana payment. The pick is not
     * taken when the player cannot pay the life, so a refusal is Forge's own. */
    private void payPhyrexianWithLife(ManaCostBeingPaid toPay, SpellAbility ability) {
        while (player.canPayLife(2, false, ability)) {
            boolean phyrexian = toPay.containsPhyrexianMana();
            boolean blackForLife = !phyrexian && player.hasKeyword("PayLifeInsteadOf:B")
                    && toPay.hasAnyKind(ManaAtom.BLACK);
            if (!phyrexian && !blackForLife) {
                return;
            }
            Decision d = take(x -> x.pickKinds.contains("pay_life"));
            if (d == null) {
                return;
            }
            if (phyrexian) {
                toPay.payPhyrexian();
                ability.setSpendPhyrexianMana(true);
            } else {
                toPay.decreaseShard(ManaCostShard.BLACK, 1);
            }
            player.payLife(2, ability, false);
            m.note("pay " + (phyrexian ? "phyrexian" : "black") + " with 2 life step " + m.step() + " for "
                    + ability.getHostCard().getName() + ": life " + player.getLife());
        }
    }

    /** The engine's own exact-colour pass, then the generic remainder in the
     * fixed WUBRGC order (deterministic, P0 S1). */
    private void payFromPool(ManaCostBeingPaid toPay, SpellAbility ability) {
        ManaPool pool = player.getManaPool();
        pool.payManaCostFromPool(toPay, ability, false, ability.getPayingMana());
        while (!toPay.isPaid() && !pool.isEmpty()) {
            boolean found = false;
            for (byte color : ManaAtom.MANATYPES) {
                if (pool.tryPayCostWithColor(color, ability, toPay, ability.getPayingMana())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
        }
    }

    /** Finish a play/cast cost the pool cannot cover from the payer's own
     * sources. The battlefield is walked in zone order and the first playable
     * mana ability whose predicted mana the remaining cost can use is
     * activated, one at a time, until the pool pays the cost -- no AI choice
     * and no map order reaches the result. */
    private boolean payFromOwnSources(ManaCostBeingPaid toPay, SpellAbility ability) {
        ManaPool pool = player.getManaPool();
        int guard = 0;
        while (!toPay.isPaid() && guard++ < 64) {
            payFromPool(toPay, ability);
            if (toPay.isPaid()) {
                return true;
            }
            SpellAbility use = null;
            for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
                for (SpellAbility ma : c.getManaAbilities()) {
                    ma.setActivatingPlayer(player);
                    if (!ma.canPlay()) {
                        continue;
                    }
                    String produced = forge.ai.ComputerUtilMana.predictMana(ma, player, ManaCostShard.GENERIC)
                            .replace(" ", "");
                    if (produced.isEmpty()) {
                        continue;
                    }
                    ManaCostBeingPaid probe = new ManaCostBeingPaid(toPay);
                    if (probe.ai_payMana(produced, pool)) {
                        use = ma;
                        break;
                    }
                }
                if (use != null) {
                    break;
                }
            }
            if (use == null) {
                return false;
            }
            boolean ok = PlaySpellAbility.playSpellAbility(this, player, use);
            m.note("play-choice source step " + m.step() + ": " + use.getHostCard().getName()
                    + " activated=" + ok + " pool=" + pool.totalMana());
            if (!ok) {
                return false;
            }
        }
        return toPay.isPaid();
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) {
        return new ScriptedCostDecision(m, seat, player, ability, effect);
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return costs;
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility choosen, List<OptionalCostValue> optionalCostValues) {
        List<OptionalCostValue> picked = m.chooseOptionalCosts(seat, optionalCostValues);
        if (picked != null) {
            return picked;
        }
        m.miss("optional cost", String.valueOf(optionalCostValues));
        return super.chooseOptionalCosts(choosen, optionalCostValues);
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        // Forge passes isOptional for an unless cost ("you may pay"), but once
        // gorge's unless_pay answer elected the payment (payingUnlessCost) the
        // whole cost is paid (CR 118.12, 601.2h): all `amount` cards are
        // chosen, so an option list of exactly that size is a forced pick
        // (Ill-Timed Explosion's Discard<2/Card> from a 2-card hand). A pick
        // gorge did log is still consumed first by pickObjects.
        int min = isOptional && !payingUnlessCost ? 0 : amount;
        List<Card> picked = m.pickObjects(seat, optionList, min, amount, "cost card");
        return picked != null ? new CardCollection(picked) : super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
    }

    /** A gorge pick kind answered by Forge's chooseCardsForConvokeOrImprovise. */
    static boolean isConvokeLikeKind(String kind) {
        return kind.startsWith("convoke") || kind.startsWith("improvise") || kind.startsWith("waterbend");
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        // gorge's convoke/improvise/waterbend picks (pick kinds convoke_generic,
        // convoke_<C>, improvise_generic, waterbend_generic): each names a
        // creature/artifact and the pip it pays. With none logged, gorge paid
        // from the pool only. A waterbend tap (CR 701.67a) is the same ask:
        // Forge routes Waterbend{N}/{X} through this method too, so a
        // waterbend_generic pick left unconsumed here leaves Forge to pay the
        // cost from an empty pool and refuse the activation.
        Map<Card, ManaCostShard> out = new java.util.LinkedHashMap<>();
        while (true) {
            Decision d = take(x -> !x.pickKinds.isEmpty() && isConvokeLikeKind(x.pickKinds.get(0)));
            if (d == null) {
                break;
            }
            List<String> refs = d.refs();
            for (int i = 0; i < refs.size(); i++) {
                Card c = m.card(refs.get(i));
                String k = i < d.pickKinds.size() ? d.pickKinds.get(i) : d.pickKinds.get(0);
                String col = k.contains("_") ? k.substring(k.indexOf('_') + 1) : "generic";
                ManaCostShard sh = col.equals("generic") ? ManaCostShard.GENERIC : ManaCostShard.parseNonGeneric(col);
                if (!untappedCards.contains(c)) {
                    m.miss("convoke", refs.get(i) + " is not offered");
                    return new java.util.HashMap<>();
                }
                out.put(c, sh);
            }
        }
        if (!out.isEmpty()) {
            m.note("convoke step " + m.step() + ": " + out.size() + " tapped");
        }
        return out;
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        // CR 702.66a: gorge answers its Delve ask with one choose whose every
        // pick kind is "exile" and whose refs name graveyard cards. Forge asks
        // this separately for the generic mana it cannot pay. Each pick must
        // consume one distinct card: several copies share a name, and Forge's
        // own grave order is not gorge's, so the ordinal cannot be trusted to
        // pick the exact copy -- but any copy of that name is the same exile.
        // No ask means gorge paid from the pool only, as for convoke.
        Decision d = take(DecisionQueue::isDelvePick);
        if (d == null) {
            return new CardCollection();
        }
        List<String> refs = d.refs();
        boolean byRef = !refs.isEmpty();
        List<String> picks = byRef ? refs : d.picks;
        int n = Math.min(genericAmount, picks.size());
        CardCollection remaining = new CardCollection(grave);
        CardCollection out = new CardCollection();
        for (int i = 0; i < n; i++) {
            String pick = picks.get(i);
            RefTable.Ref r = byRef ? RefTable.parse(pick) : null;
            String name = r != null ? r.name : pick;
            Card hit = null;
            for (Card c : remaining) {
                int owner = SnapshotWriter.seatOf(m.seats, c.getOwner());
                if (SnapshotWriter.zoneName(c).equals(name) && (r == null || r.seat == owner)) {
                    hit = c;
                    break;
                }
            }
            if (hit == null) {
                m.miss("delve", pick + " is not in the graveyard");
                return new CardCollection();
            }
            remaining.remove(hit);
            out.add(hit);
        }
        if (!out.isEmpty()) {
            m.note("delve step " + m.step() + ": " + out.size() + " exiled");
        }
        return out;
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return false;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return null;
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        if (manaChoices.size() == 1) {
            return manaChoices.get(0);
        }
        return manaChoices.get(0); // pool mana is indistinguishable within a colour
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        // gorge's per-pip mana picks ("Forage: Add W", "Forage: Add U").
        Decision d = take(x -> x.pickKinds.size() == manaAmount && x.pickKinds.stream().allMatch("mana"::equals));
        if (d != null) {
            Map<Byte, Integer> out = new java.util.HashMap<>();
            for (String p : d.picks) {
                byte b = StepMachine.manaColor(p);
                if (b == 0 || !colorSet.hasAnyColor(b)) {
                    out = null;
                    break;
                }
                out.merge(b, 1, Integer::sum);
            }
            if (out != null) {
                m.note("mana combo step " + m.step() + ": " + d.picks);
                return out;
            }
        }
        m.miss("mana combo", String.valueOf(sa));
        return super.specifyManaCombo(sa, colorSet, manaAmount, different);
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        if (min == max) {
            return min;
        }
        m.miss("cost reduction number", String.valueOf(sa));
        return super.chooseNumberForCostReduction(sa, min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        // An optional keyword cost (offspring, multikicker, ...): gorge logs a
        // decision only when it pays one, so with none logged it paid none.
        Integer n = m.chooseNumber(seat, 0, max);
        if (n == null) {
            // No decision: the cast step's cast_mode may elect the keyword.
            n = m.modeKeywordCost(keyword, max);
            m.note("keyword cost " + keyword + ": " + n + (n == 0 ? " (gorge logged none)" : " (cast_mode " + m.castMode + ")"));
            return n;
        }
        m.note("keyword cost " + keyword + ": " + n);
        return n;
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return new ArrayList<>();
    }

    /** Per-source amounts gorge chose for a MoveCounter ValidSource ask, read
     * back by the per-source chooseNumber that follows. */
    private final Map<Card, Integer> moveCounterAmounts = new java.util.HashMap<>();

    /**
     * MoveCounter with ValidSource$ and CounterNum$ Any (Aetherborn Marauder).
     * Forge asks which sources to take from, then an amount per source. gorge
     * logs the same election as a loop of move_counter_origin picks (only
     * while more than one origin still holds counters; a lone remaining one is
     * no choice) each followed by a move_counter amount. An empty origin pick
     * or a 0 amount ends the election. The sources become the answer to the
     * card ask, and the amounts wait for chooseNumber. Returns null when gorge
     * logged none of this (the caller then answers as before).
     */
    private CardCollectionView moveCounterSources(CardCollectionView sourceList, CounterType ct) {
        Map<Card, Integer> pending = new java.util.LinkedHashMap<>();
        boolean any = false;
        while (true) {
            List<Card> remaining = new ArrayList<>();
            for (Card c : sourceList) {
                if (c.getCounters(ct) - pending.getOrDefault(c, 0) > 0) {
                    remaining.add(c);
                }
            }
            if (remaining.isEmpty()) {
                break;
            }
            Card chosen = remaining.get(0);
            if (remaining.size() > 1) {
                Decision d = take(x -> x.resume.equals("move_counter_origin"));
                if (d == null) {
                    break;
                }
                any = true;
                if (d.refs().isEmpty()) {
                    m.note("move counter step " + m.step() + ": origin election declined");
                    break;
                }
                List<Card> one = m.pickFrom(d, remaining, 1, 1, "card (move counters from)");
                if (one == null) {
                    break;
                }
                chosen = one.get(0);
            }
            Decision a = take(x -> x.resume.equals("move_counter") && x.picks.size() == 1);
            if (a == null) {
                break;
            }
            any = true;
            int n;
            try {
                n = Integer.parseInt(a.picks.get(0).trim());
            } catch (NumberFormatException e) {
                m.miss("number", "move_counter amount " + a.picks.get(0));
                break;
            }
            if (n <= 0) {
                m.note("move counter step " + m.step() + ": 0 from " + chosen + " ends the election");
                break;
            }
            pending.merge(chosen, n, Integer::sum);
        }
        if (!any) {
            return null;
        }
        moveCounterAmounts.clear();
        moveCounterAmounts.putAll(pending);
        m.note("move counter sources: " + pending);
        return new CardCollection(pending.keySet());
    }

    // ---- object picks -------------------------------------------------------

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        if (m.loneTwoPilesSplit(seat, sa, sourceList.size(), isOptional ? 0 : min, max)) {
            // CR 700.3: one card splits one way (the card and an empty pile), so
            // gorge poses no split and logs none. The card is pile 1 (gorge's
            // pile A), which chooseCardsPile's twopiles_pick then resolves.
            m.note("two piles step " + m.stepIdx + ": one card, no split pick; the card is pile 1");
            return new CardCollection(sourceList);
        }
        if (sa != null && sa.getApi() == ApiType.MoveCounter && sa.hasParam("ValidSource") && params != null
                && params.get("CounterType") instanceof CounterType ct) {
            CardCollectionView sources = moveCounterSources(sourceList, ct);
            if (sources != null) {
                return sources;
            }
        }
        List<Card> picked = m.pickObjects(seat, sourceList, isOptional ? 0 : min, max, "card (" + title + ")");
        return picked != null ? new CardCollection(picked) : super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        // DigMultiple (In the Presence of Ages): at most one card per ChangeValid$
        // category. gorge logs one choose_n[digmultiple] over the dug cards;
        // its picks are the chosen cards, and an empty pick is the Optional$
        // decline.
        Decision d = take(x -> x.resume.equals("digmultiple"));
        if (d != null) {
            List<Card> union = new ArrayList<>();
            for (CardCollection cc : validMap.values()) {
                for (Card c : cc) {
                    if (!union.contains(c)) {
                        union.add(c);
                    }
                }
            }
            List<Card> picked = m.pickFrom(d, union, isOptional ? 0 : 1, validMap.size(), "cards (multiple) (" + title + ")");
            if (picked != null && assignsOnePerCategory(validMap, picked)) {
                return new CardCollection(picked);
            }
            if (picked != null) {
                m.miss("cards (multiple)", "gorge's picks " + d.picks + " take more than one card of a category");
            }
        }
        m.miss("cards (multiple)", title);
        return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
    }

    /** Whether each picked card can stand for a distinct validMap category
     * (DigMultiple chooses at most one card per ChangeValid$ entry). */
    static boolean assignsOnePerCategory(Map<String, CardCollection> validMap, List<Card> picked) {
        List<CardCollection> cats = new ArrayList<>(validMap.values());
        return assign(cats, picked, 0, new boolean[cats.size()]);
    }

    private static boolean assign(List<CardCollection> cats, List<Card> picked, int i, boolean[] used) {
        if (i == picked.size()) {
            return true;
        }
        for (int k = 0; k < cats.size(); k++) {
            if (!used[k] && cats.get(k).contains(picked.get(i))) {
                used[k] = true;
                if (assign(cats, picked, i + 1, used)) {
                    return true;
                }
                used[k] = false;
            }
        }
        return false;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        if (optionList.isEmpty()) {
            return null;
        }
        if (!isOptional && optionList.size() == 1) {
            // A lone forced entity is not a decision: TwoPiles' Separator$/
            // Chooser$ and ChangeZoneEffect's Chooser$ ask even when the
            // defined player list has one member, and gorge logs nothing for
            // it. Taking the next gorge pick here would consume the pile card
            // or the exiled card instead. A pick that names exactly this one
            // option is the exception: ChoosePlayerEffect's lone opponent and
            // ChooseCardEffect's lone ChooseEach$ candidate are logged by
            // gorge as one-option picks, and nothing else is the answer to
            // this ask, so it is consumed rather than left over.
            T only = optionList.get(0);
            if (m.takeLonePick(seat, only)) {
                rememberAttachChoice(sa, params, only);
                m.note("entity (" + title + "): lone option, gorge's pick consumed");
            } else {
                m.note("entity (" + title + "): lone forced option");
            }
            return only;
        }
        List<T> picked = m.pickObjects(seat, optionList, isOptional ? 0 : 1, 1, "entity (" + title + ")");
        if (picked != null) {
            if (!picked.isEmpty()) {
                rememberAttachChoice(sa, params, picked.get(0));
            }
            return picked.isEmpty() ? null : picked.get(0);
        }
        return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
    }

    /** An Optional$ Attach's Choices$ form asks which attachment first (params
     * carry "Target"; the later ask for the attached-to entity carries
     * "Attachments"). A gorge pick that chose it is the yes its confirm needs. */
    private void rememberAttachChoice(SpellAbility sa, Map<String, Object> params, GameEntity chosen) {
        if (sa != null && sa.getApi() == ApiType.Attach && sa.hasParam("Optional") && !sa.hasParam("Object")
                && params != null && params.containsKey("Target") && chosen instanceof Card c) {
            attachChosen = c;
            attachChosenStep = m.step();
        }
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        List<T> picked = m.pickObjects(seat, optionList, min, max, "entities (" + title + ")");
        return picked != null ? picked : super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params);
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        List<Card> picked = m.pickObjects(seat, validTargets, min, max, "sacrifice");
        return picked != null ? new CardCollection(picked) : super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        List<Card> picked = m.pickObjects(seat, validTargets, min, max, "destroy");
        return picked != null ? new CardCollection(picked) : super.choosePermanentsToDestroy(sa, min, max, validTargets, message);
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        if (min == 0 && max > 0) {
            // "You may discard a card" (DiscardEffect Mode$ TgtChoose with
            // Optional$ True) is one 0..N pick in Forge. gorge poses it as two
            // asks (effDiscardTgtChoose, resume discard_may): a yes/no election,
            // then the card pick, which it skips when the options are forced
            // (CR 701.9a, 608.2c). Read the election here: no discards nothing,
            // yes is the 1..N pick gorge goes on to make (a lone card is
            // forced). A DiscardEffect AnyNumber$ pick has no election, so
            // nothing is queued and its 0..N pick below is unchanged.
            Decision election = take(x -> x.resume.equals("discard_may") && DecisionQueue.isYesNo(x));
            if (election != null) {
                boolean yes = DecisionQueue.yes(election);
                m.note("discard election step " + m.step() + ": " + (yes ? "yes" : "no"));
                if (!yes) {
                    return new CardCollection();
                }
                List<Card> picked = m.pickObjects(seat, validCards, 1, max, "discard");
                return picked != null ? new CardCollection(picked)
                        : super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, 1, max, visibleToChooser);
            }
        }
        List<Card> picked = m.pickObjects(seat, validCards, min, max, "discard");
        return picked != null ? new CardCollection(picked) : super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) {
        // The election keeps one unless-type card (Forge's own validity test,
        // the one PlayerControllerHuman's "cards unless type" selection uses)
        // or discards min cards. The lower bound of 1 stands for that one typed
        // card, so it only applies while one is in hand: with none the choice
        // is exactly min cards, and a hand of min or fewer is forced.
        Player p = getPlayer();
        boolean unlessInHand = false;
        for (Card c : hand) {
            if (c.isValid(unlessTypes, p, sa.getHostCard(), sa)) {
                unlessInHand = true;
                break;
            }
        }
        // gorge logs the discard as one pick (one typed card, or min cards).
        List<Card> picked = unlessInHand
                ? m.pickObjects(seat, hand, 1, Math.max(min, 1), "discard unless type")
                : m.pickObjects(seat, hand, min, min, "discard unless type");
        if (picked != null && (!picked.isEmpty() || min == 0)) {
            return new CardCollection(picked);
        }
        m.miss("discard unless type", String.valueOf(sa));
        return super.chooseCardsToDiscardUnlessType(min, hand, unlessTypes, sa);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        List<Card> picked = m.pickObjects(seat, player.getCardsIn(ZoneType.Hand), numDiscard, numDiscard, "cleanup discard");
        return picked != null ? new CardCollection(picked) : super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        List<Card> picked = m.pickObjects(seat, valid, min, max, "reveal from hand");
        return picked != null ? new CardCollection(picked) : super.chooseCardsToRevealFromHand(min, max, valid);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) {
        if (spells.size() <= num) {
            // Forge asks nothing when it offers no more choices than it needs,
            // but gorge still logs the forced GenericChoice. Consume it when
            // its picks name every offered spell, so it is not a leftover.
            List<SpellAbility> forced = m.takeForcedGenericModes(seat, spells, num);
            return forced != null ? forced : spells;
        }
        List<SpellAbility> picked = m.chooseGenericModes(seat, spells, num);
        if (picked != null) {
            return picked;
        }
        m.miss("spell abilities", title);
        return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        if (spells.size() == 1) {
            // The one-spell form of the early return above: a lone Forge
            // choice still corresponds to gorge's recorded (forced) mode.
            List<SpellAbility> forced = m.takeForcedGenericModes(seat, spells, 1);
            return forced != null ? forced.get(0) : spells.get(0);
        }
        List<SpellAbility> picked = m.chooseGenericModes(seat, spells, 1);
        if (picked != null) {
            return picked.get(0);
        }
        m.miss("spell", title);
        return super.chooseSingleSpellForEffect(spells, sa, title, params);
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        if (fetchList.isEmpty()) {
            return null;
        }
        List<Card> picked = m.pickObjects(seat, fetchList, isOptional ? 0 : 1, 1, "zone change");
        if (picked != null) {
            return picked.isEmpty() ? null : picked.get(0);
        }
        return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        List<Card> picked = m.pickObjects(seat, fetchList, min, max, "zone change");
        return picked != null ? picked : super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
    }

    @Override
    public Card chooseCardToKeepStickers(CardCollectionView options) {
        List<Card> picked = m.pickObjects(seat, options, 1, 1, "keep stickers");
        return picked != null && !picked.isEmpty() ? picked.get(0) : super.chooseCardToKeepStickers(options);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        m.miss("contraptions", "");
        return super.chooseContraptionsToCrank(contraptions);
    }

    // ---- ordering -----------------------------------------------------------

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        if (cards.isEmpty()) {
            return cards;
        }
        // One card is no choice, but gorge logs a one-pick order for it (look at
        // the top card of a library, put it back: RearrangeTopOfLibrary), so the
        // lone-card ask goes through orderCards too, which consumes that pin when
        // its pick names the card and otherwise leaves the queue alone.
        if (cards.size() == 1) {
            m.orderCards(seat, cards, "order to " + destinationZone);
            return cards;
        }
        // A Dig's picked cards bound for the library bottom (LibraryPosition$ -1,
        // its default) are ordered by gorge's one "dig" pick, not an order decision.
        boolean digToBottom = destinationZone == ZoneType.Library && source != null
                && source.getApi() == ApiType.Dig
                && "-1".equals(source.getParamOrDefault("LibraryPosition", "-1"));
        List<Card> ordered = m.orderCards(seat, cards, "order to " + destinationZone, digToBottom);
        return ordered != null ? new CardCollection(ordered) : super.orderMoveToZoneList(cards, destinationZone, source);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        List<List<Card>> a = m.arrange(seat, topN);
        if (a != null) {
            return ImmutablePair.of(new CardCollection(a.get(0)), new CardCollection(a.get(1)));
        }
        m.miss("scry", "");
        return super.arrangeForScry(topN);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        List<List<Card>> a = m.arrange(seat, topN);
        if (a != null) {
            return ImmutablePair.of(new CardCollection(a.get(0)), new CardCollection(a.get(1)));
        }
        m.miss("surveil", "");
        return super.arrangeForSurveil(topN);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        // Clash (CR 701.30a): each clashing player may put the revealed card on
        // the bottom. gorge asks the revealer once, as a 1-of-2 pick
        // (resume clash_placement) with option kinds "top" ("Keep it on top")
        // and "bottom" ("Put it on the bottom"); Forge's true means top. By
        // role, so the answer is found whatever its label says.
        Decision d = take(x -> x.kind.equals("choose_n") && x.resume.equals("clash_placement") && x.picks.size() == 1);
        if (d != null) {
            boolean top = d.pickKinds.contains("top");
            m.note("clash step " + m.step() + ": " + (top ? "top" : "bottom") + " " + d.picks);
            return top;
        }
        m.miss("top or bottom", String.valueOf(c));
        return super.willPutCardOnTop(c);
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return blockers.size() <= 1 ? blockers : missThen("blocker order", () -> super.orderBlockers(attacker, blockers));
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return missThen("blocker order", () -> super.orderBlocker(attacker, blocker, oldBlockers));
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return attackers.size() <= 1 ? attackers : missThen("attacker order", () -> super.orderAttackers(blocker, attackers));
    }

    // ---- combat: the attack / block steps and pass_to decision stops ---------

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        m.declareAttackers(attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        m.declareBlockers(defender, combat);
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return new ArrayList<>();
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return new ArrayList<>();
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        if (blockers.size() <= 1) {
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        return missThen("combat damage assignment", () -> super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder));
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return missThen("shield division", () -> super.divideShield(effectSource, affected, shieldAmount));
    }

    // ---- named choices ------------------------------------------------------

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        String s = m.chooseLabel(seat, validTypes, "type");
        return s != null ? s : missThen("type", () -> super.chooseSomeType(kindOfType, sa, validTypes, isOptional));
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return missThen("sector", () -> super.chooseSector(assignee, ai, sectors));
    }

    @Override
    public Sticker chooseSticker(List<Sticker> options, Card target, SpellAbility sa, boolean isOptional) {
        return missThen("sticker", () -> super.chooseSticker(options, target, sa, isOptional));
    }

    @Override
    public int chooseStickerNamePosition(Sticker sticker, Card target) {
        return missThen("sticker position", () -> super.chooseStickerNamePosition(sticker, target));
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        return missThen("sprocket", () -> super.chooseSprocket(assignee, sprockets));
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return missThen("planar die", () -> super.choosePDRollToIgnore(rolls));
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return missThen("roll to ignore", () -> super.chooseRollToIgnore(rolls));
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return missThen("dice to reroll", () -> super.chooseDiceToReroll(rolls));
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return missThen("roll to modify", () -> super.chooseRollToModify(rolls));
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return missThen("roll to swap", () -> super.chooseRollToSwap(rolls));
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return missThen("roll swap value", () -> super.chooseRollSwapValue(swapChoices, currentResult, power, toughness));
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return missThen("vote", () -> super.vote(sa, prompt, options, votes, forPlayer, optional));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (min == max) {
            return min;
        }
        Integer n = m.chooseNumber(seat, min, max);
        return n != null ? n : missThen("number", () -> super.chooseNumber(sa, title, min, max));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max, Map<String, Object> params) {
        Object kind = params == null ? null : params.get("CounterType");
        if (sa != null && sa.getApi() == ApiType.MoveCounter && params != null
                && params.get("Source") instanceof Card src && moveCounterAmounts.containsKey(src)) {
            // The amount gorge logged with the source election above.
            return Math.max(min, Math.min(max, moveCounterAmounts.remove(src)));
        }
        // Two different asks carry a CounterType param. RemoveCounter's
        // per-counter kind election logs one choose_n[counter] pick per
        // counter removed, so that run is the amount. CountersPutEffect's
        // UpTo$/SplitAmount$ ask (and a divided put) carries the same param
        // but logs ONE numeric choose_n (gorge resume "counter_upto"), so it
        // belongs on the four-argument numeric path. Only route the ask by
        // what it is, never by the mere presence of CounterType: a put
        // answered through takeCounterPicks returns 0 and strands the number
        // pick (Clockwork Avian, Terra's chapter I).
        if (kind instanceof CounterType ct && sa != null && sa.getApi() == ApiType.RemoveCounter) {
            int n = m.takeCounterPicks(seat, ct, max);
            if (n >= min) {
                return n;
            }
            if (n == 0) {
                Integer plain = m.chooseNumber(seat, min, max);
                if (plain != null) {
                    return plain;
                }
                if (min == max) {
                    // One counter kind and the card's fixed CounterNum$ leave
                    // no choice, so gorge logs no counter pick (CR 122.1) and
                    // the forced amount is answered without an ask. Keep the
                    // miss for a free min < max range with no pick.
                    m.note("number step " + m.step() + ": " + min + " (forced: no counter pick)");
                    return min;
                }
                if (min == 0) {
                    // RemoveCounter's "remove up to N": no counter-kind pick
                    // means the controller removed none.
                    return 0;
                }
            }
            m.miss("number", "counter " + ct.getName() + ": " + n + " picks, Forge wants " + min + ".." + max);
        }
        return chooseNumber(sa, title, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (values.size() == 1) {
            return values.get(0);
        }
        return missThen("number", () -> super.chooseNumber(sa, title, values, relatedPlayer));
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return missThen("coin flip", () -> super.chooseFlipResult(sa, flipper, call));
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        if (colors.countColors() == 1) {
            return colors.getColor();
        }
        Byte b = m.chooseColor(seat, colors);
        if (b != null) {
            return b;
        }
        return missThen("colour", () -> super.chooseColor(message, sa, colors));
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return missThen("colour", () -> super.chooseColorAllowColorless(message, c, colors));
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        if (max == 1) {
            Byte b = m.chooseColor(seat, options);
            if (b != null) {
                return ColorSet.fromMask(b);
            }
        }
        return missThen("colours", () -> super.chooseColors(message, sa, min, max, options));
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return missThen("card face", () -> super.chooseSingleCardFace(sa, message, cpp, name));
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        if (faces.size() == 1) {
            return faces.get(0);
        }
        ICardFace dungeon = m.chooseDungeon(seat, faces);
        if (dungeon != null) {
            return dungeon;
        }
        return missThen("card face", () -> super.chooseSingleCardFace(sa, faces, message));
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        if (states.size() == 1) {
            return states.get(0);
        }
        return missThen("card state", () -> super.chooseSingleCardState(sa, states, message, params));
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        Boolean first = m.choosePile(seat);
        if (first != null) {
            return first;
        }
        return missThen("pile", () -> super.chooseCardsPile(sa, pile1, pile2, faceUp));
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        // RemoveCounter's remove-any-type follows this ask with chooseNumber,
        // which counts the run of this kind's picks (takeCounterPicks), so the
        // kind pick stays queued for it. Other asks consume their pick here.
        // Peek before the lone-option shortcut: under UpTo, gorge offers Done
        // even when only one kind is present, and the Done must be consumed by
        // this kind ask (see StepMachine.peekCounterKind), not left queued.
        boolean removeAny = sa != null && sa.getApi() == ApiType.RemoveCounter;
        if (removeAny) {
            CounterType ct = m.peekCounterKind(seat, options);
            if (ct != null) {
                return ct;
            }
        }
        if (options.size() == 1) {
            // A lone legal kind is forced; the number ask consumes the picks.
            return options.get(0);
        }
        CounterType ct = removeAny ? null : m.takeCounterKind(seat, options);
        if (ct != null) {
            return ct;
        }
        return missThen("counter type", () -> super.chooseCounterType(options, sa, prompt, params));
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        if (options.size() == 1) {
            return options.get(0);
        }
        String s = m.chooseLabel(seat, options, "keyword");
        return s != null ? s : missThen("keyword", () -> super.chooseKeywordForPump(options, sa, prompt, tgtCard));
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers.size() == 1) {
            return possibleReplacers.get(0);
        }
        // With several dredgers Forge first asks which replacement to apply;
        // gorge's single dredge pick already named it, so hand Forge that
        // host's replacement and let confirmReplacementEffect answer yes.
        ReplacementEffect dredge = m.dredgeReplacementPick(seat, possibleReplacers);
        if (dredge != null) {
            m.note("replacement order step " + m.step() + ": dredge " + dredge.getHostCard().getName());
            return dredge;
        }
        // gorge poses a "replacement" decision only when the order is
        // observable (decision.KReplacement); with none logged it applied them
        // in its own fixed order, so take Forge's first, deterministically.
        Decision d = take(x -> x.gorgeKind.equals("replacement") && x.picks.size() == 1);
        if (d != null) {
            for (ReplacementEffect re : possibleReplacers) {
                if (StepMachine.norm(String.valueOf(re), re.getHostCard().getName())
                        .startsWith(StepMachine.norm(d.picks.get(0), re.getHostCard().getName()))) {
                    return re;
                }
            }
            m.miss("replacement order", "gorge pick " + d.picks.get(0) + " matches no replacement");
        }
        m.note("replacement order step " + m.step() + ": first of " + possibleReplacers.size() + " (gorge logged none)");
        return possibleReplacers.get(0);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        if (possibleReplacers.size() == 1) {
            return possibleReplacers.get(0);
        }
        return missThen("static order", () -> super.chooseSingleStaticAbility(possibleReplacers));
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        String s = m.chooseLabel(seat, choices, "protection");
        return s != null ? s : missThen("protection", () -> super.chooseProtectionType(sa, choices));
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        String n = m.chooseName(seat);
        if (n != null) {
            return n;
        }
        return missThen("card name", () -> super.chooseCardName(sa, cpp, valid, message));
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        if (faces.isEmpty()) {
            // ChooseCardNameEffect (ChooseFromDefinedCards$ Remembered, Alhammarret, High Arbiter) still asks
            // when the opponent revealed no nonland card: there is no name to choose and gorge logs no decision.
            // "" is what ChooseCardNameAi returns for it, and the effect then names nothing
            // (`if (!chosen.isEmpty())` guards addNamedCard/setNamedCard).
            m.note("card name step " + m.step() + ": no face to name, none chosen (gorge logged none)");
            return "";
        }
        if (faces.size() == 1) {
            return faces.get(0).getName();
        }
        return missThen("card name", () -> super.chooseCardName(sa, faces, message));
    }

    // ---- pregame and match-level: never reached by a scenario ---------------

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return null;
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return new ArrayList<>();
    }

    @Override
    public boolean mulliganKeepHand(Player player, int cardsToReturn) {
        return true;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        throw new HarnessError("mulligan tuck asked: scenarios never mulligan");
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return player; // never asked: setupFirstTurn names seat 0
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return zones.get(0);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return new ArrayList<>(); // no opening-hand actions: setup places cards directly
    }

    // ---- helpers -----------------------------------------------------------

    private <T> T missThen(String what, java.util.function.Supplier<T> ai) {
        m.miss(what, "");
        return ai.get();
    }
}
