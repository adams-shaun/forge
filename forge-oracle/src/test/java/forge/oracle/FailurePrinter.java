// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import org.testng.ITestListener;
import org.testng.ITestResult;

/** Prints each test's outcome, and a failure's message and first frames,
 * since run-unit-tests.sh turns TestNG's report-writing listeners off. */
public class FailurePrinter implements ITestListener {
    @Override
    public void onTestSuccess(ITestResult r) {
        System.out.println("PASS " + name(r));
    }

    @Override
    public void onTestFailure(ITestResult r) {
        System.out.println("FAIL " + name(r) + ": " + r.getThrowable());
        StackTraceElement[] st = r.getThrowable() == null ? new StackTraceElement[0] : r.getThrowable().getStackTrace();
        for (int i = 0; i < Math.min(6, st.length); i++) {
            System.out.println("    at " + st[i]);
        }
    }

    @Override
    public void onTestSkipped(ITestResult r) {
        System.out.println("SKIP " + name(r));
    }

    private static String name(ITestResult r) {
        return r.getTestClass().getRealClass().getSimpleName() + "." + r.getMethod().getMethodName();
    }
}
