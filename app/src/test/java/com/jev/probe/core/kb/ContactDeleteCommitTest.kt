package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDeleteCommitTest {
    @Test
    fun failedContactWriteNeverDeletesHistory() {
        val steps = mutableListOf<String>()
        val completed = ContactDeleteCommit.execute(
            writeContacts = { steps += "contacts"; false },
            writeRelations = { steps += "relations"; true },
            restoreContacts = { steps += "restore"; true },
            deleteHistory = { steps += "history"; true }
        )

        assertFalse(completed)
        assertEquals(listOf("contacts"), steps)
    }

    @Test
    fun failedRelationWriteAttemptsRollbackAndPreservesHistoryIfRollbackFails() {
        val steps = mutableListOf<String>()
        val completed = ContactDeleteCommit.execute(
            writeContacts = { steps += "contacts"; true },
            writeRelations = { steps += "relations"; false },
            restoreContacts = { steps += "restore"; false },
            deleteHistory = { steps += "history"; true }
        )

        assertFalse(completed)
        assertEquals(listOf("contacts", "relations", "restore"), steps)
    }

    @Test
    fun historyIsDeletedOnlyAfterBothIndexesCommit() {
        val steps = mutableListOf<String>()
        val completed = ContactDeleteCommit.execute(
            writeContacts = { steps += "contacts"; true },
            writeRelations = { steps += "relations"; true },
            restoreContacts = { steps += "restore"; true },
            deleteHistory = { steps += "history"; true }
        )

        assertTrue(completed)
        assertEquals(listOf("contacts", "relations", "history"), steps)
    }

    @Test
    fun historyCleanupFailureIsReported() {
        val steps = mutableListOf<String>()
        val completed = ContactDeleteCommit.execute(
            writeContacts = { steps += "contacts"; true },
            writeRelations = { steps += "relations"; true },
            restoreContacts = { steps += "restore"; true },
            deleteHistory = { steps += "history"; false }
        )

        assertFalse(completed)
        assertEquals(listOf("contacts", "relations", "history"), steps)
    }
}
