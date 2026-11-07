// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.Map;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Setup restates the board as it stood before the scenario turn, so placement
 * must not leave any per-turn event history behind. The two cases a farm game
 * measured: a creature that enters with counters (Creakwood Safewright) used
 * to satisfy Lasting Tarfire's {@code Count$CountersAddedThisTurn}, and a
 * setup life above 20 used to consume Vanguard Seraph's {@code FirstTime$}
 * life gain. Both now go through Forge's own cleanup bookkeeping, which also
 * covers any per-turn field cleanup grows later.
 */
public class SetupTurnHistoryTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static final String REQUEST = "{\"abilities\":{},\"gorge_decisions\":[],"
            + "\"id\":\"setup/turn-history\",\"item\":{\"id\":\"setup/turn-history\","
            + "\"card\":\"\",\"template\":\"setup\",\"name\":\"setup-turn-history\","
            + "\"setup\":{\"p0\":{\"battlefield\":[\"Lasting Tarfire\",\"Creakwood Safewright\"],"
            + "\"life\":24},\"p1\":{\"battlefield\":[\"Grizzly Bears\"]}},\"steps\":[]}}";

    /** Build the game and place the setup, exactly as a replay does. */
    private static StepMachine placed() {
        StepMachine m = new StepMachine(Request.parse(REQUEST), true);
        m.build();
        SetupBuilder.place(m);
        return m;
    }

    private static Card named(Player p, String name) {
        return p.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    @Test
    public void placementLeavesNoPerTurnHistory() {
        StepMachine m = placed();
        Game game = m.game;
        Player p0 = m.seats[0];
        Card safewright = named(p0, "Creakwood Safewright");

        assertEquals(game.getCounterAddedThisTurn(null, safewright), 0,
                "an entry counter is board state, not a counter put this turn");
        assertEquals(p0.getLifeGainedThisTurn(), 0, "setup life is board state, not life gained");
        assertEquals(p0.getLifeGainedTimesThisTurn(), 0,
                "setup life must not consume the first life gain of the turn");
        assertEquals(p0.getLifeGainedByTeamThisTurn(), 0, "setup life is not a team life gain");
        assertEquals(p0.getLifeLostThisTurn(), 0, "setup life is not life lost");
        assertEquals(p0.getLifeStartedThisTurnWith(), p0.getLife(),
                "the turn starts with the setup life total");
    }

    @Test
    public void laterCountersAndLifeGainsStillCount() {
        StepMachine m = placed();
        Game game = m.game;
        Player p0 = m.seats[0];
        Card safewright = named(p0, "Creakwood Safewright");

        SetupBuilder.addCounters(game, p0, safewright, Map.of("P1P1", 1));
        assertEquals(game.getCounterAddedThisTurn(null, safewright), 1,
                "a counter put after setup still counts for this turn");

        assertTrue(p0.gainLife(3, null, null));
        assertEquals(p0.getLifeGainedThisTurn(), 3);
        assertEquals(p0.getLifeGainedTimesThisTurn(), 1);
        assertTrue(p0.gainLife(2, null, null));
        assertEquals(p0.getLifeGainedThisTurn(), 5);
        assertEquals(p0.getLifeGainedTimesThisTurn(), 2);
    }
}
