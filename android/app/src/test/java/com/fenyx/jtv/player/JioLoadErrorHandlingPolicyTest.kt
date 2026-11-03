package com.fenyx.jtv.player

import com.fenyx.jtv.player.JioLoadErrorHandlingPolicy.Companion.retryInsteadOfFallback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// android.net.Uri is a null stub on the JVM, so a real DataSpec / InvalidResponseCodeException can't be
// built here; this pins the decision getFallbackSelectionFor makes.
class JioLoadErrorHandlingPolicyTest {

    @Test fun tokenErrorsWithinLimitRetryInsteadOfExcludingTheRendition() {
        for (code in listOf(403, 404, 410)) for (n in 1..4) {
            assertTrue("$code #$n", retryInsteadOfFallback(code, n, limit = 4))
        }
    }

    @Test fun pastTheLimitFallsBackToTheDefault() {
        assertFalse(retryInsteadOfFallback(403, 5, limit = 4))
    }

    @Test fun otherErrorsKeepTheDefault() {
        for (code in listOf(400, 416, 500, 503)) assertFalse("$code", retryInsteadOfFallback(code, 1, limit = 4))
    }
}
