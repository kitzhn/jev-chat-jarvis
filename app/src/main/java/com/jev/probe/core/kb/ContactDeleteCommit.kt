package com.jev.probe.core.kb

/** Coordinates the multi-file part of deletion without removing history early. */
internal object ContactDeleteCommit {
    fun execute(
        writeContacts: () -> Boolean,
        writeRelations: () -> Boolean,
        restoreContacts: () -> Boolean,
        deleteHistory: () -> Boolean
    ): Boolean {
        if (!writeContacts()) return false
        if (!writeRelations()) {
            restoreContacts()
            return false
        }
        return deleteHistory()
    }
}
