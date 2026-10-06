// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.util.Localizer;

/** Snapshot normalization from plain values, with no Game (P1-3). */
public class SnapshotWriterTest {
    /** gorge's step vocabulary, state/ids.go stepNames. */
    static final Set<String> GORGE_STEPS = Set.of("untap", "upkeep", "draw", "main1", "begin-combat",
            "declare-attackers", "declare-blockers", "combat-damage", "end-combat", "main2", "end", "cleanup");

    /** PhaseType's constructor localizes its UI name: the language files
     * (not the card database) must be loaded. */
    @BeforeClass
    public void localizer() {
        Localizer.getInstance().initialize("en-US", TestRes.res() + "/languages/");
    }

    @Test
    public void everyPhaseMapsToAGorgeStep() {
        Set<String> seen = new HashSet<>();
        for (PhaseType pt : PhaseType.values()) {
            String s = SnapshotWriter.stepName(pt);
            assertTrue(GORGE_STEPS.contains(s), pt + " -> " + s + " is not a gorge step name");
            seen.add(s);
        }
        assertEquals(seen, GORGE_STEPS, "every gorge step is reachable");
        assertEquals(SnapshotWriter.stepName(PhaseType.MAIN1), "main1");
        assertEquals(SnapshotWriter.stepName(PhaseType.COMBAT_FIRST_STRIKE_DAMAGE), "combat-damage");
        assertEquals(SnapshotWriter.stepName(PhaseType.COMBAT_DAMAGE), "combat-damage");
        assertEquals(SnapshotWriter.stepName(PhaseType.END_OF_TURN), "end");
        assertEquals(SnapshotWriter.stepName(null), "");
    }

    @Test
    public void colorsAreWubrgLetters() {
        assertEquals(SnapshotWriter.colors(false, false, false, false, false), "");
        assertEquals(SnapshotWriter.colors(true, true, true, true, true), "WUBRG");
        assertEquals(SnapshotWriter.colors(false, false, true, false, true), "BG");
        assertEquals(SnapshotWriter.colors(true, false, false, true, false), "WR");
    }

    @Test
    public void poolIsWubrgcLetters() {
        assertEquals(SnapshotWriter.pool(new int[] {0, 0, 0, 0, 0, 0}), "");
        assertEquals(SnapshotWriter.pool(new int[] {0, 0, 0, 1, 0, 0}), "R");
        assertEquals(SnapshotWriter.pool(new int[] {1, 2, 0, 0, 1, 3}), "WUUGCCC");
    }

    @Test
    public void countersUseGorgesKinds() {
        assertEquals(SnapshotWriter.counterName(CounterEnumType.P1P1), "P1P1");
        assertEquals(SnapshotWriter.counterName(CounterEnumType.M1M1), "M1M1");
        // Forge's display names are "+1/+1", "Loyalty", "Charge"; gorge's are the kinds.
        assertEquals(SnapshotWriter.counterName(CounterEnumType.LOYALTY), "LOYALTY");
        assertEquals(SnapshotWriter.counterName(CounterEnumType.CHARGE), "CHARGE");
        assertEquals(SnapshotWriter.counterName(CounterEnumType.LORE), "LORE");
        assertEquals(SnapshotWriter.counterName(CounterEnumType.POISON), "POISON");
        // Keyword and custom counters: upper-cased, spaces removed (the comparator's normCounter).
        assertEquals(SnapshotWriter.counterName(null, "First Strike"), "FIRSTSTRIKE");
        assertEquals(SnapshotWriter.counterName(null, "+1/+1"), "P1P1");
        assertEquals(SnapshotWriter.counterName(null, "-1/-1"), "M1M1");
        assertEquals(SnapshotWriter.counterName(null, "flying"), "FLYING");
    }

    @Test
    public void typesAreOneSortedList() {
        List<String> t = SnapshotWriter.sortedTypes(List.of("Creature", "Artifact"), List.of("Legendary"), List.of("Golem", "Bear"));
        assertEquals(t, List.of("Artifact", "Bear", "Creature", "Golem", "Legendary"));
        assertFalse(SnapshotWriter.sortedTypes(List.of(), List.of(), List.of()).iterator().hasNext());
    }
}
