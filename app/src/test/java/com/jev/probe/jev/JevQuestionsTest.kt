package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Test

class JevQuestionsTest {
    @Test
    fun rankingQuestionSupportsThreeCandidates() {
        val q = JevQuestions.rankQuestion(listOf("A", "B", "C"))
            .getJSONObject("best_reply")
            .getJSONObject("criteria")
        assertEquals(3, q.length())
        assertEquals("A", q.getString("reply_a"))
        assertEquals("B", q.getString("reply_b"))
        assertEquals("C", q.getString("reply_c"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rankingQuestionStillRejectsTwoCandidates() {
        JevQuestions.rankQuestion(listOf("A", "B"))
    }
}
