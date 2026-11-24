// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.player.PlayerActionConfirmMode;

/**
 * The behavior half of the OriginAlternative$ mapping: a real
 * ScriptedController answers Forge's initial "search the library?" confirm
 * and each per-zone confirm from gorge's one search_zones election, and a
 * declined optional search leaves the search_confirm for the later
 * ChangeZoneGeneral gate. The classification helpers are covered separately
 * in DecisionQueueTest; this drives them through confirmAction.
 */
public class AltSourceAnswerTest {
    private static final String SEARCH_LIBRARY_ONLY = "{\"step\":0,\"seat\":0,\"kind\":\"choose_n\","
            + "\"picks\":[\"Search the library\"],\"pick_refs\":[\"Search the library\"],"
            + "\"pick_kinds\":[\"zone\"],\"resume\":\"search_zones\",\"gorge_kind\":\"choose\","
            + "\"options\":2,\"min\":1,\"max\":2}";
    private static final String SEARCH_BOTH = "{\"step\":0,\"seat\":0,\"kind\":\"choose_n\","
            + "\"picks\":[\"Search the library\",\"Search the graveyard\"],"
            + "\"pick_refs\":[\"Search the library\",\"Search the graveyard\"],"
            + "\"pick_idx\":[0,1],\"pick_kinds\":[\"zone\"],\"resume\":\"search_zones\","
            + "\"gorge_kind\":\"choose\",\"options\":2,\"min\":1,\"max\":2}";
    private static final String SEARCH_DECLINED = "{\"step\":0,\"seat\":0,\"kind\":\"choose_n\","
            + "\"picks\":[\"No\"],\"pick_kinds\":[\"no\"],\"resume\":\"search_confirm\","
            + "\"gorge_kind\":\"choose\",\"min\":1,\"max\":1}";

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static ScriptedController controller(String decision) {
        Request req = Request.parse("{\"id\":\"alt\",\"item\":{\"steps\":[{\"op\":\"pass\"}]},"
                + "\"gorge_decisions\":[" + decision + "]}");
        StepMachine m = new StepMachine(req, true);
        m.stepIdx = 0;
        m.build();
        return (ScriptedController) m.seats[0].getController();
    }

    private static boolean confirm(ScriptedController c, String message) {
        return c.confirmAction(null, PlayerActionConfirmMode.ChangeZoneFromAltSource, message, null, null, null);
    }

    @Test
    public void libraryOnlyElectionAnswersTheInitialAndGraveyardConfirms() {
        ScriptedController c = controller(SEARCH_LIBRARY_ONLY);
        assertTrue(confirm(c, "Would you like to search your library with this ability? If you do, your library will be shuffled."), "the election picked the library");
        assertFalse(confirm(c, "Search your graveyard?"), "the election did not pick the graveyard");
        assertNull(c.m.dq.leftover(), "one election answers the whole effect");
    }

    @Test
    public void bothZonesElectionAnswersEveryConfirm() {
        ScriptedController c = controller(SEARCH_BOTH);
        assertTrue(confirm(c, "Would you like to search your library with this ability? If you do, your library will be shuffled."));
        assertTrue(confirm(c, "Search your graveyard?"));
        assertNull(c.m.dq.leftover());
    }

    @Test
    public void declinedOptionalSearchLeavesTheSearchConfirmForTheLaterGate() {
        ScriptedController c = controller(SEARCH_DECLINED);
        assertFalse(confirm(c, "Would you like to search your library with this ability? If you do, your library will be shuffled."));
        assertFalse(confirm(c, "Search your graveyard?"), "no zone was elected");
        assertTrue(c.m.dq.leftover() != null && c.m.dq.leftover().contains("[no]"),
                "the declined search_confirm waits for its real ask");
        // Forge's Optional$ gate asks ChangeZoneGeneral after the zone confirms.
        assertFalse(c.confirmAction(null, PlayerActionConfirmMode.ChangeZoneGeneral,
                "Would you like to search your library with this ability? If you do, your library will be shuffled.", null, null, null), "the recorded No answers the later gate");
        assertNull(c.m.dq.leftover(), "the search_confirm was consumed by its real ask");
    }
}
