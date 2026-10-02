package com.fenyx.jtv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    private fun newer(remote: String, current: String) = AppUpdateManager.isNewerVersion(remote, current)

    @Test fun stableUsersNeverDowngradeFromBeta() = assertFalse(newer("1.5.7", "2.0-beta"))
    @Test fun stableOfSameLineBeatsBeta() = assertTrue(newer("2.0.0", "2.0-beta"))
    @Test fun laterBetaBeatsEarlierBeta() = assertTrue(newer("2.0-beta.2", "2.0-beta"))
    @Test fun betaIsNotNewerThanItsStable() = assertFalse(newer("2.0-beta.3", "2.0"))
    @Test fun betaSuffixDoesNotLookLikePatch() = assertFalse(newer("2.0-beta.2", "2.0.1"))
    @Test fun normalStableBumps() = assertTrue(newer("1.5.8", "1.5.7"))
}
