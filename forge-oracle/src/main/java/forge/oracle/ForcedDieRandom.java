// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Random;

/**
 * The engine's seeded random source, plus the die results the scenario's tape
 * recorded. Forge's {@code RollDiceEffect.rollAction} draws from
 * {@code MyRandom.getRandom().nextInt(sides)} and exposes no controller hook,
 * so the driver installs this wrapper instead: the step machine arms it with
 * the request's recorded rolls for the step it is about to run, and a draw
 * whose bound is an armed die's sides returns the recorded NATURAL result
 * (the modifier is Forge's own, exactly as the Go request contract says).
 * Every other draw falls through to the seeded generator, so a scenario with
 * no recorded roll is byte-identical to the plain seeded replay.
 *
 * <p>Armed rolls are matched by sides in the order the tape recorded them,
 * which is the order the live engine made them. The seat is informational in
 * the request and is not needed: the engine's own call order is the log
 * order. A step with no recorded roll keeps Forge's own RNG.</p>
 */
public final class ForcedDieRandom extends Random {

    private final Request req;
    private final ArrayDeque<int[]> armed = new ArrayDeque<>();
    private final ArrayDeque<Boolean> flips;
    private int armedStep = Integer.MIN_VALUE;

    public ForcedDieRandom(long seed, Request req) {
        super(seed);
        this.req = req;
        this.flips = new ArrayDeque<>(req.forcedFlips());
    }

    /** A coin flip is Forge's only use of nextBoolean, so the recorded game's
     * outcomes go there, in order; once they run out the flips are Forge's. */
    @Override
    public boolean nextBoolean() {
        Boolean w = flips.poll();
        return w != null ? w : super.nextBoolean();
    }

    /** Arm the rolls the request records for the step about to run. A repeat
     * call for the same step is a no-op: the step loop re-enters the priority
     * callback many times and must not re-queue what it already consumed. */
    public void arm(int step) {
        if (step == armedStep) {
            return;
        }
        armedStep = step;
        armed.clear();
        if (step < 0) {
            return;
        }
        for (int[] r : req.dieRolls(step)) {
            armed.addLast(r);
        }
    }

    @Override
    public int nextInt(int bound) {
        if (bound > 1) {
            for (Iterator<int[]> it = armed.iterator(); it.hasNext();) {
                int[] r = it.next();
                if (r[0] == bound) {
                    it.remove();
                    return r[1] - 1;
                }
            }
        }
        return super.nextInt(bound);
    }
}
