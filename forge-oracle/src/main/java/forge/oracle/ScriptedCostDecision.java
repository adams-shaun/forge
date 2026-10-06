// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.List;

import forge.ai.AiCostDecision;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
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

    @Override
    public PaymentDecision visit(CostUntapType cost) {
        return choice(cost, super.visit(cost));
    }

    /** Exile N cards of a type from a zone: gorge's exilecost picks. */
    @Override
    public PaymentDecision visit(CostExile cost) {
        String type = cost.getType();
        if (cost.payCostFromSource() || type.equals("All") || type.contains("FromTopGrave") || type.contains("+withTotal")) {
            return choice(cost, super.visit(cost));
        }
        int n = cost.getAbilityAmount(ability);
        CardCollectionView list = CardLists.getValidCards(player.getCardsIn(cost.getFrom()), type.split(";"), player, source, ability);
        List<Card> picked = m.pickObjects(seat, list, n, n, "cost CostExile");
        if (picked != null && picked.size() == n) {
            return PaymentDecision.card(picked);
        }
        return m.strict() ? null : super.visit(cost);
    }

    @Override
    public PaymentDecision visit(CostReturn cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostReveal cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostPutCardToLib cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostGainControl cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostRemoveAnyCounter cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostExiledMoveToGrave cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostBehold cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostBeholdExile cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostCollectEvidence cost) {
        return choice(cost, super.visit(cost));
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

    @Override
    public PaymentDecision visit(CostExert cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostEnlist cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostPromiseGift cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostChooseColor cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostChooseCreatureType cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostBlight cost) {
        return choice(cost, super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostRevealChosen cost) {
        return choice(cost, super.visit(cost));
    }
}
