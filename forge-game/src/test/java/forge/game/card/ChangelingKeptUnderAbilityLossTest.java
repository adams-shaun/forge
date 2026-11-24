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
 * CR 702.73a, 613.1d, 613.1f, 613.6: Changeling is a characteristic-defining
 * ability that sets every creature type in layer 4, so a creature that loses
 * all abilities afterwards (Curious Colossus: "loses all abilities, becomes a
 * Coward in addition to its other types, and has base power and toughness
 * 1/1") is still every creature type. No card database: the Changeling
 * creature is built by hand and the Colossus ETB is the card's own
 * AnimateAll script line, resolved through the same effect.
 */
public class ChangelingKeptUnderAbilityLossTest {
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
        return new Game(players, rules, new Match(rules, players, "changeling"));
    }

    private static final String CHANGELING = "Name:Chomping Changeling\nManaCost:1 G\nTypes:Creature Shapeshifter\nPT:1/1\n"
            + "K:Changeling\nOracle:Changeling";
    private static final String BEARS = "Name:Grizzly Bears\nManaCost:1 G\nTypes:Creature Bear\nPT:2/2\nOracle:";
    /** forge-gui/res/cardsfolder/c/curious_colossus.txt, verbatim. */
    private static final String COLOSSUS = "Name:Curious Colossus\nManaCost:5 W W\nTypes:Creature Giant Warrior\nPT:7/7\n"
            + "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Card.Self | Execute$ TrigAnimateAll"
            + " | TriggerDescription$ When this creature enters, each creature target opponent controls loses all abilities,"
            + " becomes a Coward in addition to its other types, and has base power and toughness 1/1.\n"
            + "SVar:TrigAnimateAll:DB$ AnimateAll | ValidTgts$ Opponent | ValidCards$ Creature | Power$ 1 | Toughness$ 1"
            + " | RemoveAllAbilities$ True | Types$ Coward | Duration$ Permanent\n"
            + "Oracle:When this creature enters, each creature target opponent controls loses all abilities, becomes a Coward"
            + " in addition to its other types, and has base power and toughness 1/1.";

    private static Card onBattlefield(Game game, Player owner, String script, int id) {
        CardRules rules = CardRules.fromScript(Arrays.asList(script.split("\n")));
        Card c = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, id, game);
        owner.getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    /** Resolves Curious Colossus's own ETB script line at the opponent. */
    private static void resolveColossus(Game game, Player caster, Player opp, Card colossus) {
        SpellAbility sa = AbilityFactory.getAbility(colossus, "TrigAnimateAll");
        sa.setActivatingPlayer(caster);
        sa.getTargets().add(opp);
        AbilityUtils.resolve(sa);
        game.getAction().checkStaticAbilities();
    }

    @Test
    public void changelingStaysEveryCreatureTypeAfterLosingAllAbilities() {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        Card colossus = onBattlefield(game, p0, COLOSSUS, 1);
        Card changeling = onBattlefield(game, p1, CHANGELING, 2);
        game.getAction().checkStaticAbilities();
        assertTrue(changeling.getType().hasAllCreatureTypes(), "sanity: Changeling is every creature type");

        resolveColossus(game, p0, p1, colossus);

        assertFalse(changeling.hasKeyword("Changeling"), "the keyword is gone (layer 6)");
        assertTrue(changeling.getType().hasAllCreatureTypes(), "the layer-4 CDA already set every creature type (CR 613.6)");
        assertTrue(changeling.getType().hasSubtype("Coward"), "Coward is added in layer 4");
        assertEquals(changeling.getNetPower(), 1);
        assertEquals(changeling.getNetToughness(), 1);
        // The effect is on the opponent's creatures only.
        assertEquals(colossus.getNetPower(), 7);
    }

    @Test
    public void aCreatureThatNeverHadChangelingGainsNoCreatureTypes() {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        Card colossus = onBattlefield(game, p0, COLOSSUS, 1);
        Card bear = onBattlefield(game, p1, BEARS, 2);
        game.getAction().checkStaticAbilities();

        resolveColossus(game, p0, p1, colossus);

        assertFalse(bear.getType().hasAllCreatureTypes());
        assertTrue(bear.getType().hasSubtype("Coward"));
    }
    /** forge-gui/res/cardsfolder/a/azure_beastbinder.txt, the single-target Animate path. */
    private static final String BEASTBINDER = "Name:Azure Beastbinder\nManaCost:1 U\nTypes:Creature Rat Rogue\nPT:1/3\n"
            + "T:Mode$ Attacks | ValidCard$ Card.Self | Execute$ TrigAnimate | TriggerDescription$ Whenever CARDNAME attacks, up to one target artifact, creature, or planeswalker an opponent controls loses all abilities until your next turn. If it's a creature, it also has base power and toughness 2/2 until your next turn.\n"
            + "SVar:TrigAnimate:DB$ Animate | ValidTgts$ Artifact.OppCtrl,Creature.OppCtrl,Planeswalker.OppCtrl | TgtPrompt$ Select up to one target artifact, creature, or planeswalker an opponent controls | TargetMin$ 0 | TargetMax$ 1 | RemoveAllAbilities$ True | Duration$ UntilYourNextTurn | SubAbility$ DBAnimate\n"
            + "SVar:DBAnimate:DB$ Animate | Defined$ Targeted.Creature | Power$ 2 | Toughness$ 2 | Duration$ UntilYourNextTurn\n"
            + "Oracle:Whenever CARDNAME attacks, up to one target artifact, creature, or planeswalker an opponent controls loses all abilities until your next turn. If it's a creature, it also has base power and toughness 2/2 until your next turn.";

    /** Resolves Azure Beastbinder's own attack-trigger Animate at the opponent's Changeling. */
    private static void resolveBeastbinder(Game game, Player caster, Card target, Card beastbinder) {
        SpellAbility sa = AbilityFactory.getAbility(beastbinder, "TrigAnimate");
        sa.setActivatingPlayer(caster);
        sa.getTargets().add(target);
        AbilityUtils.resolve(sa);
        game.getAction().checkStaticAbilities();
    }

    @Test
    public void changelingStaysEveryCreatureTypeAfterSingleTargetAnimate() {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        Card beastbinder = onBattlefield(game, p0, BEASTBINDER, 1);
        Card changeling = onBattlefield(game, p1, CHANGELING, 2);
        game.getAction().checkStaticAbilities();
        assertTrue(changeling.getType().hasAllCreatureTypes(), "sanity: Changeling is every creature type");

        resolveBeastbinder(game, p0, changeling, beastbinder);

        assertFalse(changeling.hasKeyword("Changeling"), "the keyword is gone (layer 6)");
        assertTrue(changeling.getType().hasAllCreatureTypes(), "the layer-4 CDA already set every creature type (CR 613.6)");
    }

}
