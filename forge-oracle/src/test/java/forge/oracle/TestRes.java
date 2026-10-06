// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.io.File;

/** The fork's forge-gui/res for tests that read a non-card resource (language
 * files, type lists). Never the card database. */
final class TestRes {
    private TestRes() {
    }

    static String res() {
        String r = System.getProperty("forge.oracle.res", "../forge-gui/res");
        if (!new File(r, "lists/TypeLists.txt").isFile()) {
            throw new IllegalStateException("forge.oracle.res=" + r + " is not the fork's forge-gui/res");
        }
        return r;
    }
}
