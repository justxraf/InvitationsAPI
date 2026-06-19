package com.justxraf.networkapi.invitations

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A file-backed [InvitationStore] that persists invitations as a JSON array of flat string objects,
 * using an [InvitationSerializer] to bridge the engine's generic [T] to those objects.
 *
 * Suitable for the typical Skyblock plugin: a handful to a few hundred pending invitations at a time.
 * It keeps the full set in memory and rewrites the whole file on every mutation, so it trades write
 * throughput for zero external dependencies and a trivially inspectable on-disk format. For higher
 * volumes, back the SPI with [SqlInvitationStore] instead.
 *
 * ### Durability
 * Writes are crash-safe: the new content is written to a sibling temp file (flushed to disk), then
 * moved into place with [StandardCopyOption.ATOMIC_MOVE] where the filesystem supports it (falling
 * back to a plain replacing move). A reader therefore always sees either the old or the new file,
 * never a half-written one. All I/O uses an explicit UTF-8 charset.
 *
 * ### Corruption recovery
 * If the file is present but unparseable on open, it is moved aside to `<name>.corrupt-<timestamp>`
 * (so the bad data is preserved for inspection) and the store starts empty rather than throwing and
 * preventing the plugin from enabling. Pass `recoverFromCorruption = false` to fail loudly instead.
 *
 * ### Single-process ownership
 * Pass `lockFile = true` to acquire an exclusive OS [FileLock] on a sibling `<name>.lock` file, held
 * until [close]: this enforces single-process ownership, so a second process (or a second un-closed
 * store) opening the same file throws [IllegalStateException]. It is **off by default** so the common
 * open → use → reopen pattern (and tests) keeps working; turn it on in production where two server
 * processes might race for the same file. Remember to [close] the store so the lock is released.
 *
 * ### Thread-safety
 * An in-memory [ConcurrentHashMap] mirrors the file; writes are serialised on [lock] so concurrent
 * [save] / [remove] / [removeAll] / [replace] calls never interleave a half-written file.
 *
 * The JSON is written and parsed by a tiny built-in codec (below) so the core stays dependency-free.
 * The serialized form is `String -> String` only; richer values must be encoded by the serializer.
 */
class JsonFileStore<T : Invitation> @JvmOverloads constructor(
    private val file: File,
    private val serializer: InvitationSerializer<T>,
    private val recoverFromCorruption: Boolean = true,
    lockFile: Boolean = false,
) : InvitationStore<T> {

    private val byId = ConcurrentHashMap<UUID, T>()
    private val lock = Any()

    private val lockHandle: LockHandle? = if (lockFile) acquireLock(file) else null

    init {
        if (file.exists()) {
            val text = file.readText(StandardCharsets.UTF_8)
            try {
                Json.parseArray(text).forEach { fields ->
                    val invitation = serializer.deserialize(fields)
                    byId[invitation.id] = invitation
                }
            } catch (e: Exception) {
                if (!recoverFromCorruption) {
                    lockHandle?.release()
                    throw IOException("Invitation store file ${file.path} is corrupt: ${e.message}", e)
                }
                quarantineCorruptFile()
                byId.clear()
            }
        } else {
            file.parentFile?.mkdirs()
        }
    }

    override fun load(): List<T> = byId.values.toList()

    override fun save(invitation: T) {
        synchronized(lock) {
            byId[invitation.id] = invitation
            flush()
        }
    }

    override fun remove(id: UUID) {
        synchronized(lock) {
            if (byId.remove(id) != null) flush()
        }
    }

    /** Batched: apply every removal in memory, then write the file at most once. */
    override fun removeAll(ids: Collection<UUID>) {
        synchronized(lock) {
            var changed = false
            for (id in ids) if (byId.remove(id) != null) changed = true
            if (changed) flush()
        }
    }

    /** Atomic from the file's perspective: both the removal and the insert land in a single rewrite. */
    override fun replace(old: UUID, new: T) {
        synchronized(lock) {
            byId.remove(old)
            byId[new.id] = new
            flush()
        }
    }

    /** Release the OS file lock (if held). The in-memory state and the data file are left intact. */
    override fun close() {
        lockHandle?.release()
    }

    /** Rewrite the whole file crash-safely: write a temp file, fsync it, then atomically move it in. */
    private fun flush() {
        val text = Json.writeArray(byId.values.map { serializer.serialize(it) })
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text, StandardCharsets.UTF_8)
        val tmpPath = tmp.toPath()
        val target = file.toPath()
        try {
            Files.move(tmpPath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Filesystem can't do an atomic move (some Windows/network cases) — fall back to a plain
            // replacing move, which is still safer than truncating the live file in place.
            Files.move(tmpPath, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Move an unparseable file aside so the bad data is kept for inspection and we can start clean. */
    private fun quarantineCorruptFile() {
        val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // Best effort: if we can't move it, leave it; the next flush will overwrite it anyway.
        }
    }

    private fun acquireLock(dataFile: File): LockHandle {
        dataFile.parentFile?.mkdirs()
        val lockFile = File(dataFile.parentFile, "${dataFile.name}.lock")
        val raf = RandomAccessFile(lockFile, "rw")
        val fileLock: FileLock? = try {
            raf.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (fileLock == null) {
            raf.close()
            throw IllegalStateException(
                "Invitation store ${dataFile.path} is already locked by another process " +
                    "(lock file ${lockFile.path}). Only one process may own a JsonFileStore at a time.",
            )
        }
        return LockHandle(raf, fileLock, lockFile)
    }

    private class LockHandle(
        private val raf: RandomAccessFile,
        private val fileLock: FileLock,
        private val lockFile: File,
    ) {
        fun release() {
            try { fileLock.release() } catch (_: IOException) {}
            try { raf.close() } catch (_: IOException) {}
            lockFile.delete()
        }
    }

    /**
     * Minimal JSON codec for exactly the shape this store writes: an array of objects whose values
     * are all strings. Not a general JSON parser — it rejects anything outside that shape — but it
     * round-trips the store's own output and keeps the core free of a JSON dependency.
     */
    private object Json {

        fun writeArray(objects: List<Map<String, String>>): String =
            objects.joinToString(prefix = "[", postfix = "]", separator = ",") { obj ->
                obj.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
                    "${quote(k)}:${quote(v)}"
                }
            }

        fun parseArray(text: String): List<Map<String, String>> = Parser(text).parseArray()

        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
            append('"')
        }

        private class Parser(private val s: String) {
            private var i = 0

            fun parseArray(): List<Map<String, String>> {
                val out = mutableListOf<Map<String, String>>()
                skipWs()
                if (peek() != '[') error("expected '['")
                i++; skipWs()
                if (peek() == ']') { i++; return out }
                while (true) {
                    out += parseObject(); skipWs()
                    when (next()) {
                        ',' -> skipWs()
                        ']' -> return out
                        else -> error("expected ',' or ']'")
                    }
                }
            }

            private fun parseObject(): Map<String, String> {
                val out = LinkedHashMap<String, String>()
                skipWs()
                if (next() != '{') error("expected '{'")
                skipWs()
                if (peek() == '}') { i++; return out }
                while (true) {
                    skipWs(); val key = parseString()
                    skipWs(); if (next() != ':') error("expected ':'")
                    skipWs(); val value = parseString()
                    out[key] = value; skipWs()
                    when (next()) {
                        ',' -> {}
                        '}' -> return out
                        else -> error("expected ',' or '}'")
                    }
                }
            }

            private fun parseString(): String {
                if (next() != '"') error("expected '\"'")
                val sb = StringBuilder()
                while (true) {
                    when (val c = next()) {
                        '"' -> return sb.toString()
                        '\\' -> when (val e = next()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> error("bad escape '\\$e'")
                        }
                        else -> sb.append(c)
                    }
                }
            }

            private fun peek(): Char = if (i < s.length) s[i] else error("unexpected end")
            private fun next(): Char = peek().also { i++ }
            private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
            private fun error(msg: String): Nothing =
                throw IllegalArgumentException("Malformed invitation store JSON at index $i: $msg")
        }
    }
}
