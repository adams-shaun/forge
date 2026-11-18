// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.Random;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * A lobby seat whose in-game controller is a {@link RecordingController}
 * writing to the game's {@link ShadowLog}. A game the log does not record (an
 * AI simulation's copy) gets a plain AI controller, as does a Mindslaver-style
 * second controller: those decisions are not part of the log.
 */
public final class RecordingLobbyPlayer extends LobbyPlayerAi {
    private final ShadowLog log;
    private final int seat;
    private final long randomSeed;
    private final boolean random;

    /** {@code random} makes the seat answer its listed options with a
     * {@code new Random(randomSeed)} stream. */
    public RecordingLobbyPlayer(String name, int seat, ShadowLog log, boolean random, long randomSeed) {
        super(name, null);
        this.log = log;
        this.seat = seat;
        this.random = random;
        this.randomSeed = randomSeed;
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player p = new Player(getName(), game, id);
        if (log.records(game)) {
            p.setFirstController(new RecordingController(game, p, this, log, seat,
                    random ? new Random(randomSeed) : null));
        } else {
            p.setFirstController(new PlayerControllerAi(game, p, this));
        }
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new PlayerControllerAi(slave.getGame(), slave, this);
    }
}
