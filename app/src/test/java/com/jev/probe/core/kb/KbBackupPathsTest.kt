package com.jev.probe.core.kb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KbBackupPathsTest {
    @Test fun acceptsOnlyKnowledgeBaseFiles() {
        listOf(
            "notes.json",
            "contacts.json",
            "contact_relations.json",
            "logs/0a12bc34de56.json",
            "logs/0a12bc34de56.screen.json",
            "relations/0a12bc34de56.json",
            "contacts.json.corrupt.1727000000000",
            "logs/0a12bc34de56.json.corrupt.1727000000000"
        ).forEach { assertTrue("expected allowlisted: $it", KbBackupPaths.isAllowed(it)) }
    }

    @Test fun rejectsTraversalAbsoluteAndUnexpectedFiles() {
        listOf(
            "../secrets.json",
            "logs/../../secrets.json",
            "/notes.json",
            "logs\\notes.json",
            "logs/%2e%2e/notes.json",
            "settings.xml",
            "logs/anyone.json.bak",
            "notes.json.corrupt.not-a-timestamp",
            "logs/.hidden.json",
            "logs/a/b.json"
        ).forEach { assertFalse("expected rejection: $it", KbBackupPaths.isAllowed(it)) }
    }

    @Test fun quarantinedFilesAreRecognizedOnlyByTheirSuffix() {
        assertTrue(KbBackupPaths.isQuarantined("logs/person.json.corrupt.123"))
        assertFalse(KbBackupPaths.isQuarantined("logs/person.json"))
    }
}
