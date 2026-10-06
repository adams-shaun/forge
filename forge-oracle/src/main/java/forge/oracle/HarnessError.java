// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

/**
 * The driver could not perform the scenario: a bad ref, an unsupported op, a
 * cast Forge refused, no progress. Never a verdict on the card. The message
 * always starts "harness: ".
 */
public final class HarnessError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public HarnessError(String msg) {
        super(msg.startsWith("harness: ") ? msg : "harness: " + msg);
    }
}
