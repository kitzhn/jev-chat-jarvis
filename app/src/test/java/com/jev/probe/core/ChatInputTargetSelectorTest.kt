package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatInputTargetSelectorTest {
    @Test
    fun prefersKnownComposerIds() {
        val candidates = listOf(
            candidate(0, viewId = "com.tencent.mobileqq:id/search_input", focused = true),
            candidate(1, viewId = "com.tencent.mobileqq:id/input")
        )
        assertEquals(1, ChatInputTargetSelector.select("com.tencent.mobileqq", candidates))
    }

    @Test
    fun prefersFocusedComposerOverUnfocusedSearchField() {
        val candidates = listOf(
            candidate(0, hint = "搜索", focused = false, verticalCenter = 0.12f),
            candidate(1, hint = "输入消息", focused = true, verticalCenter = 0.91f)
        )
        assertEquals(1, ChatInputTargetSelector.select("com.example.chat", candidates))
    }

    @Test
    fun choosesSingleEditableOnlyWhenItIsInComposerArea() {
        assertEquals(
            0,
            ChatInputTargetSelector.select(
                "com.example.chat",
                listOf(candidate(0, verticalCenter = 0.88f))
            )
        )
        assertNull(
            ChatInputTargetSelector.select(
                "com.example.chat",
                listOf(candidate(0, verticalCenter = 0.18f))
            )
        )
    }

    @Test
    fun ambiguousCandidatesFailClosed() {
        val candidates = listOf(
            candidate(0, verticalCenter = 0.88f),
            candidate(1, verticalCenter = 0.88f)
        )
        assertNull(ChatInputTargetSelector.select("com.example.chat", candidates))
    }

    private fun candidate(
        index: Int,
        viewId: String = "",
        hint: String = "",
        focused: Boolean = false,
        verticalCenter: Float? = null
    ) = EditableCandidate(
        index = index,
        viewId = viewId,
        hint = hint,
        contentDescription = "",
        className = "android.widget.EditText",
        editable = true,
        focused = focused,
        verticalCenter = verticalCenter
    )
}
