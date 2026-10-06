// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/** A lobby seat whose in-game player is driven by a {@link ScriptedController}. */
public class ScriptedLobbyPlayer extends LobbyPlayerAi {
    final StepMachine m;
    final int seat;

    public ScriptedLobbyPlayer(String name, StepMachine m, int seat) {
        super(name, null);
        this.m = m;
        this.seat = seat;
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new ScriptedController(game, p, this, m, seat));
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new ScriptedController(slave.getGame(), slave, this, m, seat);
    }
}
