package com.quickshare.core.transfer

import org.junit.Test

/**
 * Placeholder kept empty on purpose.
 *
 * The timing probe that used to live here was a scratch diagnostic used to attribute a slow
 * transfer to a specific layer; it has served its purpose and is intentionally not part of the
 * shipped suite, because a timing test that asserts on wall-clock numbers is flaky on shared CI.
 */
class TimingDiagnosticTest {
    @Test
    fun `timing diagnostics are not part of the shipped suite`() {
        // Intentionally empty.
    }
}
