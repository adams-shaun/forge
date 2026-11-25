// SPDX-License-Identifier: GPL-3.0-or-later
package forge.game.phase;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;

/**
 * CR 510.4: with a first striker in combat the phase has a second, regular
 * combat damage step, and CR 510.3 / 117.3a give the active player priority
 * in it even when no creature assigns damage there.
 *
 * <p>A lone first striker attacks a player with no creatures. The first-strike
 * step deals its damage; the regular step has nothing to assign. Before the fix
 * onPhaseBegin cleared givePriorityToPlayer when assignCombatDamage(false)
 * assigned nothing, so the game went straight to end of combat.
 */
public class SecondCombatDamageStepPriorityTest {
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
        Game game = new Game(players, rules, new Match(rules, players, "second-damage-step"));
        game.setAge(GameStage.Play);
        return game;
    }

    private static final String FIRST_STRIKER = "Name:First Strike Test Knight\nManaCost:1 W\nTypes:Creature Human Knight\n"
            + "PT:2/2\nK:First Strike\nOracle:First strike";

    private static Card onBattlefield(Game game, Player owner, String script, int id) {
        CardRules rules = CardRules.fromScript(Arrays.asList(script.split("\n")));
        Card c = CardFactory.getCard(new PaperCard(rules, "TST", CardRarity.Common), owner, id, game);
        owner.getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    /** As the priority loop does: a new step starts with priority to give, then its turn-based actions run. */
    private static void beginStep(PhaseHandler ph, PhaseType step, Player active) throws Exception {
        ph.devModeSet(step, active, false);
        priorityField().setBoolean(ph, true);
        Method m = PhaseHandler.class.getDeclaredMethod("onPhaseBegin");
        m.setAccessible(true);
        m.invoke(ph);
    }

    private static Field priorityField() throws Exception {
        Field f = PhaseHandler.class.getDeclaredField("givePriorityToPlayer");
        f.setAccessible(true);
        return f;
    }

    @Test
    public void regularDamageStepGivesPriorityAfterLoneFirstStriker() throws Exception {
        Game game = twoPlayerGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        Card knight = onBattlefield(game, p0, FIRST_STRIKER, 1);
        game.getAction().checkStaticAbilities();
        assertTrue(knight.hasFirstStrike(), "the test knight must have first strike");

        PhaseHandler ph = game.getPhaseHandler();
        Combat combat = new Combat(p0);
        combat.addAttacker(knight, p1);
        ph.setCombat(combat);

        beginStep(ph, PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, p0);
        assertEquals(p1.getLife(), 18, "the first striker deals its damage in the first-strike step");
        assertTrue(priorityField().getBoolean(ph), "the first-strike step gives priority");

        beginStep(ph, PhaseType.COMBAT_DAMAGE, p0);
        assertEquals(p1.getLife(), 18, "the first striker deals no damage again in the regular step");
        assertTrue(priorityField().getBoolean(ph),
                "the regular combat damage step gives priority even when no creature assigns damage (CR 510.3, 510.4)");
    }
}
