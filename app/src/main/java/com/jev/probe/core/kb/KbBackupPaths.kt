package com.jev.probe.core.kb

/** Strict allowlist for files that may enter a knowledge-base backup archive. */
internal object KbBackupPaths {
    const val MAX_FILE_BYTES = 16L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L
    const val MAX_FILE_COUNT = 4096

    private val rootFiles = setOf("notes.json", "contacts.json", "contact_relations.json")
    private val contactFile = Regex("[A-Za-z0-9_-]{1,80}(?:\\.screen)?\\.json(?:\\.corrupt\\.[0-9]{1,16})?")
    private val corruptStamp = Regex("[0-9]{1,16}")

    fun isAllowed(relativePath: String): Boolean {
        if (relativePath.isBlank() || relativePath.length > 240 ||
            relativePath.startsWith('/') || '\\' in relativePath || '\u0000' in relativePath) {
            return false
        }
        val parts = relativePath.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return when (parts.size) {
            1 -> {
                val name = parts[0]
                name in rootFiles || rootFiles.any { base ->
                    name.startsWith("$base.corrupt.") &&
                        corruptStamp.matches(name.removePrefix("$base.corrupt."))
                }
            }
            2 -> parts[0] in setOf("logs", "relations") && contactFile.matches(parts[1])
            else -> false
        }
    }

    fun isQuarantined(relativePath: String): Boolean =
        relativePath.substringAfterLast('/').contains(".corrupt.")
}
