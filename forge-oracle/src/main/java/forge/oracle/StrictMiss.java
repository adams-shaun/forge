// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

/**
 * Forge asked a question the scenario's script (gorge's decision log) does
 * not answer, in strict mode. ScenarioReplay retries the scenario loose and
 * records the message as the row's strict_miss, as the XMage driver does
 * (ScenarioReplay.java:534-560). Messages start "Missing " so a reader can
 * grep for them.
 */
public final class StrictMiss extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StrictMiss(String what, int step, String detail) {
        super("Missing " + what + " answer at step " + step + (detail == null || detail.isEmpty() ? "" : ": " + detail));
    }
}
