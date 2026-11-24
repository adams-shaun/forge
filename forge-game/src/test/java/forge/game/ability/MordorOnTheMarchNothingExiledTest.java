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
 * CR 603.7a: Mordor on the March's "Exile it at the beginning of the next end step" is a delayed triggered
 * ability its resolution creates whether or not a creature card was exiled and copied. With nothing in the
 * graveyard to exile, the trigger still happens at the beginning of the end step (and then exiles nothing,
 * CR 603.7c). Only Myriad's text makes the delayed ability conditional on a token having been created
 * (CR 702.116a, MyriadNoTokensCreatesNoDelayedTriggerTest), so registerDelayedTrigger must not drop an
 * AtEOT registration over an empty set unless the script says AtEOTNeedsCard$ True.
 *
 * <p>The test resolves the real Mordor on the March script with no creature card in its controller's
 * graveyard, then fires the end-of-turn phase trigger: the delayed trigger must reach the stack.
 */
public class MordorOnTheMarchNothingExiledTest {
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
        Game game = new Game(players, rules, new Match(rules, players, "mordor"));
        game.setAge(GameStage.Play);
        return game;
    }

    /** A synthetic carrier: CardFactoryUtil installs the same K:Myriad trigger for every card. */
    private static final String MYRIAD_CARRIER = "Name:Myriad Test Beast\nManaCost:2 G\nTypes:Creature Beast\nPT:2/2\n"
            + "K:Myriad\nOracle:Myriad";

    private static Card inZone(Game game, Player owner, String script, int id) {
        CardRules rules = CardRules.fromScript(Arrays.asList(script.split("\n")));
        Card c = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, id, game);
        owner.getZone(ZoneType.Battlefield).add(c);
        return c;
    }


    private static Card inZone(Game game, Player owner, String script, int id, ZoneType zone) {
        CardRules rules = CardRules.fromScript(Arrays.asList(script.split("\n")));
        Card c = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, id, game);
        owner.getZone(zone).add(c);
        return c;
    }

    /** The real card script from the fork's corpus, not a synthetic one. */
    private static String mordorScript() throws java.io.IOException {
        return String.join("\n", java.nio.file.Files.readAllLines(
                new File(res(), "cardsfolder/m/mordor_on_the_march.txt").toPath()));
    }

    @Test
    public void nothingExiledStillRegistersTheEndStepTrigger() throws java.io.IOException {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        // p0's graveyard holds no creature card (the test players have no controller, so no card can be placed
        // in an ordered zone; the empty graveyard is the case under test). The host sits on the battlefield
        // zone only because it is the one zone that accepts a card without a controller.
        Card mordor = inZone(game, p0, mordorScript(), 1, ZoneType.Battlefield);

        // The spell's first step ("Exile a creature card from your graveyard") asks its controller to choose, and
        // the test players have none; with no creature card it leaves Remembered empty. So resolve the copy step
        // ("Create a token that's a copy of it ... Exile it at the beginning of the next end step") from the real
        // script's DBCopy SVar in exactly that state.
        SpellAbility copy = AbilityFactory.getAbility(mordor, "DBCopy");
        assertNotNull(copy, "Mordor on the March's script must carry the DBCopy step");
        assertEquals(copy.getParam("AtEOT"), "Exile", "the copy step schedules the end-step exile");
        copy.setActivatingPlayer(p0);
        AbilityUtils.resolve(copy);

        assertEquals(game.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count(), 0L,
                "no creature card was in the graveyard, so no token copy exists");
        assertEquals(game.getCardsIn(ZoneType.Exile).size(), 0, "nothing was exiled");

        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, p0);
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(p0), false);
        assertTrue(game.getStack().hasSimultaneousStackEntries(),
                "the delayed exile trigger Mordor on the March's resolution created goes on the stack at the end step (CR 603.7a)");
    }
}
