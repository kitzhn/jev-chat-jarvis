package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeMutationTransactionTest {
    @Test
    fun successfulMutationDoesNotRollback() {
        val steps = mutableListOf<String>()
        val out = KnowledgeMutationTransaction.execute(
            snapshot = { steps += "snapshot"; "before" },
            mutate = { steps += "mutate"; true },
            rollback = { steps += "rollback"; true }
        )
        assertTrue(out.snapshotSucceeded)
        assertTrue(out.committed)
        assertFalse(out.rollbackAttempted)
        assertEquals(listOf("snapshot", "mutate"), steps)
    }

    @Test
    fun failedMutationRestoresTheSnapshot() {
        val steps = mutableListOf<String>()
        var restored: String? = null
        val out = KnowledgeMutationTransaction.execute(
            snapshot = { steps += "snapshot"; "before" },
            mutate = { steps += "mutate"; false },
            rollback = {
                steps += "rollback"
                restored = it
                true
            }
        )
        assertFalse(out.committed)
        assertTrue(out.rollbackAttempted)
        assertTrue(out.rollbackSucceeded)
        assertEquals("before", restored)
        assertEquals(listOf("snapshot", "mutate", "rollback"), steps)
    }

    @Test
    fun thrownMutationAlsoRollsBack() {
        val out = KnowledgeMutationTransaction.execute(
            snapshot = { "before" },
            mutate = { throw IllegalStateException("disk failure") },
            rollback = { true }
        )
        assertFalse(out.committed)
        assertTrue(out.rollbackAttempted)
        assertTrue(out.rollbackSucceeded)
    }

    @Test
    fun snapshotFailureNeverStartsMutation() {
        var mutated = false
        val out = KnowledgeMutationTransaction.execute<String>(
            snapshot = { throw IllegalStateException("unreadable KB") },
            mutate = { mutated = true; true },
            rollback = { true }
        )
        assertFalse(out.snapshotSucceeded)
        assertFalse(out.committed)
        assertFalse(out.rollbackAttempted)
        assertFalse(mutated)
    }

    @Test
    fun rollbackFailureIsExposed() {
        val out = KnowledgeMutationTransaction.execute(
            snapshot = { "before" },
            mutate = { false },
            rollback = { false }
        )
        assertFalse(out.committed)
        assertTrue(out.rollbackAttempted)
        assertFalse(out.rollbackSucceeded)
    }
}
