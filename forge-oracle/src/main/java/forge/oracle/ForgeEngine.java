// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;

import forge.util.MyRandom;

/** The real engine behind ScenarioReplay: a fresh Match and Game per attempt,
 * with MyRandom seeded from the scenario id so a replay is byte-identical
 * apart from ms (DESIGN 6.1). The scenario run itself lands with P1-2..P1-4. */
public final class ForgeEngine implements ScenarioReplay.Engine {
    @Override
    public ResultRow replayOnce(Request req, boolean strict) {
        MyRandom.setRandom(new Random(seed(req.id)));
        throw new HarnessError("the scenario run is not implemented yet (P1-2..P1-4)");
    }

    public static long seed(String id) {
        CRC32 crc = new CRC32();
        crc.update((id == null ? "" : id).getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
