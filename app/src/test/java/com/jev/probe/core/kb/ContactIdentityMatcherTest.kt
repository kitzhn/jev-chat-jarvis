package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactIdentityMatcherTest {
    private fun norm(value: String?): String = KbStore.normalizeName(value)

    @Test
    fun exactIdentityWinsWithinTheCurrentApp() {
        val qq = Contact(
            id = "qq-zhang",
            name = "张三",
            identities = listOf(PlatformIdentity("com.tencent.mobileqq", "张三"))
        )
        val x = Contact(
            id = "x-zhang",
            name = "张三",
            identities = listOf(PlatformIdentity("com.twitter.android", "张三"))
        )

        assertEquals(
            "x-zhang",
            ContactIdentityMatcher.findConversationContact(
                listOf(qq, x), "张三", "com.twitter.android", ::norm
            )?.id
        )
    }

    @Test
    fun sameNameOnAnotherAppDoesNotCrossLink() {
        val qq = Contact(
            id = "qq-zhang",
            name = "张三",
            apps = listOf("com.tencent.mobileqq"),
            identities = listOf(PlatformIdentity("com.tencent.mobileqq", "张三"))
        )

        assertNull(
            ContactIdentityMatcher.findConversationContact(
                listOf(qq), "张三", "com.twitter.android", ::norm
            )
        )
    }

    @Test
    fun legacySameAppRecordStillMatchesWithoutIdentity() {
        val legacy = Contact(
            id = "legacy",
            name = "李雷",
            aliases = listOf("小雷"),
            apps = listOf("com.tencent.mobileqq")
        )

        assertEquals(
            "legacy",
            ContactIdentityMatcher.findConversationContact(
                listOf(legacy), "小雷", "com.tencent.mobileqq", ::norm
            )?.id
        )
    }

    @Test
    fun blankAppMayUseManualGlobalNameLookup() {
        val c = Contact(id = "manual", name = "王五", aliases = listOf("老王"))
        assertEquals(
            "manual",
            ContactIdentityMatcher.findConversationContact(
                listOf(c), "老王", "", ::norm
            )?.id
        )
    }
}
