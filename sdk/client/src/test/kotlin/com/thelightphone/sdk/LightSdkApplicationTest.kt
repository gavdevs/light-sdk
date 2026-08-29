package com.thelightphone.sdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightSdkApplicationTest {
    @Test
    fun selfHostedServerDoesNotRegisterPushWithItself() {
        assertFalse(shouldRegisterPush("com.example.tool", "com.example.tool"))
    }

    @Test
    fun externalLightServerStillReceivesPushRegistration() {
        assertTrue(shouldRegisterPush("com.example.tool", "com.lightos"))
    }
}
