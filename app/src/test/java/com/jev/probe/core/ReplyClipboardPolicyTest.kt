package com.jev.probe.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyClipboardPolicyTest {
    @Test
    fun clearsOnlyTheReplyClipStillOwnedByThisOperation() {
        assertTrue(ReplyClipboardPolicy.owns("jev_reply", "候选回复", "候选回复"))
        assertFalse(ReplyClipboardPolicy.owns("user_clip", "候选回复", "候选回复"))
        assertFalse(ReplyClipboardPolicy.owns("jev_reply", "用户刚复制的内容", "候选回复"))
        assertFalse(ReplyClipboardPolicy.owns(null, "候选回复", "候选回复"))
    }
}
