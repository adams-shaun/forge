// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;

/**
 * The cast step's cast_mode, read without a card database: which optional
 * cost or keyword cost a mode elects. Which spell ability a mode selects
 * (plot, adventure, omen, modal back face, warp) needs Forge's card scripts
 * and is covered by fixtures/castmode.
 */
public class CastModeTest {

    private static OptionalCostValue value(OptionalCost t) {
        return new OptionalCostValue(t, null);
    }

    @Test
    public void kickedElectsEitherKicker() {
        assertTrue(StepMachine.modeElects("kicked", value(OptionalCost.Kicker1)));
        assertTrue(StepMachine.modeElects("kicked", value(OptionalCost.Kicker2)));
        assertFalse(StepMachine.modeElects("kicked", value(OptionalCost.Buyback)));
    }

    @Test
    public void kickedOneAndTwoNameTheirKicker() {
        assertTrue(StepMachine.modeElects("kicked1", value(OptionalCost.Kicker1)));
        assertFalse(StepMachine.modeElects("kicked1", value(OptionalCost.Kicker2)));
        assertTrue(StepMachine.modeElects("kicked2", value(OptionalCost.Kicker2)));
    }

    @Test
    public void ordinaryCastAndOtherModesElectNothing() {
        for (String m : new String[] {"", "plot", "adventure_alt", "offspring", "optionalcost"}) {
            assertFalse(StepMachine.modeElects(m, value(OptionalCost.Kicker1)), m);
            assertFalse(StepMachine.modeElects(m, value(OptionalCost.Buyback)), m);
        }
    }

    @Test
    public void keywordCostPaidOnceWhenTheModeNamesIt() {
        assertEquals(StepMachine.modeKeywordTimes("offspring", "OFFSPRING"), 1);
        assertEquals(StepMachine.modeKeywordTimes("offspring", "REPLICATE"), 0);
        assertEquals(StepMachine.modeKeywordTimes("replicated", "REPLICATE"), 1);
        assertEquals(StepMachine.modeKeywordTimes("", "OFFSPRING"), 0);
        assertEquals(StepMachine.modeKeywordTimes("kicked", "OFFSPRING"), 0);
    }
}
