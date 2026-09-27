package com.jev.probe.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSafetyTest {
    @Test
    fun emptyOrBlankAllowlistDeniesEveryConversation() {
        assertFalse(ConversationWhitelistGate.allows("演示群", emptySet()))
        assertFalse(ConversationWhitelistGate.allows("演示群", setOf("", "  ")))
        assertFalse(ConversationWhitelistGate.allows(null, setOf("演示群")))
        assertFalse(ConversationWhitelistGate.allows("   ", setOf("演示群")))
    }

    @Test
    fun fullTitleMatchesAfterRemovingGroupCountSuffix() {
        assertTrue(ConversationWhitelistGate.allows("演示旅行群（3）", setOf("演示旅行群")))
        assertTrue(ConversationWhitelistGate.allows("DEV TEAM", setOf("dev team")))
        assertTrue(ConversationWhitelistGate.allows("家庭群 ( 3 )", setOf("家庭群")))
    }

    @Test
    fun partialTitleDoesNotAuthorizeAnotherConversation() {
        assertFalse(ConversationWhitelistGate.allows("演示旅行群", setOf("旅行群")))
        assertFalse(ConversationWhitelistGate.allows("家庭群工作讨论", setOf("家庭群")))
        assertFalse(ConversationWhitelistGate.allows("家庭群", setOf("旅行群")))
    }

    @Test
    fun broadMatchingRequiresAnExplicitWildcard() {
        assertTrue(ConversationWhitelistGate.allows("家庭群工作讨论", setOf("家庭群*")))
        assertFalse(ConversationWhitelistGate.allows("家庭群工作讨论", setOf("家庭群")))
        assertFalse(ConversationWhitelistGate.allows("家庭群", setOf("*")))
    }

    @Test
    fun queuedFillRequiresSamePackageTitleAndGeneration() {
        val expected = CaptureTarget("com.tencent.mobileqq", "演示旅行群（3）", 12)
        val allowlist = setOf("演示旅行群")

        assertTrue(CaptureTargetGate.isCurrent(expected, expected, allowlist))
        assertFalse(
            CaptureTargetGate.isCurrent(
                expected, expected.copy(packageName = "com.tencent.mm"), allowlist
            )
        )
        assertFalse(
            CaptureTargetGate.isCurrent(
                expected, expected.copy(title = "另一个会话"), allowlist
            )
        )
        assertFalse(
            CaptureTargetGate.isCurrent(
                expected, expected.copy(generation = 13), allowlist
            )
        )
        assertFalse(CaptureTargetGate.isCurrent(expected, expected, emptySet()))
    }
}
