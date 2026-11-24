// SPDX-License-Identifier: GPL-3.0-or-later
package forge.game.card;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
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
import forge.card.MagicColor;
import forge.item.PaperCard;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.deck.Deck;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;

/**
 * CR 702.114a, 613.1e, 613.1f, 613.6: Devoid is a characteristic-defining
 * ability that makes the object colorless in layer 5, so a creature that loses
 * all abilities in layer 6 (Humble: "Until end of turn, target creature loses
 * all abilities and has base power and toughness 0/1") stays colorless. No card
 * database: the Devoid creature is built by hand and Humble's spell is the
 * card's own Animate script line.
 */
public class DevoidColourAfterAbilityLossTest {
    /** The fork's forge-gui/res (the type lists only, never the card database). */
    private static String res() {
        String r = System.getProperty("forge.oracle.res", "../forge-gui/res");
        if (!new File(r, "lists/TypeLists.txt").isFile()) {
            throw new IllegalStateException("forge.oracle.res=" + r + " is not the fork's forge-gui/res");
        }
        return r;
    }

    /** Without the type lists Forge silently drops every subtype, Coward included. */
    @BeforeClass
    public void typeLists() {
        Localizer.getInstance().initialize("en-US", res() + "/languages/");
        Lang.createInstance("en-US");
        Map<String, List<String>> types = FileSection.parseSections(FileUtil.readFile(res() + "/lists/TypeLists.txt"));
        for (Map.Entry<String, List<String>> e : types.entrySet()) {
            CardType.Helper.parseTypes(e.getKey(), e.getValue());
        }
        CardType.Constant.LOADED.set();
        assertTrue(CardType.isACreatureType("Coward"), "Coward must be a creature type");
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
        return new Game(players, rules, new Match(rules, players, "devoid"));
    }

    /** forge-gui/res/cardsfolder/e/emrakuls_messenger.txt, the Devoid and Flying lines. */
    private static final String MESSENGER = "Name:Emrakul's Messenger\nManaCost:1 U\nTypes:Creature Eldrazi Faerie Rogue\nPT:2/1\n"
            + "K:Devoid\nK:Flying\nOracle:Devoid (This card has no color.)\nFlying";
    private static final String DRAKE = "Name:Wind Drake\nManaCost:2 U\nTypes:Creature Drake\nPT:2/2\nK:Flying\nOracle:Flying";
    /** forge-gui/res/cardsfolder/h/humble.txt, verbatim. */
    private static final String HUMBLE = "Name:Humble\nManaCost:1 W\nTypes:Instant\n"
            + "A:SP$ Animate | ValidTgts$ Creature | Power$ 0 | Toughness$ 1 | RemoveAllAbilities$ True | IsCurse$ True"
            + " | SpellDescription$ Until end of turn, target creature loses all abilities and has base power and toughness 0/1.\n"
            + "Oracle:Until end of turn, target creature loses all abilities and has base power and toughness 0/1.";

    private static Card onBattlefield(Game game, Player owner, String script, int id) {
        CardRules rules = CardRules.fromScript(Arrays.asList(script.split("\n")));
        Card c = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, id, game);
        owner.getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    private static void castHumble(Game game, Player caster, Card target) {
        Card humble = onBattlefield(game, caster, HUMBLE, 9);
        SpellAbility sa = humble.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        sa.getTargets().add(target);
        AbilityUtils.resolve(sa);
        game.getAction().checkStaticAbilities();
    }

    @Test
    public void devoidStaysColorlessAfterLosingAllAbilities() {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        Card messenger = onBattlefield(game, p1, MESSENGER, 2);
        game.getAction().checkStaticAbilities();
        assertTrue(messenger.getColor().isColorless(), "sanity: Devoid makes it colorless");

        castHumble(game, p0, messenger);

        assertFalse(messenger.hasKeyword("Devoid"), "the keyword is gone (layer 6)");
        assertFalse(messenger.hasKeyword("Flying"), "Flying is gone too");
        assertTrue(messenger.getColor().isColorless(), "the layer-5 CDA already made it colorless (CR 613.6)");
        assertEquals(messenger.getNetPower(), 0);
        assertEquals(messenger.getNetToughness(), 1);
    }

    @Test
    public void aBlueCreatureWithoutDevoidStaysBlue() {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        Card drake = onBattlefield(game, p1, DRAKE, 2);
        game.getAction().checkStaticAbilities();

        castHumble(game, p0, drake);

        assertTrue(drake.getColor().hasBlue());
        assertEquals(drake.getColor().getColor(), MagicColor.BLUE);
    }
}
