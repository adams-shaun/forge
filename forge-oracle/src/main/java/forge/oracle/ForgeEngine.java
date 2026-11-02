// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import forge.util.MyRandom;

/** The real engine behind ScenarioReplay: a fresh Match and Game per attempt,
 * with MyRandom seeded from the scenario id so a replay is byte-identical
 * apart from ms (DESIGN 6.1). */
public final class ForgeEngine implements ScenarioReplay.Engine {
    @Override
    public ResultRow replayOnce(Request req, boolean strict) {
        MyRandom.setRandom(new ForcedFlips(seed(req.id), req.forcedFlips()));
        return new StepMachine(req, strict).run();
    }

    /** A coin flip is Forge's only use of nextBoolean, so the recorded game's
     * outcomes go there, in order; once they run out the flips are Forge's. */
    static final class ForcedFlips extends Random {
        private final ArrayDeque<Boolean> wins;

        ForcedFlips(long seed, List<Boolean> wins) {
            super(seed);
            this.wins = new ArrayDeque<>(wins);
        }

        @Override
        public boolean nextBoolean() {
            Boolean w = wins == null ? null : wins.poll();
            return w != null ? w : super.nextBoolean();
        }
    }

    public static long seed(String id) {
        CRC32 crc = new CRC32();
        crc.update((id == null ? "" : id).getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
