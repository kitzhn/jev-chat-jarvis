package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Note / contact / history counts, for the settings screen. */
data class KbCounts(
    val notes: Int,
    val contacts: Int,
    val logLines: Int,
    val relationEdges: Int = 0
)

/**
 * The knowledge-base store: three kinds of JSON file under `filesDir/kb`.
 *
 *   kb/notes.json           all notes
 *   kb/contacts.json        all contacts
 *   kb/logs/<contactId>.json per-contact chat history (≤ 300 lines)
 *
 * Single writer by construction: every read and write goes through one lock, and
 * writes land via a temp file + rename so a kill mid-write can never leave half
 * a JSON document behind. Serialization is hand-written org.json (no Gson/Moshi
 * dependency). Chat text never reaches logcat — only counts and lengths.
 */
class KbStore private constructor(context: Context) {

    private val app = context.applicationContext
    private val lock = Any()

    private val root: File get() = File(app.filesDir, "kb")
    private val notesFile: File get() = File(root, "notes.json")
    private val contactsFile: File get() = File(root, "contacts.json")
    private val graphFile: File get() = File(root, "contact_relations.json")
    private fun logFile(contactId: String) = File(File(root, "logs"), "$contactId.json")
    private fun screenFile(contactId: String) = File(File(root, "logs"), "$contactId.screen.json")
    private fun relationFile(contactId: String) = File(File(root, "relations"), "$contactId.json")

    private var notesCache: MutableList<Note>? = null
    private var contactsCache: MutableList<Contact>? = null
    private var graphCache: MutableList<ContactRelation>? = null
    private val logCache = HashMap<String, MutableList<LogEntry>>()
    private val relationCache = HashMap<String, MutableList<RelationshipEvent>>()

    /** Per contact: the comparison keys of the last screen written. See [appendLog]. */
    private val lastScreenCache = HashMap<String, List<String>>()

    // ------------------------------------------------------------------ notes

    fun notes(): List<Note> = synchronized(lock) { loadNotes().toList() }

    fun note(id: String): Note? = synchronized(lock) { loadNotes().firstOrNull { it.id == id } }

    /** Insert or replace by id. Returns false when it did not reach disk. */
    fun saveNote(note: Note): Boolean = synchronized(lock) {
        val list = loadNotes()
        val i = list.indexOfFirst { it.id == note.id }
        val stamped = note.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null   // memory must not claim a write that failed
        ok
    }

    fun deleteNote(id: String): Boolean = synchronized(lock) {
        val list = loadNotes()
        if (!list.removeAll { it.id == id }) return@synchronized true
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null
        ok
    }

    // --------------------------------------------------------------- contacts

    fun contacts(): List<Contact> = synchronized(lock) { loadContacts().toList() }

    fun contact(id: String): Contact? = synchronized(lock) { loadContacts().firstOrNull { it.id == id } }

    fun contactRelations(): List<ContactRelation> = synchronized(lock) { loadContactRelations().toList() }

    fun relationsFor(contactId: String): List<ContactRelation> = synchronized(lock) {
        loadContactRelations().filter { it.fromId == contactId || it.toId == contactId }
    }

    fun saveContactRelation(relation: ContactRelation): Boolean = synchronized(lock) {
        if (relation.fromId.isBlank() || relation.toId.isBlank() || relation.fromId == relation.toId) {
            return@synchronized false
        }
        if (loadContacts().none { it.id == relation.fromId } ||
            loadContacts().none { it.id == relation.toId }) {
            return@synchronized false
        }
        val list = loadContactRelations()
        val i = list.indexOfFirst { it.id == relation.id }
        val stamped = relation.copy(
            type = relation.type.trim(),
            strength = relation.strength.coerceIn(0, 100),
            note = relation.note.trim(),
            updatedAt = System.currentTimeMillis()
        )
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(graphFile, contactRelationsJson(list))
        if (!ok) graphCache = null
        ok
    }

    fun deleteContactRelation(id: String): Boolean = synchronized(lock) {
        val list = loadContactRelations()
        if (!list.removeAll { it.id == id }) return@synchronized true
        val ok = writeAtomic(graphFile, contactRelationsJson(list))
        if (!ok) graphCache = null
        ok
    }

    fun relationContext(contactId: String, limit: Int = 8): List<String> = synchronized(lock) {
        val names = loadContacts().associateBy({ it.id }, { it.name })
        loadContactRelations()
            .filter { it.fromId == contactId || it.toId == contactId }
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(0, 20))
            .mapNotNull { edge ->
                val otherId = if (edge.fromId == contactId) edge.toId else edge.fromId
                val otherName = names[otherId]?.ifBlank { null } ?: return@mapNotNull null
                buildString {
                    append(otherName)
                    if (edge.type.isNotBlank()) append("：").append(edge.type)
                    append("（强度 ").append(edge.strength.coerceIn(0, 100)).append("/100）")
                    if (edge.note.isNotBlank()) append("；").append(edge.note)
                }
            }
    }

    fun saveContact(c: Contact): Boolean = synchronized(lock) {
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == c.id }
        val identities = c.identities
            .map { it.copy(
                app = it.app.trim(),
                title = it.title.trim(),
                label = it.label.trim(),
                scope = it.scope.trim()
            ) }
            .filter { it.title.isNotBlank() }
            .distinctBy { it.app + "\u0000" + normalizeName(it.scope) + "\u0000" + normalizeName(it.title) }
        val stamped = c.copy(
            identities = identities,
            affection = AffectionScale.clamp(c.affection),
            trust = AffectionScale.clamp(c.trust),
            closeness = AffectionScale.clamp(c.closeness),
            updatedAt = System.currentTimeMillis()
        )
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(contactsFile, contactsJson(list))
        if (!ok) contactsCache = null
        ok
    }

    fun saveContactWithAffectionEvent(c: Contact, reason: String = "画像编辑"): Boolean = synchronized(lock) {
        val old = loadContacts().firstOrNull { it.id == c.id }
        val from = old?.affection
        val to = AffectionScale.clamp(c.affection)
        val ok = saveContact(c.copy(affection = to))
        if (ok && from != null && from != to) {
            appendRelationshipEvent(c.id, RelationshipEvent(
                id = newId(),
                ts = System.currentTimeMillis(),
                delta = to - from,
                fromScore = from,
                toScore = to,
                reason = reason.trim(),
                source = "profile"
            ))
        }
        ok
    }

    fun adjustAffection(id: String, delta: Int, reason: String = "", source: String = "quick"): Contact? = synchronized(lock) {
        val current = loadContacts().firstOrNull { it.id == id } ?: return@synchronized null
        val to = AffectionScale.clamp(current.affection + delta)
        if (to == current.affection) return@synchronized current
        val next = current.copy(affection = to)
        if (!saveContact(next)) return@synchronized null
        appendRelationshipEvent(id, RelationshipEvent(
            id = newId(),
            ts = System.currentTimeMillis(),
            delta = to - current.affection,
            fromScore = current.affection,
            toScore = to,
            reason = reason.trim(),
            source = source
        ))
        return@synchronized contact(id)
    }

    /**
     * Learn from an explicit GalGame option tap. This does NOT change affection,
     * trust or closeness; it only records the owner's own reply-style choices.
     */
    fun recordStrategySelection(contactId: String, strategyKey: String): Boolean = synchronized(lock) {
        val current = loadContacts().firstOrNull { it.id == contactId } ?: return@synchronized false
        val key = strategyKey.trim().lowercase()
        if (key.isBlank()) return@synchronized false
        val next = current.strategySelections.toMutableMap()
        next[key] = (next[key] ?: 0) + 1
        saveContact(current.copy(strategySelections = next))
    }

    fun clearStrategySelections(contactId: String): Boolean = synchronized(lock) {
        val current = loadContacts().firstOrNull { it.id == contactId } ?: return@synchronized false
        saveContact(current.copy(strategySelections = emptyMap()))
    }

    fun linkIdentity(
        contactId: String,
        app: String,
        title: String,
        label: String = "",
        scope: String = ""
    ): Boolean = synchronized(lock) {
        val contact = loadContacts().firstOrNull { it.id == contactId } ?: return@synchronized false
        val cleanTitle = displayName(title)
        if (cleanTitle.isBlank()) return@synchronized false
        val cleanApp = app.trim()
        val cleanScope = displayName(scope)
        val exists = contact.identities.any {
            it.app == cleanApp &&
                normalizeName(it.scope) == normalizeName(cleanScope) &&
                normalizeName(it.title) == normalizeName(cleanTitle)
        }
        val identities = if (exists) contact.identities else
            contact.identities + PlatformIdentity(cleanApp, cleanTitle, label.trim(), cleanScope)
        val apps = if (cleanApp.isBlank() || cleanApp in contact.apps) contact.apps else contact.apps + cleanApp
        val aliases = if ((listOf(contact.name) + contact.aliases).any { normalizeName(it) == normalizeName(cleanTitle) })
            contact.aliases else contact.aliases + cleanTitle
        saveContact(contact.copy(identities = identities, apps = apps, aliases = aliases))
    }

    fun unlinkIdentity(contactId: String, app: String, title: String, scope: String = ""): Boolean = synchronized(lock) {
        val contact = loadContacts().firstOrNull { it.id == contactId } ?: return@synchronized false
        val identities = contact.identities.filterNot {
            it.app == app &&
                normalizeName(it.scope) == normalizeName(scope) &&
                normalizeName(it.title) == normalizeName(title)
        }
        saveContact(contact.copy(identities = identities))
    }

    /**
     * Explicit UI action: bind the current app/title identity to [targetId].
     * If that exact identity already belongs to another saved contact, merge the
     * duplicate into the selected target first.
     */
    fun linkCurrentIdentityToContact(
        targetId: String,
        app: String,
        title: String,
        scope: String = ""
    ): String = synchronized(lock) {
        val target = loadContacts().firstOrNull { it.id == targetId }
            ?: return@synchronized "目标联系人不存在"
        val want = normalizeName(title)
        val wantScope = normalizeName(scope)
        if (want.isBlank()) return@synchronized "当前会话标题为空"
        val owner = loadContacts().firstOrNull { c ->
            c.id != targetId && c.identities.any {
                it.app == app &&
                    normalizeName(it.scope) == wantScope &&
                    normalizeName(it.title) == want
            }
        }
        if (owner != null && !mergeContacts(targetId, owner.id)) {
            return@synchronized "合并旧联系人失败"
        }
        return@synchronized if (linkIdentity(targetId, app, title, appLabelForData(app), scope)) {
            if (owner != null) "已合并「${owner.name}」并关联到「${target.name}」"
            else "已关联到「${target.name}」"
        } else "关联失败"
    }

    fun findSpeakerContact(speaker: String, app: String, scope: String): Contact? = synchronized(lock) {
        val wantSpeaker = normalizeName(speaker)
        val wantScope = normalizeName(scope)
        if (wantSpeaker.isBlank()) return@synchronized null

        loadContacts().firstOrNull { c ->
            c.identities.any {
                it.app == app &&
                    normalizeName(it.scope) == wantScope &&
                    normalizeName(it.title) == wantSpeaker
            }
        }?.let { return@synchronized it }

        loadContacts().firstOrNull { c ->
            c.identities.any {
                it.app == app &&
                    it.scope.isBlank() &&
                    normalizeName(it.title) == wantSpeaker
            }
        }?.let { return@synchronized it }

        loadContacts().firstOrNull { c ->
            normalizeName(c.name) == wantSpeaker || c.aliases.any { normalizeName(it) == wantSpeaker }
        }
    }

    private fun mergeContacts(targetId: String, sourceId: String): Boolean {
        if (targetId == sourceId) return true
        val list = loadContacts()
        val target = list.firstOrNull { it.id == targetId } ?: return false
        val source = list.firstOrNull { it.id == sourceId } ?: return false

        fun joined(a: String, b: String): String = when {
            a.isBlank() -> b
            b.isBlank() || a.contains(b) -> a
            else -> a.trim() + "\n" + b.trim()
        }

        val merged = target.copy(
            aliases = (target.aliases + source.name + source.aliases)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            apps = (target.apps + source.apps).distinct(),
            identities = (target.identities + source.identities).distinctBy {
                it.app + "\u0000" + normalizeName(it.scope) + "\u0000" + normalizeName(it.title)
            },
            relationship = target.relationship.ifBlank { source.relationship },
            relationshipStage = target.relationshipStage.ifBlank { source.relationshipStage },
            profileTags = (target.profileTags + source.profileTags).distinct(),
            traits = joined(target.traits, source.traits),
            communicationStyle = joined(target.communicationStyle, source.communicationStyle),
            boundaries = joined(target.boundaries, source.boundaries),
            notes = joined(target.notes, source.notes),
            strategySelections = (target.strategySelections.keys + source.strategySelections.keys)
                .associateWith { key ->
                    (target.strategySelections[key] ?: 0) + (source.strategySelections[key] ?: 0)
                }.filterValues { it > 0 },
            autoSummary = joined(target.autoSummary, source.autoSummary)
        )
        if (!saveContact(merged)) return false

        val mergedLog = (loadLog(targetId) + loadLog(sourceId))
            .sortedBy { it.ts }
            .distinctBy { "${it.ts}\u0000${it.side}\u0000${it.speaker ?: ""}\u0000${it.app}\u0000${it.text}" }
            .takeLast(MAX_LOG)
            .toMutableList()
        if (!writeAtomic(logFile(targetId), logJson(mergedLog))) return false
        logCache[targetId] = mergedLog

        val mergedEvents = (loadRelationshipEvents(targetId) + loadRelationshipEvents(sourceId))
            .sortedBy { it.ts }
            .distinctBy { it.id }
            .takeLast(MAX_RELATION_EVENTS)
            .toMutableList()
        if (!writeAtomic(relationFile(targetId), relationshipEventsJson(mergedEvents))) return false
        relationCache[targetId] = mergedEvents

        val rewired = loadContactRelations()
            .map { edge ->
                edge.copy(
                    fromId = if (edge.fromId == sourceId) targetId else edge.fromId,
                    toId = if (edge.toId == sourceId) targetId else edge.toId
                )
            }
            .filter { it.fromId != it.toId }
            .distinctBy { edge ->
                val pair = listOf(edge.fromId, edge.toId).sorted().joinToString("|")
                pair + "\u0000" + edge.type.trim().lowercase()
            }
            .toMutableList()
        if (!writeAtomic(graphFile, contactRelationsJson(rewired))) {
            graphCache = null
            return false
        }
        graphCache = rewired

        list.removeAll { it.id == sourceId }
        if (!writeAtomic(contactsFile, contactsJson(list))) {
            contactsCache = null
            return false
        }
        logCache.remove(sourceId)
        relationCache.remove(sourceId)
        lastScreenCache.remove(sourceId)
        runCatching { logFile(sourceId).delete() }
        runCatching { screenFile(sourceId).delete() }
        runCatching { relationFile(sourceId).delete() }
        return true
    }

    /** Removes the contact and its history file. */
    fun deleteContact(id: String): Boolean = synchronized(lock) {
        val list = loadContacts()
        var ok = true
        if (list.removeAll { it.id == id }) {
            ok = writeAtomic(contactsFile, contactsJson(list))
            if (!ok) contactsCache = null
        }
        logCache.remove(id)
        relationCache.remove(id)
        lastScreenCache.remove(id)
        runCatching { logFile(id).delete() }
        runCatching { screenFile(id).delete() }
        runCatching { relationFile(id).delete() }
        val edges = loadContactRelations()
        if (edges.removeAll { it.fromId == id || it.toId == id }) {
            val graphOk = writeAtomic(graphFile, contactRelationsJson(edges))
            if (!graphOk) graphCache = null
            ok = ok && graphOk
        }
        ok
    }

    /**
     * Match a conversation title to a contact by normalized name or alias.
     * Never creates anything: an unknown title simply has no contact (v1.3
     * revision — contacts are only ever created by the user).
     *
     * @param app package name of the chat app the title came from; used only to
     *        prefer a contact that already knows this app when two match.
     */
    fun findContact(title: String, app: String): Contact? {
        synchronized(lock) {
            val want = normalizeName(title)
            if (want.isEmpty()) return null
            if (app.isNotBlank()) {
                loadContacts().firstOrNull { c ->
                    c.identities.any { identity ->
                        identity.app == app &&
                            identity.scope.isBlank() &&
                            normalizeName(identity.title) == want
                    }
                }?.let { return it }
            }
            val hits = loadContacts().filter { c ->
                normalizeName(c.name) == want || c.aliases.any { normalizeName(it) == want }
            }
            if (hits.isEmpty()) return null
            return hits.firstOrNull { app.isNotBlank() && it.apps.contains(app) } ?: hits.first()
        }
    }

    /**
     * Create a contact from a conversation title, or fold the title/app into the
     * one that already matches. Returns a message for the toast.
     */
    fun saveOrMergeContact(title: String, app: String): String {
        val display = displayName(title)
        if (display.isEmpty()) return "当前会话没有标题，存不了"
        val existing = findContact(title, app)
        if (existing == null) {
            val aliases = if (displayName(title) != title.trim()) listOf(title.trim()) else emptyList()
            saveContact(Contact(
                id = newId(),
                name = display,
                aliases = aliases,
                apps = if (app.isBlank()) emptyList() else listOf(app),
                identities = if (app.isBlank()) emptyList() else listOf(
                    PlatformIdentity(app = app, title = display, label = "")
                )
            ))
            return "已存为联系人「${display}」"
        }
        val alreadyLinked = existing.identities.any {
            it.app == app && normalizeName(it.title) == normalizeName(display)
        }
        if (alreadyLinked) return "联系人「${existing.name}」已关联当前会话"
        linkIdentity(existing.id, app, display)
        return "已把当前会话并入联系人「${existing.name}」"
    }

    // ---------------------------------------------------------------- history

    /**
     * Append one screenful of messages, keeping only the newest [MAX_LOG].
     *
     * The unit is a SEQUENCE, not a set of lines. A capture gives us the whole
     * visible screen S, top to bottom; P is whatever screen we last wrote for
     * this contact. Two identical short lines on one screen are two positions and
     * get two entries — they are not folded together, and nothing is dropped for
     * being "too short to dedupe on" or for having been said before.
     *
     * The rules, in order:
     *  - S equals P            → the same screen again, write nothing.
     *  - log is empty          → write all of S.
     *  - log tail matches the first k lines of S (k > 0) → the screen scrolled by
     *    (S.size - k) lines; append only that new tail.
     *  - k is 0 and S shares nothing with P → the user scrolled up into old
     *    messages we already hold; this round writes nothing rather than
     *    duplicating history at the end of the file.
     *  - anything else         → append all of S.
     *
     * @param screenBatch true for a capture (the rules above). False for a
     *        deliberate single-entry injection that is NOT a screen read, which
     *        is appended as-is.
     */
    fun appendLog(contactId: String, entries: List<LogEntry>, screenBatch: Boolean = true): Boolean {
        if (entries.isEmpty()) return true
        synchronized(lock) {
            val screen = entries.filter { it.text.isNotBlank() }
            if (screen.isEmpty()) return true
            val list = loadLog(contactId)
            val keys = screen.map { key(it.side, it.text, it.speaker) }
            val prev = if (screenBatch) loadLastScreen(contactId) else emptyList()

            // Same screen as last time: nothing happened worth recording.
            if (screenBatch && prev.isNotEmpty() && prev == keys) return true

            // How much of S the log already ends with.
            var k = 0
            val maxK = minOf(list.size, keys.size)
            for (cand in maxK downTo 1) {
                var match = true
                for (i in 0 until cand) {
                    val e = list[list.size - cand + i]
                    if (key(e.side, e.text, e.speaker) != keys[i]) { match = false; break }
                }
                if (match) { k = cand; break }
            }

            val tail: List<LogEntry> = when {
                !screenBatch -> screen
                list.isEmpty() -> screen
                k > 0 -> screen.drop(k)
                // Nothing in common with the screen we last wrote → we are looking
                // at older messages, not newer ones. Leave the log alone.
                prev.isNotEmpty() && keys.none { it in prev } -> {
                    Log.d(TAG, "appendLog contact=$contactId skipped: scrolled off the last screen")
                    return true
                }
                else -> screen
            }
            if (tail.isEmpty()) {
                if (screenBatch) saveLastScreen(contactId, keys)
                return true
            }

            list.addAll(tail)
            while (list.size > MAX_LOG) list.removeAt(0)
            val ok = writeAtomic(logFile(contactId), logJson(list))
            if (!ok) logCache.remove(contactId)
            if (ok && screenBatch) saveLastScreen(contactId, keys)
            Log.d(TAG, "appendLog contact=$contactId added=${tail.size} overlap=$k total=${list.size} ok=$ok")
            return ok
        }
    }

    /** The newest [n] entries, oldest first. */
    fun recentLog(contactId: String, n: Int): List<LogEntry> {
        if (n <= 0) return emptyList()
        synchronized(lock) {
            val list = loadLog(contactId)
            return if (list.size <= n) list.toList()
            else list.subList(list.size - n, list.size).toList()
        }
    }

    fun logSize(contactId: String): Int = synchronized(lock) { loadLog(contactId).size }

    fun lastInteractionAt(contactId: String): Long = synchronized(lock) {
        loadLog(contactId).lastOrNull()?.ts ?: 0L
    }

    fun relationshipEvents(contactId: String, n: Int = 20): List<RelationshipEvent> = synchronized(lock) {
        if (n <= 0) return@synchronized emptyList()
        val list = loadRelationshipEvents(contactId)
        if (list.size <= n) list.toList() else list.takeLast(n)
    }

    private fun appendRelationshipEvent(contactId: String, event: RelationshipEvent): Boolean {
        val list = loadRelationshipEvents(contactId)
        list.add(event)
        while (list.size > MAX_RELATION_EVENTS) list.removeAt(0)
        val ok = writeAtomic(relationFile(contactId), relationshipEventsJson(list))
        if (!ok) relationCache.remove(contactId)
        return ok
    }

    fun clearLog(contactId: String) = synchronized(lock) {
        logCache.remove(contactId)
        lastScreenCache.remove(contactId)
        runCatching { logFile(contactId).delete() }
        runCatching { screenFile(contactId).delete() }
        Unit
    }

    /**
     * The (side, text) sequence of the last screen written for this contact, as
     * comparison keys. Kept on disk as well as in memory so that re-opening a
     * chat that has not moved since does not append the same screen again.
     */
    private fun loadLastScreen(contactId: String): List<String> {
        lastScreenCache[contactId]?.let { return it }
        val out = ArrayList<String>()
        val loaded = readJsonArray(screenFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) out.add(s)
            }
        }
        if (loaded.trustworthy) lastScreenCache[contactId] = out
        return out
    }

    private fun saveLastScreen(contactId: String, keys: List<String>) {
        lastScreenCache[contactId] = keys
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        if (!writeAtomic(screenFile(contactId), arr.toString())) lastScreenCache.remove(contactId)
    }

    // ------------------------------------------------------------------ admin

    fun counts(): KbCounts = synchronized(lock) {
        val contacts = loadContacts()
        var lines = 0
        contacts.forEach { lines += loadLog(it.id).size }
        KbCounts(loadNotes().size, contacts.size, lines, loadContactRelations().size)
    }

    /**
     * Wipe every knowledge-base file. Deletes only `filesDir/kb` — API keys,
     * whitelist and every other SharedPreferences value are untouched.
     */
    fun clearAll() = synchronized(lock) {
        notesCache = null
        contactsCache = null
        graphCache = null
        logCache.clear()
        relationCache.clear()
        lastScreenCache.clear()
        runCatching { root.deleteRecursively() }
        Log.i(TAG, "kb cleared")
        Unit
    }

    // ------------------------------------------------------------------ io

    private fun loadNotes(): MutableList<Note> {
        notesCache?.let { return it }
        val list = ArrayList<Note>()
        val loaded = readJsonArray(notesFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Note(
                    id = o.optString("id").ifBlank { newId() },
                    title = o.optString("title"),
                    content = o.optString("content"),
                    tags = strList(o.optJSONArray("tags")),
                    alwaysOn = o.optBoolean("alwaysOn", false),
                    enabled = o.optBoolean("enabled", true),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) notesCache = list
        return list
    }

    private fun loadContacts(): MutableList<Contact> {
        contactsCache?.let { return it }
        val list = ArrayList<Contact>()
        val loaded = readJsonArray(contactsFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Contact(
                    id = o.optString("id").ifBlank { newId() },
                    name = o.optString("name"),
                    aliases = strList(o.optJSONArray("aliases")),
                    apps = strList(o.optJSONArray("apps")),
                    identities = identityList(o.optJSONArray("identities")),
                    relationship = o.optString("relationship"),
                    relationshipStage = o.optString("relationshipStage"),
                    affection = AffectionScale.clamp(o.optInt("affection", 50)),
                    trust = AffectionScale.clamp(o.optInt("trust", 50)),
                    closeness = AffectionScale.clamp(o.optInt("closeness", 50)),
                    profileTags = strList(o.optJSONArray("profileTags")),
                    traits = o.optString("traits"),
                    communicationStyle = o.optString("communicationStyle"),
                    boundaries = o.optString("boundaries"),
                    notes = o.optString("notes"),
                    strategySelections = intMap(o.optJSONObject("strategySelections")),
                    autoSummary = o.optString("autoSummary"),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) contactsCache = list
        return list
    }

    private fun loadContactRelations(): MutableList<ContactRelation> {
        graphCache?.let { return it }
        val list = ArrayList<ContactRelation>()
        val loaded = readJsonArray(graphFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val fromId = o.optString("fromId").trim()
                val toId = o.optString("toId").trim()
                if (fromId.isBlank() || toId.isBlank() || fromId == toId) continue
                list.add(ContactRelation(
                    id = o.optString("id").ifBlank { newId() },
                    fromId = fromId,
                    toId = toId,
                    type = o.optString("type"),
                    strength = o.optInt("strength", 50).coerceIn(0, 100),
                    note = o.optString("note"),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) graphCache = list
        return list
    }

    private fun loadLog(contactId: String): MutableList<LogEntry> {
        logCache[contactId]?.let { return it }
        val list = ArrayList<LogEntry>()
        val loaded = readJsonArray(logFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(LogEntry(
                    side = o.optString("side", "other"),
                    text = o.optString("text"),
                    ts = o.optLong("ts", 0L),
                    app = o.optString("app"),
                    speaker = o.optString("speaker").takeIf { it.isNotBlank() }
                ))
            }
        }
        if (loaded.trustworthy) logCache[contactId] = list
        return list
    }

    private fun loadRelationshipEvents(contactId: String): MutableList<RelationshipEvent> {
        relationCache[contactId]?.let { return it }
        val list = ArrayList<RelationshipEvent>()
        val loaded = readJsonArray(relationFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(RelationshipEvent(
                    id = o.optString("id").ifBlank { newId() },
                    ts = o.optLong("ts", 0L),
                    delta = o.optInt("delta", 0),
                    fromScore = AffectionScale.clamp(o.optInt("fromScore", 50)),
                    toScore = AffectionScale.clamp(o.optInt("toScore", 50)),
                    reason = o.optString("reason"),
                    source = o.optString("source", "manual")
                ))
            }
        }
        if (loaded.trustworthy) relationCache[contactId] = list
        return list
    }

    private fun notesJson(list: List<Note>): String {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject()
                .put("id", n.id)
                .put("title", n.title)
                .put("content", n.content)
                .put("tags", JSONArray(n.tags))
                .put("alwaysOn", n.alwaysOn)
                .put("enabled", n.enabled)
                .put("updatedAt", n.updatedAt))
        }
        return arr.toString()
    }

    private fun contactsJson(list: List<Contact>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("aliases", JSONArray(c.aliases))
                .put("apps", JSONArray(c.apps))
                .put("identities", JSONArray().apply {
                    c.identities.forEach { identity ->
                        put(JSONObject()
                            .put("app", identity.app)
                            .put("title", identity.title)
                            .put("label", identity.label)
                            .put("scope", identity.scope))
                    }
                })
                .put("relationship", c.relationship)
                .put("relationshipStage", c.relationshipStage)
                .put("affection", AffectionScale.clamp(c.affection))
                .put("trust", AffectionScale.clamp(c.trust))
                .put("closeness", AffectionScale.clamp(c.closeness))
                .put("profileTags", JSONArray(c.profileTags))
                .put("traits", c.traits)
                .put("communicationStyle", c.communicationStyle)
                .put("boundaries", c.boundaries)
                .put("notes", c.notes)
                .put("strategySelections", JSONObject().apply {
                    c.strategySelections.forEach { (k, v) -> if (v > 0) put(k, v) }
                })
                .put("autoSummary", c.autoSummary)
                .put("updatedAt", c.updatedAt))
        }
        return arr.toString()
    }

    private fun contactRelationsJson(list: List<ContactRelation>): String {
        val arr = JSONArray()
        list.forEach { edge ->
            arr.put(JSONObject()
                .put("id", edge.id)
                .put("fromId", edge.fromId)
                .put("toId", edge.toId)
                .put("type", edge.type)
                .put("strength", edge.strength.coerceIn(0, 100))
                .put("note", edge.note)
                .put("updatedAt", edge.updatedAt))
        }
        return arr.toString()
    }

    private fun logJson(list: List<LogEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject()
                .put("side", e.side)
                .put("text", e.text)
                .put("ts", e.ts)
                .put("app", e.app)
                .put("speaker", e.speaker ?: JSONObject.NULL))
        }
        return arr.toString()
    }

    private fun relationshipEventsJson(list: List<RelationshipEvent>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject()
                .put("id", e.id)
                .put("ts", e.ts)
                .put("delta", e.delta)
                .put("fromScore", e.fromScore)
                .put("toScore", e.toScore)
                .put("reason", e.reason)
                .put("source", e.source))
        }
        return arr.toString()
    }

    private fun identityList(arr: JSONArray?): List<PlatformIdentity> {
        arr ?: return emptyList()
        val out = ArrayList<PlatformIdentity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isNotEmpty()) {
                out.add(PlatformIdentity(
                    app = o.optString("app").trim(),
                    title = title,
                    label = o.optString("label").trim(),
                    scope = o.optString("scope").trim()
                ))
            }
        }
        return out
    }

    /**
     * Result of reading one JSON file. [trustworthy] is false only in the one
     * nasty case: the file exists, does not parse, AND could not be moved aside
     * — then an empty list is a guess, so it must not be cached and must not be
     * written over the user's data.
     */
    private class Loaded(val arr: JSONArray?, val trustworthy: Boolean)

    /** Files that failed to parse and could not be preserved; never overwrite. */
    private val unreadable = HashSet<String>()

    private fun readJsonArray(f: File): Loaded {
        if (!f.exists()) { unreadable.remove(f.absolutePath); return Loaded(null, true) }
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            unreadable.remove(f.absolutePath)
            Loaded(arr, true)
        } catch (e: Exception) {
            // Damaged file: set it aside under a dated name rather than let the
            // next save silently write over it. Starting empty is only safe once
            // the original is actually preserved.
            val backup = File(f.parentFile, "${f.name}.corrupt.${System.currentTimeMillis()}")
            val kept = runCatching { f.renameTo(backup) }.getOrDefault(false)
            if (kept) unreadable.remove(f.absolutePath) else unreadable.add(f.absolutePath)
            Log.w(TAG, "unreadable ${f.name}: ${e.javaClass.simpleName} preserved=$kept")
            Loaded(null, kept)
        }
    }

    /**
     * Temp file + rename, so a crash never leaves a half-written document.
     *
     * The rename REPLACES the destination in one step (POSIX semantics, same
     * directory) — deleting the old file first would mean a kill in between
     * loses everything. Returns false when the data did not reach disk; callers
     * drop their cache so the next read goes back to the file.
     */
    private fun writeAtomic(f: File, text: String): Boolean {
        if (f.absolutePath in unreadable) {
            Log.w(TAG, "refusing to overwrite unparsable ${f.name}")
            return false
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        return try {
            f.parentFile?.mkdirs()
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(f)) return true
            // Same-directory rename should not fail. If it somehow does, an
            // in-place overwrite is the only way left — not atomic, so say so.
            Log.w(TAG, "rename failed, overwriting ${f.name} in place")
            f.writeText(text, Charsets.UTF_8)
            runCatching { tmp.delete() }
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Log.w(TAG, "write failed ${f.name}: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun intMap(o: JSONObject?): Map<String, Int> {
        o ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        o.keys().forEach { key ->
            val v = o.optInt(key, 0)
            if (v > 0) out[key] = v
        }
        return out
    }

    private fun strList(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun key(side: String, text: String, speaker: String? = null) =
        side + "\u0000" + (speaker ?: "") + "\u0000" + text

    private fun appLabelForData(pkg: String): String = when (pkg) {
        "com.tencent.mobileqq" -> "QQ"
        "com.ss.android.lark" -> "飞书"
        "com.twitter.android" -> "X"
        "com.tencent.mm" -> "微信"
        "org.telegram.messenger" -> "Telegram"
        "com.alibaba.android.rimet" -> "钉钉"
        else -> pkg
    }

    companion object {
        private const val TAG = "JEVASSIST"
        const val MAX_LOG = 300
        const val MAX_RELATION_EVENTS = 100

        @Volatile private var instance: KbStore? = null

        fun get(context: Context): KbStore =
            instance ?: synchronized(this) {
                instance ?: KbStore(context).also { instance = it }
            }

        fun newId(): String = java.util.UUID.randomUUID().toString().substring(0, 12)

        /**
         * Compile a pattern without ever taking the class down with it. A
         * `Regex(...)` straight in a `val` runs during `<clinit>`, so one bad
         * pattern turns into `ExceptionInInitializerError` and every call into
         * [KbStore] dies with it — which is exactly what happened on device
         * (Android's ICU engine rejected the old member-count pattern). A null
         * here only means that one cleanup step is skipped.
         */
        private fun safeRegex(pattern: String): Regex? =
            runCatching { Regex(pattern) }.getOrElse {
                Log.w(TAG, "regex init failed: ${it.javaClass.simpleName} ${it.message ?: ""}")
                null
            }

        private val ZERO_WIDTH = safeRegex("[\\u200B-\\u200D\\uFEFF]")

        /**
         * Trailing group member count. Written as an alternation of escaped code
         * points rather than a character class holding brackets: ICU on device
         * read `[(...)]` as an unterminated class ("missing closing bracket").
         * No literal full-width bracket in the source, on purpose.
         */
        private val TRAILING_COUNT =
            safeRegex("\\s*(?:\\(|\\uFF08)\\s*\\d+\\s*(?:\\)|\\uFF09)\\s*$")

        private fun stripZeroWidth(s: String): String = ZERO_WIDTH?.replace(s, "") ?: s

        private fun stripTrailingCount(s: String): String =
            TRAILING_COUNT?.replace(s, "")?.trim() ?: s

        /**
         * Name key for matching: trimmed, zero-width characters removed, the
         * group member count `(12)` (half- or full-width) dropped, lower-cased.
         * If the patterns failed to compile this degrades to trim + lowercase.
         */
        fun normalizeName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val t = stripTrailingCount(stripZeroWidth(s).trim())
            return t.trim().lowercase()
        }

        /** Same cleanup as [normalizeName] but keeps the original casing, for display. */
        fun displayName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return stripTrailingCount(stripZeroWidth(s).trim()).trim()
        }

        /** Loose key for substring matching (no member-count stripping). */
        fun normalizeText(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return stripZeroWidth(s).trim().lowercase()
        }
    }
}
