package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Test

class JevQuestionsTest {
    @Test
    fun rankingKeysSupportThreeCandidatesWithoutAndroidJson() {
        assertEquals(
            listOf("reply_a", "reply_b", "reply_c"),
            JevQuestions.rankKeys(3)
        )
    }

    @Test
    fun rankingKeysSupportFourCandidates() {
        assertEquals(
            listOf("reply_a", "reply_b", "reply_c", "reply_d"),
            JevQuestions.rankKeys(4)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rankingKeysRejectTwoCandidates() {
        JevQuestions.rankKeys(2)
    }
}
