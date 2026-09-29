package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactMatchPolicyTest {
    @Test
    fun exactIdentityWinsWithinSameApp() {
        val c = Contact(
            id = "qq-1",
            name = "张三",
            identities = listOf(PlatformIdentity("com.tencent.mobileqq", "张三"))
        )
        assertEquals(c, KbStore.findContactForApp(listOf(c), "张三", "com.tencent.mobileqq"))
    }

    @Test
    fun sameNameInAnotherAppDoesNotCrossBind() {
        val qq = Contact(
            id = "qq-1",
            name = "张三",
            apps = listOf("com.tencent.mobileqq"),
            identities = listOf(PlatformIdentity("com.tencent.mobileqq", "张三"))
        )
        assertNull(KbStore.findContactForApp(listOf(qq), "张三", "com.twitter.android"))
    }

    @Test
    fun legacySameAppNameStillMatches() {
        val old = Contact(id = "old-1", name = "李四", apps = listOf("com.ss.android.lark"))
        assertEquals(old, KbStore.findContactForApp(listOf(old), "李四", "com.ss.android.lark"))
    }
}
