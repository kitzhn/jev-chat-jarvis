package com.jev.probe.core.kb

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextBudgetTest {
    @Test
    fun finalBackgroundIsHardCapped() {
        val note = Note("n", "常驻", "x".repeat(10000), alwaysOn = true)
        val ctx = ChatContext(
            contact = Contact(id = "c", name = "联系人", notes = "y".repeat(10000)),
            history = emptyList(),
            notes = listOf(note)
        )
        val background = ctx.background("朋友")
        assertTrue(background.length <= ChatContext.MAX_BACKGROUND_CHARS + 20)
        assertTrue(background.contains("背景已按长度上限截断"))
    }
}
