package com.justxraf.invitations
/**
 * Minimal, dependency-free JSON-ish codec for flat string→string field maps. Shared by
 * [JsonFileStore] and the SQL `fields` blob so the core needs no kotlinx.serialization/Jackson.
 * Internal: not part of the public API.
 */
internal object FieldCodec {

    fun encode(fields: Map<String, String>): String =
        fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "${quote(k)}:${quote(v)}"
        }

    fun decode(text: String): Map<String, String> = Parser(text).parseObject()

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

        fun parseObject(): Map<String, String> {
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
            throw IllegalArgumentException("Malformed invitation fields at index $i: $msg")
    }
}
