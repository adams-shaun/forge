// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Iterables;

import forge.ai.AiCostDecision;
import forge.game.GameEntityCounterTable;
import forge.game.card.CardCollection;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterType;
import forge.game.cost.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Cost decisions for the scripted seat. P0 found that ordinary costs went
 * through AiCostDecision, i.e. silent AI answers. Here every cost part either
 * <ul>
 * <li>has nothing to choose ({T}, {Q}, pay life, mana, a cost on the source
 *     itself, a fixed number) and keeps the AI parent's deterministic answer;</li>
 * <li>chooses cards (sacrifice, discard, tap/untap a type, exile, return ...)
 *     and is answered from gorge's object picks at the current step, or is
 *     forced (exactly as many candidates as the cost needs), or is a strict
 *     miss.</li>
 * </ul>
 */
public class ScriptedCostDecision extends AiCostDecision {
    final StepMachine m;
    final int seat;

    public ScriptedCostDecision(StepMachine m, int seat, Player p, SpellAbility sa, boolean effect) {
        super(p, sa, effect);
        this.m = m;
        this.seat = seat;
    }

    /** A card-choosing cost: scripted pick, else forced, else strict miss
     * (loose: the AI's pick). */
    private PaymentDecision cards(CostPart cost, CardCollectionView valid, int amount, PaymentDecision ai) {
        List<Card> picked = m.pickObjects(seat, valid, amount, amount, "cost " + cost.getClass().getSimpleName());
        if (picked != null) {
            return PaymentDecision.card(picked);
        }
        return ai;
    }

    private CardCollectionView valid(ZoneType zone, String type) {
        CardCollectionView list = player.getCardsIn(zone);
        return CardLists.getValidCards(list, type.split(";"), player, source, ability);
    }

    private PaymentDecision choice(CostPart cost, PaymentDecision ai) {
        if (cost.payCostFromSource()) {
            return ai;
        }
        m.miss("cost " + cost.getClass().getSimpleName(), cost.toString());
        return ai;
    }

    @Override
    public PaymentDecision visit(CostSacrifice cost) {
        if (cost.payCostFromSource() || cost.getType().equals("OriginalHost") || cost.getAmount().equals("All")) {
            return super.visit(cost);
        }
        int n = cost.getAbilityAmount(ability);
        if (n == 0) {
            return PaymentDecision.number(0);
        }
        CardCollectionView list = CardLists.filter(player.getCardsIn(ZoneType.Battlefield), CardPredicates.canBeSacrificedBy(ability, isEffect()));
        list = CardLists.getValidCards(list, cost.getType().replace("+WithDifferentNames", "").split(";"), player, source, ability);
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostDiscard cost) {
        String type = cost.getType();
        if (cost.payCostFromSource() || type.equals("Hand") || type.equals("LastDrawn") || type.equals("Random") || type.contains("WithSameName")) {
            return super.visit(cost);
        }
        int n = cost.getAbilityAmount(ability);
        return cards(cost, valid(ZoneType.Hand, type), n, m.strict() ? null : super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostTapType cost) {
        int n = cost.getAbilityAmount(ability);
        CardCollectionView list = CardLists.filter(valid(ZoneType.Battlefield, cost.getType().replace("+withTotalPowerGE", "")), CardPredicates.UNTAPPED);
        if (cost.getType().contains("+withTotalPowerGE")) {
            // Crew / saddle / teamwork: any number with enough total power;
            // gorge logs the tapped creatures as one tapcost pick.
            List<Card> picked = m.pickObjects(seat, list, 1, list.size(), "cost CostTapType (total power)");
            if (picked != null) {
                return PaymentDecision.card(picked);
            }
            return m.strict() ? null : super.visit(cost);
        }
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    /** Untap N tapped permanents of a type: gorge's object pick. */
    @Override
    public PaymentDecision visit(CostUntapType cost) {
        int n = cost.getAbilityAmount(ability);
        if (n == 0) {
            return PaymentDecision.number(0);
        }
        CardCollectionView list = CardLists.getValidCards(player.getGame().getCardsIn(ZoneType.Battlefield),
                cost.getType().split(";"), player, source, ability);
        list = CardLists.filter(list, c -> c.isTapped() && c.canUntap(null, false)
                && (cost.canUntapSource || !c.equals(source)));
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    /** Exile N cards of a type from a zone: gorge's exilecost picks. Nothing
     * to choose when the cost exiles its source or every card ("All"), and
     * FromTopGrave is the top N of the graveyard; a total-mana-value cost
     * (+withTotalCMCGE / EQ) is any number of cards, one pick. */
    @Override
    public PaymentDecision visit(CostExile cost) {
        String type = cost.getType();
        if (cost.payCostFromSource() || type.equals("All")) {
            return super.visit(cost);
        }
        int n = cost.getAbilityAmount(ability);
        if (type.contains("FromTopGrave")) {
            CardCollection gy = CardLists.getValidCards(player.getCardsIn(ZoneType.Graveyard),
                    type.replace("FromTopGrave", "").split(";"), player, source, ability);
            Collections.reverse(gy);
            return PaymentDecision.card(Iterables.limit(gy, n));
        }
        CardCollectionView zone = cost.zoneRestriction != 1 ? player.getGame().getCardsIn(cost.getFrom()) : player.getCardsIn(cost.getFrom());
        if (type.contains("+withTotal")) {
            String clean = type.replaceAll("\\+withTotalCMC(GE|EQ).*$", "");
            CardCollectionView list = CardLists.getValidCards(zone, clean.split(";"), player, source, ability);
            List<Card> picked = m.pickObjects(seat, list, 1, list.size(), "cost CostExile (total mana value)");
            if (picked != null) {
                return PaymentDecision.card(picked);
            }
            return m.strict() ? null : super.visit(cost);
        }
        CardCollectionView list = CardLists.getValidCards(zone, type.split(";"), player, source, ability);
        List<Card> picked = m.pickObjects(seat, list, n, n, "cost CostExile");
        if (picked != null && picked.size() == n) {
            return PaymentDecision.card(picked);
        }
        return m.strict() ? null : super.visit(cost);
    }

    /** Return N permanents you control to hand (Quirion Ranger's
     * Return<1/Forest>): gorge's returncost pick. */
    @Override
    public PaymentDecision visit(CostReturn cost) {
        if (cost.payCostFromSource()) {
            return super.visit(cost);
        }
        int n = cost.getAbilityAmount(ability);
        return cards(cost, valid(ZoneType.Battlefield, cost.getType()), n, m.strict() ? null : super.visit(cost));
    }

    /** Reveal N cards of a type (revealcost; reveal-or-choose's revealorchoose
     * / choosecost arms are one pick across hand and battlefield). Nothing to
     * choose for the source or the whole hand. */
    @Override
    public PaymentDecision visit(CostReveal cost) {
        if (cost.payCostFromSource() || cost.getType().equals("Hand")) {
            return super.visit(cost);
        }
        int n = cost.getAbilityAmount(ability);
        if (n == 0) {
            return PaymentDecision.number(0);
        }
        CardCollectionView zone = player.getCardsIn(cost.getRevealFrom());
        CardCollectionView list;
        if (cost.getType().equals("SameColor")) {
            list = CardLists.filter(zone, c -> CardLists.filter(zone, o -> !o.equals(c) && o.sharesColorWith(c)).size() > 0);
        } else {
            list = CardLists.getValidCards(zone, cost.getType().split(";"), player, source, ability);
        }
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    /** Put N cards of a type from a zone on top of their owner's library:
     * gorge's puttolibcost pick. */
    @Override
    public PaymentDecision visit(CostPutCardToLib cost) {
        if (cost.payCostFromSource()) {
            return super.visit(cost);
        }
        int n = cost.getAbilityAmount(ability);
        CardCollectionView zone = cost.isSameZone() ? player.getGame().getCardsIn(cost.getFrom()) : player.getCardsIn(cost.getFrom());
        CardCollectionView list = CardLists.getValidCards(zone, cost.getType().split(";"), player, source, ability);
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    /** Gain control of N permanents of a type. */
    @Override
    public PaymentDecision visit(CostGainControl cost) {
        if (cost.payCostFromSource()) {
            return super.visit(cost);
        }
        int n = cost.getAbilityAmount(ability);
        CardCollectionView list = CardLists.getValidCards(player.getGame().getCardsIn(ZoneType.Battlefield),
                cost.getType().split(";"), player, source, ability);
        list = CardLists.filter(list, c -> c.canBeControlledBy(player));
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    /** Remove N counters. A fixed kind among several permanents is gorge's
     * one-object pick per counter (subcounter / trigger_cost_among), and any
     * kind on one permanent is gorge's counter-kind pick per counter; a lone
     * permanent, or a lone kind left on it, is forced. Left on choice(): any
     * kind spread over several permanents (no card in the corpus pays such a
     * cost from more than one permanent with mixed kinds at the pin). */
    @Override
    public PaymentDecision visit(CostRemoveAnyCounter cost) {
        int n = cost.getAbilityAmount(ability);
        if (n <= 0) {
            return super.visit(cost);
        }
        CardCollectionView list = cost.payCostFromSource()
                ? new CardCollection(ability.getHostCard())
                : CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
        CounterType fixed = cost.counter;
        list = CardLists.filter(list, c -> fixed != null
                ? c.getCounters(fixed) > 0 && c.canRemoveCounters(fixed)
                : c.getCounters().elementSet().stream().anyMatch(t -> c.getCounters(t) > 0 && c.canRemoveCounters(t)));
        if (list.isEmpty()) {
            return super.visit(cost);
        }
        if (fixed == null && list.size() > 1) {
            return choice(cost, super.visit(cost));
        }
        Map<Card, Map<CounterType, Integer>> left = new LinkedHashMap<>();
        for (Card c : list) {
            Map<CounterType, Integer> k = new LinkedHashMap<>();
            for (CounterType t : c.getCounters().elementSet()) {
                int have = c.getCounters(t);
                if (have > 0 && (fixed == null ? c.canRemoveCounters(t) : t.equals(fixed))) {
                    k.put(t, have);
                }
            }
            left.put(c, k);
        }
        GameEntityCounterTable table = new GameEntityCounterTable();
        for (int i = 0; i < n; i++) {
            List<Card> open = new ArrayList<>();
            for (Map.Entry<Card, Map<CounterType, Integer>> e : left.entrySet()) {
                if (e.getValue().values().stream().anyMatch(v -> v > 0)) {
                    open.add(e.getKey());
                }
            }
            if (open.isEmpty()) {
                return m.strict() ? null : super.visit(cost);
            }
            Card card;
            if (open.size() == 1) {
                card = open.get(0);
                m.takeLonePick(seat, card);
            } else {
                List<Card> picked = m.pickObjects(seat, open, 1, 1, "cost CostRemoveAnyCounter");
                if (picked == null || picked.size() != 1) {
                    return m.strict() ? null : super.visit(cost);
                }
                card = picked.get(0);
            }
            Map<CounterType, Integer> kinds = left.get(card);
            List<CounterType> avail = new ArrayList<>();
            kinds.forEach((t, v) -> {
                if (v > 0) {
                    avail.add(t);
                }
            });
            CounterType ct = avail.size() == 1 ? avail.get(0) : m.takeCounterKind(seat, avail);
            if (ct == null) {
                m.miss("cost CostRemoveAnyCounter", "counter kind among " + avail);
                return super.visit(cost);
            }
            kinds.put(ct, kinds.get(ct) - 1);
            table.put(null, card, ct, 1);
        }
        return PaymentDecision.counters(table);
    }

    /** Move N exiled cards to their owners' graveyards: gorge's
     * movetogravecost pick. */
    @Override
    public PaymentDecision visit(CostExiledMoveToGrave cost) {
        int n = cost.getAbilityAmount(ability);
        CardCollectionView list = CardLists.getValidCards(player.getGame().getCardsIn(ZoneType.Exile),
                cost.getType().split(";"), player, source, ability);
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    /** Behold N cards of a type (reveal from hand or choose on the
     * battlefield): gorge's beholdcost pick. */
    @Override
    public PaymentDecision visit(CostBehold cost) {
        int n = cost.getAbilityAmount(ability);
        CardCollectionView list = CardLists.getValidCards(player.getCardsIn(cost.getRevealFrom()),
                cost.getType().split(";"), player, source, ability);
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostBeholdExile cost) {
        int n = cost.getAbilityAmount(ability);
        CardCollectionView list = CardLists.getValidCards(player.getCardsIn(cost.getRevealFrom()),
                cost.getType().split(";"), player, source, ability);
        return cards(cost, list, n, m.strict() ? null : super.visit(cost));
    }

    /** Collect evidence N: any graveyard cards with total mana value N or
     * more, gorge's one evidence pick. */
    @Override
    public PaymentDecision visit(CostCollectEvidence cost) {
        CardCollectionView list = CardLists.filter(player.getCardsIn(ZoneType.Graveyard), CardPredicates.canExiledBy(ability, isEffect()));
        List<Card> picked = m.pickObjects(seat, list, 1, list.size(), "cost CostCollectEvidence");
        if (picked != null) {
            return PaymentDecision.card(picked);
        }
        return m.strict() ? null : super.visit(cost);
    }

    /** Forage: gorge logs which way (forage_exile / a Food sacrifice) and then
     * the cards; Forge's decision is just the cards (three from the graveyard,
     * or one Food). */
    @Override
    public PaymentDecision visit(CostForage cost) {
        if (m.take(seat, d -> d.pickKinds.contains("trigger_cost_decline")) != null) {
            m.note("forage declined (gorge: trigger_cost_decline)");
            return null;
        }
        CardCollectionView food = CardLists.filter(player.getCardsIn(ZoneType.Battlefield), CardPredicates.isType("Food"), CardPredicates.canBeSacrificedBy(ability, isEffect()));
        // gorge logs which way only when both are open; with no Food it exiles.
        boolean exile = m.take(seat, d -> d.pickKinds.contains("forage_exile")) != null || food.isEmpty();
        CardCollectionView list = exile
                ? CardLists.filter(player.getCardsIn(ZoneType.Graveyard), CardPredicates.canExiledBy(ability, isEffect()))
                : CardLists.filter(player.getCardsIn(ZoneType.Battlefield), CardPredicates.isType("Food"), CardPredicates.canBeSacrificedBy(ability, isEffect()));
        int n = exile ? 3 : 1;
        List<Card> picked = m.pickObjects(seat, list, n, n, "cost CostForage");
        if (picked != null && picked.size() == n) {
            return PaymentDecision.card(picked);
        }
        return m.strict() ? null : super.visit(cost);
    }

    /** Exert N other permanents of a type (exerting the source itself has
     * nothing to choose; gorge's exert election is answered at attack). */
    @Override
    public PaymentDecision visit(CostExert cost) {
        if (cost.payCostFromSource()) {
            return super.visit(cost);
        }
        int n = cost.getAbilityAmount(ability);
        if (n == 0) {
            return PaymentDecision.number(0);
        }
        return cards(cost, valid(ZoneType.Battlefield, cost.getType()), n, m.strict() ? null : super.visit(cost));
    }

    /** Enlist: one creature that could tap to enlist, gorge's enlist pick. */
    @Override
    public PaymentDecision visit(CostEnlist cost) {
        return cards(cost, CostEnlist.getCardsForEnlisting(player), 1, m.strict() ? null : super.visit(cost));
    }

    /** Promise a gift to one opponent: gorge's gift_promise pick names the
     * player; a lone opponent is forced. */
    @Override
    public PaymentDecision visit(CostPromiseGift cost) {
        List<Player> opps = new ArrayList<>(cost.getPotentialPlayers(player, ability));
        if (opps.size() == 1) {
            m.takeLonePick(seat, opps.get(0));
            return PaymentDecision.players(opps);
        }
        List<Player> picked = m.pickObjects(seat, opps, 1, 1, "cost CostPromiseGift");
        if (picked != null && picked.size() == 1) {
            return PaymentDecision.players(picked);
        }
        return m.strict() ? null : super.visit(cost);
    }

    /** The parent asks the controller (chooseColors), which answers from
     * gorge's color pick or records its own miss. */
    @Override
    public PaymentDecision visit(CostChooseColor cost) {
        return super.visit(cost);
    }

    /** The parent asks the controller (chooseSomeType), which answers from
     * gorge's type pick or records its own miss. */
    @Override
    public PaymentDecision visit(CostChooseCreatureType cost) {
        return super.visit(cost);
    }

    /** Blight N is N -1/-1 counters on ONE creature you control: a one-card
     * pick like CostSacrifice, not a cost with nothing to choose. */
    @Override
    public PaymentDecision visit(CostBlight cost) {
        if (cost.payCostFromSource()) {
            return super.visit(cost);
        }
        CardCollectionView list = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield),
                cost.getType().split(";"), player, source, ability);
        list = CardLists.filter(list, CardPredicates.canReceiveCounters(cost.getCounter()));
        return cards(cost, list, 1, m.strict() ? null : super.visit(cost));
    }

    /** Reveal the card chosen earlier: nothing to choose (the parent's
     * number(1)). */
    @Override
    public PaymentDecision visit(CostRevealChosen cost) {
        return super.visit(cost);
    }
}
