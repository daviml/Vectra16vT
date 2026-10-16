package dev.turbodash.core.sim

import dev.turbodash.core.ini.IniDefinition
import dev.turbodash.core.ini.OutputChannel
import dev.turbodash.core.protocol.CommandFormatter
import dev.turbodash.core.protocol.MsEnvelope
import dev.turbodash.core.protocol.Transport
import dev.turbodash.core.tune.Tune
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToLong

/** Produces engine values (channel name → user units) for a session time in seconds. */
fun interface EngineSource {
    fun values(t: Double): Map<String, Double>
}

/**
 * In-process fake ECU speaking the MegaSquirt serial protocol described by the INI.
 * Answers signature, realtime, page read, CRC, write and burn commands, so the whole app
 * can be developed and tested without a car.
 */
class SimulatedEcu(
    private val ini: IniDefinition,
    tune: Tune,
    private val source: EngineSource,
    private val clock: () -> Double = SystemClock(),
    private val signature: String = ini.signature,
) : Transport {

    val pages: List<ByteArray> = tune.pages.map { it.copyOf() }

    private val pageIdToIndex: Map<Int, Int> = ini.pageIdentifiers.mapIndexedNotNull { i, id ->
        if (id.isEmpty()) null else CommandFormatter.format(id).lastOrNull()?.let { (it.toInt() and 0xFF) to i }
    }.toMap()

    private val ochTableId: Int = CommandFormatter.format(ini.ochGetCommand).let { c ->
        if (c.size >= 3 && c[0] == 'r'.code.toByte()) c[2].toInt() and 0xFF else 7
    }

    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val rx = java.io.ByteArrayOutputStream() // host → ECU, pending frame bytes
    private var tx = ByteArray(0)                      // ECU → host
    private var txPos = 0
    @Volatile private var closed = false

    // ------------------------------------------------------------------ Transport

    override fun write(data: ByteArray) {
        if (closed) throw IOException("simulador fechado")
        lock.withLock {
            rx.write(data)
            processPending()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        lock.withLock {
            while (txPos >= tx.size) {
                if (closed) throw IOException("simulador fechado")
                val left = deadline - System.nanoTime()
                if (left <= 0) return 0
                available.awaitNanos(left)
            }
            val n = minOf(length, tx.size - txPos)
            tx.copyInto(buffer, offset, txPos, txPos + n)
            txPos += n
            return n
        }
    }

    override fun drain() {
        lock.withLock { tx = ByteArray(0); txPos = 0 }
    }

    override fun close() {
        closed = true
        lock.withLock { available.signalAll() }
    }

    // ------------------------------------------------------------------ command handling

    private fun processPending() {
        while (true) {
            val buf = rx.toByteArray()
            if (buf.isEmpty()) return
            if (ini.usesEnvelope) {
                if (buf.size < 2) return
                val len = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                if (buf.size < 2 + len + 4) return
                val payload = buf.copyOfRange(2, 2 + len)
                val crcOk = MsEnvelope.getU32(buf, 2 + len) == MsEnvelope.crc32(payload)
                consume(buf, 2 + len + 4)
                val (flag, data) = if (crcOk) handle(payload) else 0x82 to ByteArray(0)
                val body = ByteArray(data.size + 1).also { it[0] = flag.toByte(); data.copyInto(it, 1) }
                emit(MsEnvelope.wrap(body))
            } else {
                // Legacy: single-character commands only.
                consume(buf, 1)
                val (_, data) = handle(byteArrayOf(buf[0]))
                emit(data)
            }
        }
    }

    private fun consume(buf: ByteArray, n: Int) {
        rx.reset()
        if (buf.size > n) rx.write(buf, n, buf.size - n)
    }

    private fun emit(bytes: ByteArray) {
        val remaining = tx.copyOfRange(txPos, tx.size)
        tx = remaining + bytes
        txPos = 0
        available.signalAll()
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /** Returns (flag, data) for a request payload. */
    private fun handle(p: ByteArray): Pair<Int, ByteArray> {
        return when (p[0].toInt().toChar()) {
            'Q' -> 0x00 to (signature + "\u0000").toByteArray(Charsets.ISO_8859_1)
            'S' -> 0x00 to "TurboDash simulador\u0000".toByteArray(Charsets.ISO_8859_1)
            'A' -> 0x01 to outputBlock()
            'r' -> {
                if (p.size < 7) return 0x80 to ByteArray(0)
                val table = p[2].toInt() and 0xFF
                val off = u16(p, 3)
                val cnt = u16(p, 5)
                if (table == ochTableId) {
                    val och = outputBlock()
                    0x01 to och.copyOfRange(off.coerceAtMost(och.size), (off + cnt).coerceAtMost(och.size))
                } else {
                    val page = pageIdToIndex[table] ?: return 0x84 to ByteArray(0)
                    val bytes = pages[page]
                    if (off + cnt > bytes.size) return 0x84 to ByteArray(0)
                    0x02 to bytes.copyOfRange(off, off + cnt)
                }
            }
            'k' -> {
                val page = pageIdToIndex[p.getOrNull(2)?.toInt()?.and(0xFF) ?: -1] ?: return 0x84 to ByteArray(0)
                val crc = MsEnvelope.crc32(pages[page])
                0x00 to ByteArray(4).also { MsEnvelope.putU32(it, 0, crc) }
            }
            'w' -> {
                if (p.size < 7) return 0x80 to ByteArray(0)
                val page = pageIdToIndex[p[2].toInt() and 0xFF] ?: return 0x84 to ByteArray(0)
                val off = u16(p, 3)
                val cnt = u16(p, 5)
                val bytes = pages[page]
                if (off + cnt > bytes.size || p.size < 7 + cnt) return 0x84 to ByteArray(0)
                p.copyInto(bytes, off, 7, 7 + cnt)
                0x00 to ByteArray(0)
            }
            'b' -> 0x04 to ByteArray(0)
            else -> 0x83 to ByteArray(0)
        }
    }

    // ------------------------------------------------------------------ realtime encoding

    private fun outputBlock(): ByteArray {
        val block = ByteArray(ini.ochBlockSize)
        val values = source.values(clock())
        val tune = Tune(ini, pages)
        for ((name, v) in values) {
            val ch = ini.outputChannels[name] as? OutputChannel.Scalar ?: continue
            val scale = tune.resolve(ch.scale, 1.0).takeIf { it != 0.0 } ?: 1.0
            val raw = (v / scale - ch.translate).roundToLong()
            write(block, ch.offset, ch.type.size, raw)
        }
        return block
    }

    private fun write(b: ByteArray, offset: Int, size: Int, v: Long) {
        if (offset < 0 || offset + size > b.size) return
        for (i in 0 until size) {
            val idx = if (ini.endianBig) offset + i else offset + size - 1 - i
            b[idx] = (v shr (8 * (size - 1 - i))).toByte()
        }
    }

    /** Wall-clock seconds since creation. */
    class SystemClock : () -> Double {
        private val start = System.nanoTime()
        override fun invoke() = (System.nanoTime() - start) / 1e9
    }
}
