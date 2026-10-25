package com.eelab.rflab.ota

/**
 * Golden OTA vector fixture, parsed from the committed copy of
 * `esp32c3-cc1101-lab/ota/OTA_VECTORS.json` under `app/src/test/resources`.
 *
 * `org.json` is only a stubbed android.jar class on the JVM test classpath
 * (returnDefaultValues cannot make parsing work), so this fixture ships a
 * minimal purpose-built JSON parser for the document's known shape: objects,
 * arrays, strings, and numbers. Structural mistakes surface as failed tests
 * (missing keys / unreadable values), never as silently wrong bytes.
 */
object OtaGolden {

    /** The parsed form of the vectors document, loaded once per process. */
    val document: Map<String, Any?> by lazy { parseResource() }

    data class PacketVec(val rawHex: String, val wireHex: String)

    data class ChunkVec(val offset: Long, val rawHex: String, val wireHex: String)

    data class Vectors(
        val secret: ByteArray,
        val nonce: ByteArray,
        val image: ByteArray,
        val imageSha256Hex: String,
        val maxChunk: Int,
        val hello: PacketVec,
        val helloRespOk: PacketVec,
        val begin: PacketVec,
        val beginRespOk: PacketVec,
        val chunks: List<ChunkVec>,
        val chunkRespFirst: PacketVec,
        val chunkRespBadOffset: PacketVec,
        val end: PacketVec,
        val endRespOk: PacketVec,
        val abort: PacketVec,
        val bootloader: PacketVec,
        val badMacBegin: PacketVec,
    )

    /** The typed vectors; the fixture text is parsed once per process. */
    val parsed: Vectors by lazy {
        fun packet(key: String): PacketVec {
            val obj = document.objectAt(key)
            return PacketVec(rawHex = obj.stringAt("raw"), wireHex = obj.stringAt("wire"))
        }

        val chunks = document.arrayAt("chunks").map { element ->
            val obj = element as Map<String, Any?>
            ChunkVec(
                offset = obj.longAt("offset"),
                rawHex = obj.stringAt("raw"),
                wireHex = obj.stringAt("wire"),
            )
        }

        Vectors(
            secret = document.stringAt("secret_hex").hexToBytes(),
            nonce = document.stringAt("nonce_hex").hexToBytes(),
            image = document.longAt("image_len").ruleImage(),
            imageSha256Hex = document.stringAt("image_sha256"),
            maxChunk = document.longAt("max_chunk").toInt(),
            hello = packet("hello"),
            helloRespOk = packet("hello_resp_ok"),
            begin = packet("begin"),
            beginRespOk = packet("begin_resp_ok"),
            chunks = chunks,
            chunkRespFirst = packet("chunk_resp_first"),
            chunkRespBadOffset = packet("chunk_resp_bad_offset"),
            end = packet("end"),
            endRespOk = packet("end_resp_ok"),
            abort = packet("abort"),
            bootloader = packet("bootloader"),
            badMacBegin = packet("bad_mac_begin"),
        )
    }
}

/** The image spelled by the fixture's rule, `image[i] = (i*7+3) & 0xFF`. */
private fun Long.ruleImage(): ByteArray =
    ByteArray(this.toInt()) { index -> ((index * 7 + 3) and 0xFF).toByte() }

private fun parseResource(): Map<String, Any?> {
    val text = OtaGolden::class.java.getResourceAsStream(RESOURCE_PATH)
        ?.use { stream -> stream.readBytes().decodeToString() }
        ?: throw AssertionError("OTA_VECTORS.json is missing from test resources")
    return TinyJsonParser(text).parseValue() as Map<String, Any?>
}

private const val RESOURCE_PATH = "/OTA_VECTORS.json"

/**
 * A recursive-descent JSON reader for the fixture's JSON subset: objects,
 * arrays, strings (escapes supported), numbers (returned as Long),
 * true/false/null.
 */
internal class TinyJsonParser(private val text: String) {

    private var pos = 0

    fun parseValue(): Any? {
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            else -> parseNumber()
        }
    }

    private fun parseObject(): MutableMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        expect('{')
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            when (read()) {
                ',' -> continue
                '}' -> return result
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): MutableList<Any?> {
        val result = ArrayList<Any?>()
        expect('[')
        if (peek() == ']') {
            pos++
            return result
        }
        while (true) {
            result += parseValue()
            skipWhitespace()
            when (read()) {
                ',' -> continue
                ']' -> return result
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val c = read()
            if (c == '"') return out.toString()
            if (c == '\\') {
                when (val escape = read()) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        val digits = text.substring(pos, pos + 4)
                        pos += 4
                        out.append(digits.toInt(16).toChar())
                    }

                    else -> fail("unsupported escape \\$escape")
                }
            } else {
                out.append(c)
            }
        }
    }

    private fun parseNumber(): Long {
        val start = pos
        while (pos < text.length && text[pos] in "+-0123456789.") {
            pos++
        }
        return text.substring(start, pos).toLongOrNull() ?: fail("bad number")
    }

    private fun peek(): Char {
        skipWhitespace()
        if (pos >= text.length) fail("unexpected end of input")
        return text[pos]
    }

    private fun read(): Char {
        if (pos >= text.length) fail("unexpected end of input")
        return text[pos++]
    }

    private fun expect(expected: Char) {
        if (read() != expected) fail("expected '$expected'")
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun fail(message: String): Nothing =
        throw AssertionError("OTA_VECTORS.json parse error at index $pos: $message")
}

// --- structural accessors (clear assertion failures if the fixture changes) ---

internal fun Map<String, Any?>.objectAt(key: String): Map<String, Any?> =
    get(key) as Map<String, Any?>? ?: missing(key, "object")

internal fun Map<String, Any?>.stringAt(key: String): String =
    get(key) as String? ?: missing(key, "string")

internal fun Map<String, Any?>.longAt(key: String): Long =
    get(key) as Long? ?: missing(key, "number")

internal fun Map<String, Any?>.arrayAt(key: String): List<Any?> =
    get(key) as List<Any?>? ?: missing(key, "array")

private fun missing(key: String, type: String): Nothing =
    throw AssertionError("OTA_VECTORS.json: `$key` ($type) missing or mistyped")

// --- hex helpers shared by the OTA tests --------------------------------------

private const val HEX = "0123456789abcdef"

internal fun String.hexToBytes(): ByteArray =
    chunked(2).map { token -> token.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        out.append(HEX[(byte.toInt() ushr 4) and 0xF])
        out.append(HEX[byte.toInt() and 0xF])
    }
    return out.toString()
}

/** Reads a little-endian u16 out of [payload] at [offset]. */
internal fun readU16Le(payload: ByteArray, offset: Int): Int =
    (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)

/** Reads a little-endian u32 out of [payload] at [offset]. */
internal fun readU32Le(payload: ByteArray, offset: Int): Long =
    (payload[offset].toLong() and 0xFF) or
        ((payload[offset + 1].toLong() and 0xFF) shl 8) or
        ((payload[offset + 2].toLong() and 0xFF) shl 16) or
        ((payload[offset + 3].toLong() and 0xFF) shl 24)
