package dev.turbodash.core.protocol

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.zip.CRC32

/** Byte pipe to the ECU (Bluetooth SPP, USB serial, simulator...). */
interface Transport : Closeable {
    fun write(data: ByteArray)

    /**
     * Reads up to [length] bytes, waiting at most [timeoutMs] for the first byte.
     * Returns the number of bytes read, 0 on timeout. Throws [IOException] when the link is closed.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int

    /** Discards any bytes waiting to be read. */
    fun drain()
}

class EcuTimeoutException(message: String) : IOException(message)
class EcuProtocolException(message: String) : IOException(message)

/** Reads exactly [n] bytes or throws [EcuTimeoutException]. */
fun Transport.readExactly(n: Int, timeoutMs: Long): ByteArray {
    val out = ByteArray(n)
    var got = 0
    val deadline = System.currentTimeMillis() + timeoutMs
    while (got < n) {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) throw EcuTimeoutException("timeout: recebidos $got de $n bytes")
        got += read(out, got, n - got, left)
    }
    return out
}

/** Reads whatever arrives until the line has been quiet for [quietMs] (legacy commands with unknown length). */
fun Transport.readUntilQuiet(maxBytes: Int, firstByteTimeoutMs: Long, quietMs: Long): ByteArray {
    val buf = ByteArray(maxBytes)
    var got = read(buf, 0, maxBytes, firstByteTimeoutMs)
    if (got == 0) return ByteArray(0)
    while (got < maxBytes) {
        val n = read(buf, got, maxBytes - got, quietMs)
        if (n == 0) break
        got += n
    }
    return buf.copyOf(got)
}

/**
 * MegaSquirt "new serial" envelope (msEnvelope_1.0):
 * request  = u16 length, payload, u32 CRC32(payload)
 * response = u16 length, [flag, data...], u32 CRC32(flag + data)
 * All big-endian. Flags >= 0x80 are errors.
 */
object MsEnvelope {

    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Long =
        CRC32().apply { update(data, offset, length) }.value

    fun wrap(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 6)
        out[0] = (payload.size shr 8).toByte()
        out[1] = payload.size.toByte()
        payload.copyInto(out, 2)
        putU32(out, 2 + payload.size, crc32(payload))
        return out
    }

    class Response(val flag: Int, val data: ByteArray) {
        val isError get() = flag >= 0x80
    }

    fun readResponse(t: Transport, timeoutMs: Long): Response {
        val len = t.readExactly(2, timeoutMs).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
        if (len == 0) throw EcuProtocolException("resposta vazia")
        val body = t.readExactly(len, timeoutMs)
        val crc = getU32(t.readExactly(4, timeoutMs), 0)
        if (crc != crc32(body)) {
            t.drain()
            throw EcuProtocolException("CRC inválido na resposta")
        }
        val flag = body[0].toInt() and 0xFF
        val r = Response(flag, body.copyOfRange(1, body.size))
        if (r.isError) throw EcuProtocolException("ECU respondeu erro 0x%02X (%s)".format(flag, errorName(flag)))
        return r
    }

    fun errorName(flag: Int) = when (flag) {
        0x80 -> "underrun"
        0x81 -> "overrun"
        0x82 -> "CRC"
        0x83 -> "comando desconhecido"
        0x84 -> "fora de faixa"
        0x85 -> "ocupado"
        0x86 -> "flash travada"
        0x8C -> "paridade"
        0x8D -> "framing"
        0x8E -> "ruído serial"
        else -> "desconhecido"
    }

    fun putU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v shr 24).toByte(); b[at + 1] = (v shr 16).toByte()
        b[at + 2] = (v shr 8).toByte(); b[at + 3] = v.toByte()
    }

    fun getU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}

/**
 * Expands TunerStudio command templates such as `r\$tsCanId\x04%2o%2c`:
 * `\$tsCanId` → CAN id byte, `\xNN` → byte, `%2o` → u16 offset, `%2c` → u16 count,
 * `%2i` → u16 table id, `%v` → value bytes. Everything else is sent as ASCII.
 */
object CommandFormatter {

    class Params(val canId: Int = 0, val offset: Int = 0, val count: Int = 0, val tableId: Int = 0, val value: ByteArray? = null)

    fun format(template: String, p: Params = Params()): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        val s = template
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && s.startsWith("\\\\", i) -> i++ // "\\x00" written with a doubled backslash
                c == '\\' && s.startsWith("\\\$tsCanId", i) -> { out.write(p.canId); i += 9 }
                c == '\\' && s.startsWith("\\x", i) && i + 4 <= s.length -> {
                    out.write(s.substring(i + 2, i + 4).toInt(16)); i += 4
                }
                c == '$' && s.startsWith("\$tsCanId", i) -> { out.write(p.canId); i += 8 }
                c == '%' && i + 1 < s.length -> {
                    // %v, or %<n><o|c|i>
                    if (s[i + 1] == 'v') {
                        p.value?.let { out.write(it) }; i += 2
                    } else {
                        var j = i + 1
                        while (j < s.length && s[j].isDigit()) j++
                        val size = s.substring(i + 1, j).toIntOrNull() ?: 2
                        val kind = s.getOrNull(j)
                        val v = when (kind) { 'o' -> p.offset; 'c' -> p.count; 'i' -> p.tableId; else -> 0 }
                        for (k in size - 1 downTo 0) out.write((v shr (8 * k)) and 0xFF)
                        i = j + 1
                    }
                }
                else -> { out.write(c.code and 0xFF); i++ }
            }
        }
        return out.toByteArray()
    }
}
