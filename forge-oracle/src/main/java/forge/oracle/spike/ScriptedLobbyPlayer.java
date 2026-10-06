package forge.oracle.spike;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/** Lobby seat whose in-game player is driven by a {@link ScriptedController}. */
public class ScriptedLobbyPlayer extends LobbyPlayerAi {
    final Run run;
    final int seat;

    public ScriptedLobbyPlayer(String name, Run run, int seat) {
        super(name, null);
        this.run = run;
        this.seat = seat;
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new ScriptedController(game, p, this, run, seat));
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new ScriptedController(slave.getGame(), slave, this, run, seat);
    }
}
