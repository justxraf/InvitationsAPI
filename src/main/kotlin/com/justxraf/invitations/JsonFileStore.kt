package com.justxraf.invitations

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
        // Load once on startup; after that the map is the live copy and flush() writes snapshots.
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
override fun removeAll(ids: Collection<UUID>) {
        synchronized(lock) {
            var changed = false
            for (id in ids) if (byId.remove(id) != null) changed = true
            if (changed) flush()
        }
    }
override fun replace(old: UUID, new: T) {
        synchronized(lock) {
            byId.remove(old)
            byId[new.id] = new
            flush()
        }
    }
override fun close() {
        lockHandle?.release()
    }
private fun flush() {
        // Swap a temp file into place so a crash is less likely to leave a half-written store.
        val text = Json.writeArray(byId.values.map { serializer.serialize(it) })
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text, StandardCharsets.UTF_8)
        val tmpPath = tmp.toPath()
        val target = file.toPath()
        try {
            Files.move(tmpPath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmpPath, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
private fun quarantineCorruptFile() {
        val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
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
// This store only persists flat string maps, so a tiny JSON parser keeps the dependency surface small.
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
