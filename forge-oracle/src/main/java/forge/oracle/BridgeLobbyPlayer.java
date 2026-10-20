// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * A lobby seat whose in-game controller is a {@link BridgeController}: the
 * player is played by gorge's bot over a stdio bridge instead of by Forge's
 * AI.
 *
 * The bridge process is started here, once per seat, and closed by the caller
 * after the game ({@link BridgeController#close}).
 */
public final class BridgeLobbyPlayer extends LobbyPlayerAi {
    private final String binary;
    private final long seed;
    private final int seat;
    private Process bridge;
    private BridgeController controller;

    public BridgeLobbyPlayer(String name, int seat, String binary, long seed) {
        super(name, null);
        this.seat = seat;
        this.binary = binary;
        this.seed = seed;
    }

    /** The controller installed for the game, or null before one exists. */
    public BridgeController controller() {
        return controller;
    }

    private PlayerController make(Game game, Player p) {
        try {
            if (bridge == null) {
                bridge = BridgeController.start(binary, seed);
            }
            controller = new BridgeController(game, p, this, seat, bridge);
            return controller;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot start the bridge " + binary + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(make(game, p));
        if (System.getenv("BRIDGE_DEBUG") != null) {
            System.err.println("BRIDGE seat " + seat + " controller " + p.getController().getClass().getName());
        }
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return make(slave.getGame(), slave);
    }

    /** Closes the bridge process. Safe to call more than once. */
    public void close() {
        BridgeController.close(bridge);
        bridge = null;
    }
}
