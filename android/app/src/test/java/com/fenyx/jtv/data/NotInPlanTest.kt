package com.fenyx.jtv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotInPlanTest {

    @Test
    fun code3012IsNotInPlan() {
        assertTrue(JioApiClient.isNotInPlanBody("""{"code":3012,"message":"No eligible plans found"}"""))
        assertTrue(JioApiClient.isNotInPlanBody("""{"code": "3012"}"""))
        assertTrue(JioApiClient.isNotInPlanBody("No eligible plans found"))
    }

    @Test
    fun otherRefusalsAreNot() {
        assertFalse(JioApiClient.isNotInPlanBody(""))
        assertFalse(JioApiClient.isNotInPlanBody("""{"code":30120,"message":"Forbidden"}"""))
        assertFalse(JioApiClient.isNotInPlanBody("""{"code":403,"message":"Sony Srno data is not mapped"}"""))
    }
}
