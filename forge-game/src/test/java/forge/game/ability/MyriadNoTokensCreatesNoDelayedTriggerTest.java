// SPDX-License-Identifier: GPL-3.0-or-later
package forge.game.ability;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.LobbyPlayer;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardType;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;

/**
 * CR 702.116a: Myriad creates the "exile the tokens at end of combat"
 * delayed trigger only "if one or more tokens are created this way". With one
 * opponent and that opponent defending, ForEach$ OppNonDefendingPlayer
 * iterates zero times; no token is created and no delayed trigger exists.
 *
 * <p>The test resolves the Myriad keyword's own override ability and then
 * fires the end-combat phase trigger. Before the guard in
 * SpellAbilityEffect.registerDelayedTrigger the empty card set still registered
 * an ExileCombat trigger, which put a no-op ability on the stack; after the
 * guard the delayed-trigger list is empty and so is the stack.
 */
public class MyriadNoTokensCreatesNoDelayedTriggerTest {
    /** The fork's forge-gui/res (the type lists only, never the card database). */
    private static String res() {
        String r = System.getProperty("forge.oracle.res", "../forge-gui/res");
        if (!new File(r, "lists/TypeLists.txt").isFile()) {
            throw new IllegalStateException("forge.oracle.res=" + r + " is not the fork's forge-gui/res");
        }
        return r;
    }

    @BeforeClass
    public void typeLists() {
        Localizer.getInstance().initialize("en-US", res() + "/languages/");
        Lang.createInstance("en-US");
        Map<String, List<String>> types = FileSection.parseSections(FileUtil.readFile(res() + "/lists/TypeLists.txt"));
        for (Map.Entry<String, List<String>> e : types.entrySet()) {
            CardType.Helper.parseTypes(e.getKey(), e.getValue());
        }
        CardType.Constant.LOADED.set();
        assertTrue(CardType.isACreatureType("Beast"), "Beast must be a creature type");
    }

    private static final class Lobby extends LobbyPlayer implements IGameEntitiesFactory {
        Lobby(String n) {
            super(n);
        }

        @Override
        public void hear(LobbyPlayer player, String message) {
        }

        @Override
        public PlayerController createMindSlaveController(Player master, Player slave) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            return new Player(getName(), game, id);
        }
    }

    private static Game twoPlayerGame() {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[] {"a", "b"}) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(new Lobby(n)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(players, rules, new Match(rules, players, "myriad"));
        game.setAge(GameStage.Play);
        return game;
    }

    /** A synthetic carrier: CardFactoryUtil installs the same K:Myriad trigger for every card. */
    private static final String MYRIAD_CARRIER = "Name:Myriad Test Beast\nManaCost:2 G\nTypes:Creature Beast\nPT:2/2\n"
            + "K:Myriad\nOracle:Myriad";

    private static Card onBattlefield(Game game, Player owner, String script, int id) {
        CardRules rules = CardRules.fromScript(Arrays.asList(script.split("\n")));
        Card c = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, id, game);
        owner.getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    /** The Myriad override ability, the one carrying AtEOT$ ExileCombat. */
    private static SpellAbility myriadAbility(Card card) {
        for (Trigger t : card.getTriggers()) {
            SpellAbility sa = t.getOverridingAbility();
            if (sa != null && sa.hasParam("AtEOT") && "ExileCombat".equals(sa.getParam("AtEOT"))) {
                return sa;
            }
        }
        return null;
    }

    @Test
    public void noTokenMeansNoEndOfCombatDelayedTrigger() {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        Card beast = onBattlefield(game, p0, MYRIAD_CARRIER, 1);
        game.getAction().checkStaticAbilities();

        // One opponent, and that opponent is the defending player: Myriad's
        // ForEach$ OppNonDefendingPlayer set is empty.
        Combat combat = new Combat(p0);
        combat.addAttacker(beast, p1);
        game.getPhaseHandler().setCombat(combat);

        SpellAbility myriad = myriadAbility(beast);
        assertNotNull(myriad, "the Myriad carrier must carry the Myriad trigger");
        myriad.setActivatingPlayer(p0);
        AbilityUtils.resolve(myriad);

        assertEquals(game.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count(), 0L,
                "the defending player is the only opponent, so Myriad creates no token");

        // End of combat: without the guard, the empty-set AtEOT registration
        // leaves a delayed ExileCombat trigger that becomes a no-op ability.
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_END, p0);
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(p0), false);
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "no Myriad delayed trigger may become a stacked ability when no token was created (CR 702.116a)");
        assertTrue(game.getStack().isEmpty(),
                "the end-of-combat stack must be empty when no Myriad token was created");
    }
}
