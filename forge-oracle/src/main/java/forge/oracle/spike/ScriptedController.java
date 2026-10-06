package forge.oracle.spike;

import java.util.List;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * P0 spike controller. Priority actions and targets come from the scenario
 * (through {@link Run}); every other decision falls back to the AI parent.
 * Mana is paid only from the floating pool, through the engine's own
 * PlaySpellAbility.payManaCost (X, cost adjustment) and ManaPool.
 */
public class ScriptedController extends PlayerControllerAi {
    final Run run;
    final int seat;

    public ScriptedController(Game game, Player p, LobbyPlayer lp, Run run, int seat) {
        super(game, p, lp);
        this.run = run;
        this.seat = seat;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return run.onPriority(this);
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        run.played++;
        return PlaySpellAbility.playSpellAbility(this, player, sa);
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility sa) {
        return run.chooseTargets(this, sa);
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        // Engine-side cost assembly (X, CostAdjustment, offerings) then applyManaToCost.
        return PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect);
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        ManaPool pool = player.getManaPool();
        int before = pool.totalMana();
        pool.payManaCostFromPool(toPay, ability, false, ability.getPayingMana());
        // Generic remainder: spend floating mana in fixed WUBRGC order.
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
        run.payCalls++;
        run.notes.add("applyManaToCost: pool " + before + " -> " + pool.totalMana() + ", paid=" + toPay.isPaid() + " for " + ability.getHostCard().getName());
        return toPay.isPaid();
    }
}
