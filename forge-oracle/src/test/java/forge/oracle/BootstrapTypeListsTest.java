// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

import forge.card.CardType;

/**
 * P0's silent failure mode: without TypeLists.txt Forge drops every subtype
 * (Grizzly Bears becomes a bare creature) and nothing complains. The
 * bootstrap's check must fail before the lists load and pass after. Reads
 * only the type list file, never the card database.
 */
public class BootstrapTypeListsTest {
    @Test
    public void typeListCheckFailsLoudlyUntilTheListsLoad() {
        if (!CardType.isACreatureType("Bear")) {
            try {
                Bootstrap.checkTypeLists();
                fail("checkTypeLists passed with no type lists loaded");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("type lists not loaded"), expected.getMessage());
            }
        }
        Bootstrap.loadTypeLists(TestRes.res() + "/lists/TypeLists.txt");
        Bootstrap.checkTypeLists();
        assertTrue(CardType.isACreatureType("Bear"));
        assertTrue(CardType.isACreatureType("Faerie"));
        assertTrue(CardType.isALandType("Forest"));
    }
}
