package com.jev.probe.core.kb

/** Pure coordinator for multi-file knowledge-base mutations. */
internal object KnowledgeMutationTransaction {
    data class Outcome(
        val snapshotSucceeded: Boolean,
        val committed: Boolean,
        val rollbackAttempted: Boolean,
        val rollbackSucceeded: Boolean
    )

    fun <T> execute(
        snapshot: () -> T,
        mutate: () -> Boolean,
        rollback: (T) -> Boolean
    ): Outcome {
        val before = try {
            snapshot()
        } catch (_: Exception) {
            return Outcome(
                snapshotSucceeded = false,
                committed = false,
                rollbackAttempted = false,
                rollbackSucceeded = false
            )
        }

        val committed = try {
            mutate()
        } catch (_: Exception) {
            false
        }
        if (committed) {
            return Outcome(
                snapshotSucceeded = true,
                committed = true,
                rollbackAttempted = false,
                rollbackSucceeded = true
            )
        }

        val rolledBack = try {
            rollback(before)
        } catch (_: Exception) {
            false
        }
        return Outcome(
            snapshotSucceeded = true,
            committed = false,
            rollbackAttempted = true,
            rollbackSucceeded = rolledBack
        )
    }
}
