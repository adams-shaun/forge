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
 * CR 702.181a: Mobilize N is "Whenever this creature attacks, create N tapped and attacking 1/1 red Warrior
 * creature tokens. Sacrifice them at the beginning of the next end step." The sacrifice sentence is unconditional,
 * so CR 603.7a creates the delayed triggered ability when the ability resolves even for N = 0 (Avenger of the
 * Fallen with no creature card in its controller's graveyard); it then triggers and does nothing (CR 603.7c).
 * TokenEffect.resolve returned on a zero amount before makeTokenTable, the only place that registered the AtEOT
 * trigger, so Forge left the end step with an empty stack where the rules put the no-op ability on it.
 * MyriadNoTokensCreatesNoDelayedTriggerTest pins the one text (CR 702.116a) that stays conditional on a token.
 *
 * <p>The test resolves the Mobilize keyword's own override ability, from the real Avenger of the Fallen script,
 * with an empty graveyard, then fires the end-of-turn phase trigger: the delayed trigger must reach the stack.
 */
public class MobilizeZeroTokensStillSchedulesSacrificeTest {
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

    private static Card inZone(Game game, Player owner, String script, int id, ZoneType zone) {
        CardRules rules = CardRules.fromScript(Arrays.asList(script.split("\n")));
        Card c = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, id, game);
        owner.getZone(zone).add(c);
        return c;
    }

    /** The real card script from the fork's corpus, not a synthetic one. */
    private static String avengerScript() throws java.io.IOException {
        return String.join("\n", java.nio.file.Files.readAllLines(
                new File(res(), "cardsfolder/a/avenger_of_the_fallen.txt").toPath()));
    }

    /** The Mobilize override ability, the one carrying AtEOT$ Sacrifice. */
    private static SpellAbility mobilizeAbility(Card card) {
        for (Trigger t : card.getTriggers()) {
            SpellAbility sa = t.getOverridingAbility();
            if (sa != null && "Sacrifice".equals(sa.getParam("AtEOT"))) {
                return sa;
            }
        }
        return null;
    }

    @Test
    public void zeroTokensStillRegistersTheEndStepSacrificeTrigger() throws java.io.IOException {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        // p0's graveyard holds no creature card, so Mobilize X is 0 (the test players have no controller, so no
        // card can be placed in an ordered zone; the empty graveyard is the case under test).
        Card avenger = inZone(game, p0, avengerScript(), 1, ZoneType.Battlefield);
        game.getAction().checkStaticAbilities();

        SpellAbility mobilize = mobilizeAbility(avenger);
        assertNotNull(mobilize, "Avenger of the Fallen must carry the Mobilize trigger");
        assertFalse(mobilize.hasParam("AtEOTNeedsCard"), "Mobilize's sacrifice is not conditional on a token");
        mobilize.setActivatingPlayer(p0);
        AbilityUtils.resolve(mobilize);

        assertEquals(game.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count(), 0L,
                "no creature card was in the graveyard, so Mobilize 0 creates no Warrior token");

        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, p0);
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(p0), false);
        assertTrue(game.getStack().hasSimultaneousStackEntries(),
                "the delayed sacrifice trigger Mobilize's resolution created goes on the stack at the end step (CR 603.7a)");
    }
}
