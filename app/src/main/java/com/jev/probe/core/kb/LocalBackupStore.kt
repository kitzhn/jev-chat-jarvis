package com.jev.probe.core.kb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalBackupInfo(
    val id: String,
    val createdAt: Long,
    val sizeBytes: Long
)

data class BackupDeleteResult(
    val deletedFiles: Int,
    /** False when other backups still need the shared key, or key cleanup failed. */
    val encryptionKeyDeleted: Boolean
)

/** App-private, device-bound AES-GCM backups of KbStore only. */
class LocalBackupStore(context: Context) {
    private val app = context.applicationContext
    private val directory get() = File(app.filesDir, "local-backups")

    fun listBackups(): List<LocalBackupInfo> = synchronized(GLOBAL_LOCK) {
        ensureDirectory()
        cleanupTemporaryFiles()
        listBackupFiles().map { file ->
            LocalBackupInfo(file.name, file.lastModified(), file.length())
        }.sortedByDescending { it.createdAt }
    }

    fun createBackup(): LocalBackupInfo = synchronized(GLOBAL_LOCK) {
        ensureDirectory()
        cleanupTemporaryFiles()
        val files = KbStore.get(app).exportBackupFiles()
        if (files.size > KbBackupPaths.MAX_FILE_COUNT) throw IOException("知识库文件数量超过备份上限")

        val uuid = UUID.randomUUID().toString().replace("-", "")
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val id = "kb-$timestamp-${uuid.take(8)}.jevbackup"
        val now = System.currentTimeMillis()
        val archive = File(directory, "tmp-$uuid.zip")
        val encrypted = File(directory, "tmp-$uuid.enc")
        val destination = File(directory, id)
        try {
            writeArchive(archive, files, now)
            if (archive.length() > MAX_ARCHIVE_BYTES) throw IOException("备份压缩包超过 34 MiB 上限")
            encryptArchive(archive, encrypted, encryptionKeyForNewBackup())
            if (encrypted.length() > MAX_ENCRYPTED_BYTES) throw IOException("加密备份超过大小上限")
            if (!encrypted.renameTo(destination)) throw IOException("无法保存加密备份")
            destination.setLastModified(now)
            LocalBackupInfo(destination.name, destination.lastModified(), destination.length())
        } finally {
            val archiveRemoved = !archive.exists() || archive.delete()
            val encryptedRemoved = !encrypted.exists() || encrypted.delete()
            if (!archiveRemoved || !encryptedRemoved) {
                throw IOException("无法清理加密过程中的临时文件；请重新打开备份页重试清理")
            }
        }
    }

    /** Restores only after GCM authentication, archive checks, hashes and JSON validation. */
    fun restoreBackup(id: String) = synchronized(GLOBAL_LOCK) {
        val source = findBackup(id) ?: throw IOException("备份不存在")
        if (source.length() !in (HEADER_SIZE + GCM_TAG_BYTES).toLong()..MAX_ENCRYPTED_BYTES) {
            throw IOException("备份文件大小不正确")
        }
        val plainArchive = File(directory, "tmp-${UUID.randomUUID().toString().replace("-", "")}.restore")
        try {
            decryptArchive(source, plainArchive, existingEncryptionKey())
            val snapshot = readArchive(plainArchive)
            KbStore.get(app).restoreBackupFiles(snapshot)
        } finally {
            if (plainArchive.exists() && !plainArchive.delete()) {
                throw IOException("恢复已结束，但无法清理临时文件；请重新打开备份页重试清理")
            }
        }
    }

    fun deleteBackup(id: String): BackupDeleteResult = synchronized(GLOBAL_LOCK) {
        val target = findBackup(id) ?: throw IOException("备份不存在")
        if (!target.delete()) throw IOException("无法删除该备份")
        val keyDeleted = if (listBackupFiles().isEmpty()) deleteEncryptionKey() else false
        BackupDeleteResult(1, keyDeleted)
    }

    fun deleteAllBackups(): BackupDeleteResult = synchronized(GLOBAL_LOCK) {
        ensureDirectory()
        cleanupTemporaryFiles()
        val targets = listBackupFiles()
        var deleted = 0
        targets.forEach { file ->
            if (!file.delete()) throw IOException("已删除 $deleted 份，剩余备份删除失败")
            deleted++
        }
        val keyDeleted = if (listBackupFiles().isEmpty()) deleteEncryptionKey() else false
        BackupDeleteResult(deleted, keyDeleted)
    }

    private fun ensureDirectory() {
        if (directory.exists()) {
            if (!directory.isDirectory) throw IOException("本地备份路径不是目录")
        } else if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建本地备份目录")
        }
    }

    private fun cleanupTemporaryFiles() {
        val entries = directory.listFiles() ?: throw IOException("无法读取本地备份目录")
        entries.filter { it.name.startsWith("tmp-") }.forEach { entry ->
            val removed = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            if (!removed && entry.exists()) throw IOException("无法清理上次未完成的备份临时文件")
        }
    }

    private fun listBackupFiles(): List<File> {
        val entries = directory.listFiles()
        if (entries == null) {
            if (directory.exists()) throw IOException("无法读取本地备份目录")
            return emptyList()
        }
        return entries.filter { it.isFile && BACKUP_FILE_NAME.matches(it.name) }
            .sortedByDescending { it.lastModified() }
    }

    private fun findBackup(id: String): File? {
        if (!BACKUP_FILE_NAME.matches(id)) return null
        val parent = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val candidate = File(directory, id)
        if (runCatching { candidate.canonicalFile.parentFile == parent }.getOrDefault(false) && candidate.isFile) {
            return candidate
        }
        return null
    }

    private fun encryptionKeyForNewBackup(): SecretKey {
        val store = androidKeyStore()
        val existing = runCatching { store.getKey(KEY_ALIAS, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing
        if (listBackupFiles().isNotEmpty()) {
            throw IOException("本机解密密钥不可用；为避免覆盖旧备份，暂不能创建新备份")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun existingEncryptionKey(): SecretKey {
        val key = runCatching { androidKeyStore().getKey(KEY_ALIAS, null) as? SecretKey }
            .getOrNull()
        return key ?: throw IOException("本机解密密钥不可用，这份备份无法恢复")
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun deleteEncryptionKey(): Boolean = runCatching {
        val store = androidKeyStore()
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        !store.containsAlias(KEY_ALIAS)
    }.getOrDefault(false)

    private fun writeArchive(file: File, snapshot: Map<String, ByteArray>, createdAt: Long) {
        var total = 0L
        snapshot.forEach { (path, bytes) ->
            if (!KbBackupPaths.isAllowed(path)) throw IOException("知识库导出包含不允许的路径")
            if (bytes.size.toLong() > KbBackupPaths.MAX_FILE_BYTES) throw IOException("知识库文件超过单文件上限")
            total += bytes.size
            if (total > KbBackupPaths.MAX_TOTAL_BYTES) throw IOException("知识库数据超过单份备份上限")
        }
        val entries = JSONArray()
        snapshot.toSortedMap().forEach { (path, bytes) ->
            entries.put(JSONObject()
                .put("path", path)
                .put("size", bytes.size)
                .put("sha256", sha256(bytes)))
        }
        val manifest = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("createdAt", createdAt)
            .put("files", entries)
            .toString()
            .toByteArray(Charsets.UTF_8)

        FileOutputStream(file).use { raw ->
            val zip = ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(raw)))
            zip.use {
                it.putNextEntry(ZipEntry(MANIFEST_NAME))
                it.write(manifest)
                it.closeEntry()
                snapshot.toSortedMap().forEach { (path, bytes) ->
                    it.putNextEntry(ZipEntry(path))
                    it.write(bytes)
                    it.closeEntry()
                }
            }
            raw.fd.sync()
        }
    }

    private fun encryptArchive(plain: File, destination: File, key: SecretKey) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw IOException("Android Keystore 未提供加密随机数")
        if (iv.size != GCM_IV_BYTES) throw IOException("加密随机数长度不正确")
        val header = MAGIC + iv
        cipher.updateAAD(header)
        FileOutputStream(destination).use { out ->
            out.write(header)
            FileInputStream(plain).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    cipher.update(buffer, 0, count)?.let { out.write(it) }
                }
            }
            out.write(cipher.doFinal())
            out.fd.sync()
        }
    }

    private fun decryptArchive(encrypted: File, destination: File, key: SecretKey) {
        FileInputStream(encrypted).use { input ->
            val header = ByteArray(HEADER_SIZE)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) throw IOException("备份文件头不完整")
                offset += count
            }
            if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw IOException("无法识别的备份格式")
            }
            val iv = header.copyOfRange(MAGIC.size, HEADER_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            FileOutputStream(destination).use { out ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val plain = cipher.update(buffer, 0, count)
                    if (plain != null) {
                        total += plain.size
                        if (total > MAX_ARCHIVE_BYTES) throw IOException("备份解密后超过大小上限")
                        out.write(plain)
                    }
                }
                // GCM verifies the authentication tag here, before the archive is parsed or restored.
                val finalBytes = cipher.doFinal()
                total += finalBytes.size
                if (total > MAX_ARCHIVE_BYTES) throw IOException("备份解密后超过大小上限")
                out.write(finalBytes)
                out.fd.sync()
            }
        }
    }

    private fun readArchive(file: File): Map<String, ByteArray> {
        val contents = linkedMapOf<String, ByteArray>()
        var manifest: JSONObject? = null
        var total = 0L
        var entriesSeen = 0
        ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entriesSeen++
                if (entriesSeen > KbBackupPaths.MAX_FILE_COUNT + 1 || entry.isDirectory) {
                    throw IOException("备份包含过多或不支持的 ZIP 条目")
                }
                if (entry.name == MANIFEST_NAME) {
                    if (manifest != null) throw IOException("备份清单重复")
                    val bytes = readEntryBounded(zip, MAX_MANIFEST_BYTES)
                    manifest = try {
                        JSONObject(String(bytes, Charsets.UTF_8))
                    } catch (_: Exception) {
                        throw IOException("备份清单损坏")
                    }
                } else {
                    if (!KbBackupPaths.isAllowed(entry.name) || contents.containsKey(entry.name)) {
                        throw IOException("备份包含不安全或重复的文件路径")
                    }
                    if (entry.size > KbBackupPaths.MAX_FILE_BYTES) throw IOException("备份中的单个文件过大")
                    val bytes = readEntryBounded(zip, KbBackupPaths.MAX_FILE_BYTES)
                    total += bytes.size
                    if (total > KbBackupPaths.MAX_TOTAL_BYTES) throw IOException("备份总数据超过限制")
                    contents[entry.name] = bytes
                }
                zip.closeEntry()
            }
        }

        val checkedManifest = manifest ?: throw IOException("备份缺少清单")
        if (checkedManifest.optInt("formatVersion", -1) != FORMAT_VERSION) {
            throw IOException("此备份版本暂不支持")
        }
        val manifestFiles = checkedManifest.optJSONArray("files") ?: throw IOException("备份清单缺少文件列表")
        if (manifestFiles.length() != contents.size || manifestFiles.length() > KbBackupPaths.MAX_FILE_COUNT) {
            throw IOException("备份文件列表不完整")
        }
        val expected = HashMap<String, Pair<Long, String>>()
        for (i in 0 until manifestFiles.length()) {
            val item = manifestFiles.optJSONObject(i) ?: throw IOException("备份清单格式错误")
            val path = item.optString("path")
            if (!KbBackupPaths.isAllowed(path) || expected.containsKey(path)) {
                throw IOException("备份清单包含不安全或重复的路径")
            }
            expected[path] = item.optLong("size", -1L) to item.optString("sha256")
        }
        if (expected.keys != contents.keys) throw IOException("备份清单与文件内容不一致")
        contents.forEach { (path, bytes) ->
            val (size, hash) = expected[path] ?: throw IOException("备份清单不完整")
            if (size != bytes.size.toLong() || !hash.equals(sha256(bytes), ignoreCase = true)) {
                throw IOException("备份文件校验失败：${path.substringAfterLast('/')}")
            }
            if (!KbBackupPaths.isQuarantined(path)) {
                try {
                    org.json.JSONArray(String(bytes, Charsets.UTF_8))
                } catch (_: Exception) {
                    throw IOException("知识库 JSON 校验失败：${path.substringAfterLast('/')}")
                }
            }
        }
        return contents
    }

    private fun readEntryBounded(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("ZIP 条目超过大小限制")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** ZipOutputStream must finish the archive without closing the fsync-able file descriptor. */
    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    companion object {
        private val GLOBAL_LOCK = Any()
        private val BACKUP_FILE_NAME = Regex("[A-Za-z0-9_.-]{1,150}\\.jevbackup")
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "com.jev.probe.localKbBackup.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_NAME = "manifest.json"
        private const val MAX_MANIFEST_BYTES = 1024L * 1024L
        private const val MAX_ARCHIVE_BYTES = 34L * 1024L * 1024L
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val HEADER_SIZE = 8 + GCM_IV_BYTES
        private const val MAX_ENCRYPTED_BYTES = MAX_ARCHIVE_BYTES + HEADER_SIZE + GCM_TAG_BYTES
        private val MAGIC = "JEVKBK01".toByteArray(Charsets.US_ASCII)
    }
}
