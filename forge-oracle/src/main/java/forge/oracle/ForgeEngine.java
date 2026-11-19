// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import forge.util.MyRandom;

/** The real engine behind ScenarioReplay: a fresh Match and Game per attempt,
 * with MyRandom seeded from the scenario id so a replay is byte-identical
 * apart from ms (DESIGN 6.1). */
public final class ForgeEngine implements ScenarioReplay.Engine {
    @Override
    public ResultRow replayOnce(Request req, boolean strict) {
        // The same seeded generator, plus the tape's die results and coin
        // flips: the step machine arms the dice per step, so a card whose
        // effect rolls a die (Ebony Fly's d6) gets the live game's natural
        // roll instead of its own, and the recorded flips come out in order.
        ForcedDieRandom random = new ForcedDieRandom(seed(req.id), req);
        MyRandom.setRandom(random);
        return new StepMachine(req, strict, random).run();
    }

    public static long seed(String id) {
        CRC32 crc = new CRC32();
        crc.update((id == null ? "" : id).getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
