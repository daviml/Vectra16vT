package dev.turbodash.core.ecu

import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.protocol.CommandFormatter
import dev.turbodash.core.protocol.EcuProtocolException
import dev.turbodash.core.protocol.MsEnvelope
import dev.turbodash.core.protocol.Transport
import dev.turbodash.core.protocol.readExactly
import dev.turbodash.core.protocol.readUntilQuiet

/**
 * Read-only conversation with a MegaSquirt-family ECU, driven entirely by the INI.
 *
 * Writing and burning are intentionally absent: map editing comes in a later phase,
 * after reading has been validated on real hardware.
 */
class EcuClient(
    val ini: IniDefinition,
    private val transport: Transport,
    private val canId: Int = 0,
    private val timeoutMs: Long = maxOf(1000L, ini.blockReadTimeoutMs.toLong()),
) {
    private val envelope = ini.usesEnvelope

    private fun params(offset: Int = 0, count: Int = 0) = CommandFormatter.Params(canId, offset, count)

    /** Sends a command; with the envelope the response flag byte is stripped. */
    private fun request(cmd: ByteArray, expectedLen: Int?): ByteArray {
        if (envelope) {
            transport.write(MsEnvelope.wrap(cmd))
            val r = MsEnvelope.readResponse(transport, timeoutMs)
            if (expectedLen != null && r.data.size < expectedLen) {
                throw EcuProtocolException("resposta curta: ${r.data.size} de $expectedLen bytes")
            }
            return r.data
        }
        transport.write(cmd)
        return if (expectedLen != null) transport.readExactly(expectedLen, timeoutMs)
        else transport.readUntilQuiet(256, timeoutMs, 100)
    }

    /** Returns the ECU signature (e.g. `MS2Extra MegaSpeed `). */
    fun querySignature(): String {
        transport.drain()
        val bytes = request(CommandFormatter.format(ini.queryCommand, params()), null)
        return String(bytes, Charsets.ISO_8859_1).trimEnd('\u0000')
    }

    fun queryVersion(): String? {
        val cmd = ini.versionInfoCommand ?: return null
        return try {
            String(request(CommandFormatter.format(cmd, params()), null), Charsets.ISO_8859_1).trimEnd('\u0000').trim()
        } catch (e: Exception) {
            null
        }
    }

    /** True when the ECU signature matches the INI (ignoring trailing spaces/nulls). */
    fun signatureMatches(ecuSignature: String): Boolean =
        ecuSignature.trim().equals(ini.signature.trim(), ignoreCase = false)

    /** Reads the realtime output block (`ochBlockSize` bytes). */
    fun readOutputChannels(): ByteArray {
        val size = ini.ochBlockSize
        val cmd = CommandFormatter.format(ini.ochGetCommand, params(0, size))
        return request(cmd, size).copyOf(size)
    }

    /** Reads one calibration page in blocks of `blockingFactor` bytes. */
    fun readPage(page: Int, progress: (Int) -> Unit = {}): ByteArray {
        val size = ini.pageSizes[page]
        val template = ini.pageReadCommands[page]
        require(template.isNotEmpty()) { "INI sem pageReadCommand para a página ${page + 1}" }
        val out = ByteArray(size)
        val block = ini.blockingFactor.coerceAtLeast(16)
        var off = 0
        while (off < size) {
            val n = minOf(block, size - off)
            val data = request(CommandFormatter.format(template, params(off, n)), n)
            data.copyInto(out, off, 0, n)
            off += n
            progress(off)
        }
        return out
    }

    /** CRC32 of a page as computed by the ECU, or null if the INI has no CRC command. */
    fun pageCrc32(page: Int): Long? {
        val template = ini.crc32CheckCommands.getOrNull(page)?.takeIf { it.isNotEmpty() } ?: return null
        val data = request(CommandFormatter.format(template, params()), 4)
        return MsEnvelope.getU32(data, 0)
    }
}
