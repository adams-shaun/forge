// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardFactory;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * CR 704.3 performs all applicable state-based actions at once, and CR 404.3
 * lets an owner arrange every card that entered their graveyard at the same
 * time. A lethal-damage creature (desCreats) and a 0-loyalty planeswalker
 * (noRegCreats) therefore die in one event and the owner orders both. Forge
 * asked each list separately and only when it held more than one card, so this
 * pair reached the graveyard in list order with no ask at all (Volcanic Salvo).
 */
public class SbaGraveyardOrderTest {
    private static final String WALKER = "Name:Phyrexian Walker\nManaCost:0\nTypes:Artifact Creature Construct\nPT:0/3\nOracle:";
    private static final String TIBALT = "Name:Tibalt, the Fiend-Blooded\nManaCost:1 R R\nTypes:Planeswalker Tibalt\nLoyalty:3\nOracle:";

    private static final String LORD = "Name:Test Lord\nManaCost:0\nTypes:Creature Elf\nPT:1/1\n"
            + "S:Mode$ Continuous | Affected$ Creature.Other+YouCtrl | AddPower$ 1 | AddToughness$ 1 | Description$ Other creatures you control get +1/+1.\nOracle:";
    private static final String DORK_A = "Name:Dork A\nManaCost:0\nTypes:Creature Elf\nPT:0/0\nOracle:";
    private static final String DORK_B = "Name:Dork B\nManaCost:0\nTypes:Creature Elf\nPT:0/0\nOracle:";

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    /** Records every order ask and answers it by reversing the offered order. */
    private static final class OrderRecorder extends PlayerControllerAi {
        final List<List<String>> asks = new ArrayList<>();

        OrderRecorder(Game game, Player p, LobbyPlayer lp) {
            super(game, p, lp);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
            List<String> names = new ArrayList<>();
            CardCollection reversed = new CardCollection();
            for (Card c : cards) {
                names.add(c.getName());
                reversed.add(0, c);
            }
            asks.add(names);
            return reversed;
        }
    }

    private static final class Lobby extends LobbyPlayer implements IGameEntitiesFactory {
        OrderRecorder controller;

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
            Player p = new Player(getName(), game, id);
            controller = new OrderRecorder(game, p, this);
            p.setFirstController(controller);
            return p;
        }
    }

    private static Card onBattlefield(Game game, Player owner, String script, int id) {
        CardRules rules = CardRules.fromScript(Arrays.asList(script.split("\n")));
        Card c = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, id, game);
        owner.getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    private static List<String> names(CardCollectionView cards) {
        List<String> out = new ArrayList<>();
        for (Card c : cards) {
            out.add(c.getName());
        }
        return out;
    }

    @Test
    public void oneAskOrdersLethalDamageAndZeroLoyaltyTogether() {
        Lobby[] lobbies = {new Lobby("p0"), new Lobby("p1")};
        List<RegisteredPlayer> players = new ArrayList<>();
        for (Lobby lobby : lobbies) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(lobby));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(players, rules, new Match(rules, players, "sba-order"));
        Player p1 = game.getPlayers().get(1);

        Card walker = onBattlefield(game, p1, WALKER, 1);
        onBattlefield(game, p1, TIBALT, 2);
        game.getAction().checkStaticAbilities();
        walker.setDamage(3); // lethal on a 0/3; the planeswalker has no loyalty counters

        game.getAction().checkStateEffects(true);

        assertEquals(lobbies[1].controller.asks.size(), 1,
                "one SBA event is one CR 404.3 ask, not one per list: " + lobbies[1].controller.asks);
        assertEquals(Set.copyOf(lobbies[1].controller.asks.get(0)),
                Set.of("Phyrexian Walker", "Tibalt, the Fiend-Blooded"),
                "the ask offers both cards that enter the graveyard at the same time");
        assertEquals(names(p1.getCardsIn(ZoneType.Graveyard)),
                List.of("Phyrexian Walker", "Tibalt, the Fiend-Blooded"),
                "the owner's answer, not the list order, decides the graveyard");
        assertTrue(p1.getCardsIn(ZoneType.Battlefield).isEmpty(), "both permanents left the battlefield");
    }

    /**
     * The mixed batch must not suppress the ordinary per-list ask of a later pass
     * in the same checkStateEffects call: the pass-0 union is [0-loyalty
     * planeswalker, lethal-damage lord]; the lord's death then drops two 1/1
     * creatures to 0/0, which are one noRegCreats list of two cards and still get
     * their own CR 404.3 ask.
     */
    @Test
    public void laterPassWithOneMultiCardListStillAsks() {
        Lobby[] lobbies = {new Lobby("p0"), new Lobby("p1")};
        List<RegisteredPlayer> players = new ArrayList<>();
        for (Lobby lobby : lobbies) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(lobby));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(players, rules, new Match(rules, players, "sba-order-two-pass"));
        Player p1 = game.getPlayers().get(1);

        Card lord = onBattlefield(game, p1, LORD, 1);
        onBattlefield(game, p1, TIBALT, 2);
        onBattlefield(game, p1, DORK_A, 3);
        onBattlefield(game, p1, DORK_B, 4);
        game.getAction().checkStaticAbilities();
        lord.setDamage(1); // lethal on the 1/1 lord; the dorks are 1/1 only while it lives

        game.getAction().checkStateEffects(true);

        List<List<String>> asks = lobbies[1].controller.asks;
        assertEquals(asks.size(), 2, "pass 0 asks once for the mixed batch, pass 1 asks once for the two dorks: " + asks);
        assertEquals(Set.copyOf(asks.get(0)), Set.of("Test Lord", "Tibalt, the Fiend-Blooded"));
        assertEquals(Set.copyOf(asks.get(1)), Set.of("Dork A", "Dork B"));
        assertTrue(p1.getCardsIn(ZoneType.Battlefield).isEmpty(), "everything left the battlefield");
        assertEquals(p1.getCardsIn(ZoneType.Graveyard).size(), 4);
    }
}
