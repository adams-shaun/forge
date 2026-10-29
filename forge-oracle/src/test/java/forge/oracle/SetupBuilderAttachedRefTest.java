// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.List;

import org.testng.annotations.Test;

/** Setup "attached" card refs matched against a seat's battlefield list: the
 * bare name is the first placement of that name, #k the k-th (gorge's runner,
 * XMage's placeSetupAttached). No Game. */
public class SetupBuilderAttachedRefTest {
    private static final List<String> BF = List.of("Island", "Plains", "Island", "Roadside Assistance", "Island");

    @Test
    public void bareNameIsTheFirstPlacement() {
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1:Island"), 1);
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1:Plains"), 2);
        assertEquals(SetupBuilder.occurrenceOf(BF, "p0:Roadside Assistance"), 4);
    }

    @Test
    public void ordinalIsTheKthPlacement() {
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1:Island#2"), 3);
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1:Island#3"), 5);
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1:Island#1"), 1);
    }

    @Test
    public void namesNothingWhenTheOccurrenceIsMissing() {
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1:Island#4"), -1);
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1:Swamp"), -1);
        // A token or player ref never names a battlefield card.
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1:token:Pilot Token"), -1);
        assertEquals(SetupBuilder.occurrenceOf(BF, "p1"), -1);
    }

    @Test
    public void rejectsAMalformedRef() {
        try {
            SetupBuilder.occurrenceOf(BF, "Grizzly Bears");
            fail("accepted a ref with no seat");
        } catch (HarnessError expected) {
            assertTrue(expected.getMessage().startsWith("harness: "), expected.getMessage());
        }
    }
}
