// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** An OptionalDecider trigger whose may gorge folded into the body's optional
 * Cost$ election gets no logged yes/no (Myr Battlesphere's "you may tap X
 * untapped Myr" with no other Myr: X = 0 is forced, so gorge logs nothing),
 * yet Forge confirms the trigger anyway. Shapes from gorge's decision log. */
public class FoldedOptionalConfirmTest {
    private static DecisionQueue q(String... json) {
        List<JsonObject> raw = new ArrayList<>();
        for (String j : json) {
            raw.add(JsonParser.parseString(j).getAsJsonObject());
        }
        return new DecisionQueue(raw);
    }

    private static final String X_PICK = "{\"step\":6,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"X = 2\"],"
            + "\"pick_refs\":[\"X = 2\"],\"pick_kinds\":[\"x\"],\"gorge_kind\":\"choose\",\"min\":1,\"max\":1}";
    private static final String OTHER_SEAT = X_PICK.replace("\"seat\":0", "\"seat\":1");
    private static final String OTHER_STEP = X_PICK.replace("\"step\":6", "\"step\":9");
    private static final String TAP_PICK = "{\"step\":6,\"seat\":0,\"kind\":\"choose_n\",\"picks\":[\"Myr Token\"],"
            + "\"pick_refs\":[\"p0:Myr Token\"],\"object_picks\":[\"p0:Myr Token\"],"
            + "\"pick_kinds\":[\"trigger_cost_tap\"],\"gorge_kind\":\"choose\"}";

    @Test
    public void battlesphereWithNoOtherMyrConfirmsNo() {
        // gorge logged nothing for the folded may (X = 0 is a forced cost).
        DecisionQueue empty = q();
        assertFalse(StepMachine.foldedPickPending(empty, 0, new int[] {6}));
        assertEquals(StepMachine.foldedConfirm(true, StepMachine.foldedPickPending(empty, 0, new int[] {6})),
                Boolean.FALSE, "a cost-folded trigger with nothing logged declines");
    }

    @Test
    public void battlesphereWithAnXElectionConfirmsYes() {
        DecisionQueue dq = q(X_PICK);
        assertTrue(StepMachine.foldedPickPending(dq, 0, new int[] {6}));
        assertEquals(StepMachine.foldedConfirm(true, true), Boolean.TRUE);
        // A tap pick is already a yes (isYesNo), taken before the fold applies.
        assertTrue(DecisionQueue.isYesNo(q(TAP_PICK).all().get(0)));
    }

    @Test
    public void onlyThisSeatsUnconsumedPickInTheAskedStepsCounts() {
        assertFalse(StepMachine.foldedPickPending(q(OTHER_SEAT), 0, new int[] {6}), "another seat's pick");
        assertFalse(StepMachine.foldedPickPending(q(OTHER_STEP), 0, new int[] {6}), "another step's pick");
        assertTrue(StepMachine.foldedPickPending(q(OTHER_STEP), 0, new int[] {6, 9}), "the borrowed next step");
        DecisionQueue dq = q(X_PICK);
        dq.take(6, DecisionQueue::isX);
        assertFalse(StepMachine.foldedPickPending(dq, 0, new int[] {6}), "a consumed pick");
    }

    @Test
    public void aTriggerWhoseMayIsNotFoldedStillMisses() {
        assertNull(StepMachine.foldedConfirm(false, false), "no cost to fold into: a strict miss, as before");
        assertNull(StepMachine.foldedConfirm(false, true));
    }
}
