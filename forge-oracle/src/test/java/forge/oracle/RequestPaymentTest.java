// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

/** The Item's payment array: the pool units gorge spent on a step. */
public class RequestPaymentTest {
    @Test
    public void paymentIsPerStepAndEmptyWhenAbsent() {
        Request r = Request.parse("{\"id\":\"x\",\"item\":{\"payment\":[\"\",\"GG\",\"BBR\"],\"steps\":[]},\"gorge_decisions\":[]}");
        assertEquals(r.payment(0), "");
        assertEquals(r.payment(1), "GG");
        assertEquals(r.payment(2), "BBR");
        assertEquals(r.payment(3), "", "past the array");
        assertEquals(r.payment(-1), "", "setup");
        assertEquals(Request.parse("{\"id\":\"x\",\"item\":{\"steps\":[]},\"gorge_decisions\":[]}").payment(0), "", "no array");
    }
}
