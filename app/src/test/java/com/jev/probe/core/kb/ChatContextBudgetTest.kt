package com.jev.probe.core.kb

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextBudgetTest {
    @Test
    fun backgroundHasHardCharacterCap() {
        val contact = Contact(
            id = "c1",
            name = "测试联系人",
            notes = "备注".repeat(3000),
            communicationStyle = "沟通".repeat(2000),
            boundaries = "边界".repeat(2000)
        )
        val notes = listOf(
            Note("n1", "常驻", "内容".repeat(3000), alwaysOn = true)
        )
        val ctx = ChatContext(
            contact = contact,
            history = emptyList(),
            notes = notes,
            relationshipGraph = List(20) { "关系$it-" + "说明".repeat(200) }
        )

        assertTrue(ctx.background("朋友").length <= ChatContext.MAX_BACKGROUND_CHARS)
    }
}
