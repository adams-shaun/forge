// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    public ScriptedController(Game game, Player p, LobbyPlayer lp, StepMachine m, int seat) {
        super(game, p, lp);
        this.m = m;
        this.seat = seat;
    }

    private Decision take(Predicate<Decision> match) {
        return m.take(seat, match);
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
        m.miss("x", announce + " for " + ability);
        return super.announceRequirements(ability, min, max, announce);
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        List<AbilitySub> picked = m.chooseModes(seat, sa, possible, min, num);
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

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        Boolean b = m.yesNo(seat, "confirm " + mode + ": " + message);
        return b != null ? b : super.confirmAction(sa, mode, message, options, cardToShow, params);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) {
        Boolean b = m.yesNo(seat, "bid " + string);
        return b != null ? b : super.confirmBidAction(sa, bidlife, string, bid, winner);
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
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
        Boolean b = m.yesNo(seat, "optional trigger " + sa);
        return b != null ? b : super.confirmTrigger(sa);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        m.miss("binary", question);
        return super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        Boolean b = m.yesNoOptional(seat);
        if (b != null) {
            return b;
        }
        m.miss("pay", string);
        return super.confirmPayment(costPart, string, sa);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
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

    /** Pays from the floating pool only: the engine's own exact-colour pass,
     * then the generic remainder in a fixed WUBRGC order (deterministic, P0 S1). */
    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        ManaPool pool = player.getManaPool();
        int before = pool.totalMana();
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
        m.note("pay mana: pool " + before + " -> " + pool.totalMana() + ", paid=" + toPay.isPaid() + " for " + ability.getHostCard().getName());
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
        List<Card> picked = m.pickObjects(seat, optionList, isOptional ? 0 : amount, amount, "cost card");
        return picked != null ? new CardCollection(picked) : super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        // The driver pays from the pool only; convoke/improvise is never used.
        return new java.util.HashMap<>();
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return new CardCollection(); // pool-only payment, as for convoke
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
        m.miss("keyword cost number", prompt);
        return super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return new ArrayList<>();
    }

    // ---- object picks -------------------------------------------------------

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        List<Card> picked = m.pickObjects(seat, sourceList, isOptional ? 0 : min, max, "card (" + title + ")");
        return picked != null ? new CardCollection(picked) : super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        m.miss("cards (multiple)", title);
        return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        if (optionList.isEmpty()) {
            return null;
        }
        List<T> picked = m.pickObjects(seat, optionList, isOptional ? 0 : 1, 1, "entity (" + title + ")");
        if (picked != null) {
            return picked.isEmpty() ? null : picked.get(0);
        }
        return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
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
        List<Card> picked = m.pickObjects(seat, validCards, min, max, "discard");
        return picked != null ? new CardCollection(picked) : super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) {
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
            return spells;
        }
        m.miss("spell abilities", title);
        return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        if (spells.size() == 1) {
            return spells.get(0);
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
        if (cards.size() <= 1) {
            return cards;
        }
        List<Card> ordered = m.orderCards(seat, cards, "order to " + destinationZone);
        return ordered != null ? new CardCollection(ordered) : super.orderMoveToZoneList(cards, destinationZone, source);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        m.miss("scry", "");
        return super.arrangeForScry(topN);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        m.miss("surveil", "");
        return super.arrangeForSurveil(topN);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
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

    // ---- combat (phase 2 routes these; phase 1 never attacks) --------------

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // No attacks: gorge's level-A drive never attacks, and the attack op is P2-3.
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        // No blocks, as for attacks.
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
        return missThen("colour", () -> super.chooseColor(message, sa, colors));
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return missThen("colour", () -> super.chooseColorAllowColorless(message, c, colors));
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
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
        return missThen("pile", () -> super.chooseCardsPile(sa, pile1, pile2, faceUp));
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        if (options.size() == 1) {
            return options.get(0);
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
        return missThen("replacement order", () -> super.chooseSingleReplacementEffect(possibleReplacers));
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
        return missThen("card name", () -> super.chooseCardName(sa, cpp, valid, message));
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
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
