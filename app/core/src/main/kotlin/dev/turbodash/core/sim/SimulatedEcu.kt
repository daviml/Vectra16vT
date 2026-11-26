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
                    return 0x01 to och.copyOfRange(off.coerceAtMost(och.size), (off + cnt).coerceAtMost(och.size))
                }
                // MS2/Extra `serial.c`: longer reads are refused before the table is looked at.
                if (cnt > maxRead) return 0x84 to ByteArray(0)
                if (table in LOGGER_TABLES) return 0x01 to loggerRead(off, cnt)
                val page = pageIdToIndex[table] ?: return 0x84 to ByteArray(0)
                val bytes = pages[page]
                if (off + cnt > bytes.size) return 0x84 to ByteArray(0)
                // The calibration is copied back into the RAM the logger uses: the logger stops.
                loggerOn = false
                ramIsLogger = false
                0x02 to bytes.copyOfRange(off, off + cnt)
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

    // ------------------------------------------------------------------ composite logger (page 0xf2)

    // Modelled on MS2/Extra 3.4.3 (`serial.c`, `ck_log_clr` in `ms2_extra_misc.c`, `isr_ign.s`):
    // the logger buffer is the tuning page RAM; any read of a logger table stops the logger, the read
    // that ends at 0x400 arms it again with an empty buffer, and reading a calibration page switches
    // it off (the RAM gets the calibration back).

    /** Complete passes over the logger buffer, i.e. times the logger was armed (tests). */
    @Volatile var loggerReads = 0
        private set
    /** True while the logger records edges. */
    @Volatile var loggerOn = false
        private set
    private var ramIsLogger = false
    private var ram = ByteArray(LOGGER_SIZE)
    private var loggerEdges = 0.0
    private var loggerClock = 0.0
    private val maxRead = ini.blockingFactor.takeIf { it > 0 } ?: Int.MAX_VALUE

    private fun loggerRead(off: Int, cnt: Int): ByteArray {
        if (loggerOn) { loggerFull(); ram = loggerBuffer(loggerEdges.toInt()); loggerOn = false }
        if (!ramIsLogger) { ram = ByteArray(LOGGER_SIZE); ramIsLogger = true } // first read: page zapped
        val data = ram.copyOfRange(off.coerceAtMost(LOGGER_SIZE), (off + cnt).coerceAtMost(LOGGER_SIZE))
        if (off + cnt == LOGGER_SIZE) {
            // Last piece read: logging kicked off again.
            ram = ByteArray(LOGGER_SIZE)
            loggerOn = true
            loggerEdges = 0.0
            loggerClock = clock()
            loggerReads++
        }
        return data
    }

    /** Counts trigger edges while the logger is on (60-2 wheel = 58 teeth per turn); true when full. */
    private fun loggerFull(): Boolean {
        if (!loggerOn) return false
        val now = clock()
        val rpm = source.values(now)["rpm"] ?: 0.0
        loggerEdges = (loggerEdges + rpm / 60.0 * 58.0 * (now - loggerClock).coerceAtLeast(0.0)).coerceAtMost(RECORDS.toDouble())
        loggerClock = now
        return loggerEdges >= RECORDS
    }

    /**
     * 341 records of 3 bytes: bit 23 secondary pin level, 22 primary pin level, 21 edge from the
     * secondary input, 20 synced, bits 0-19 time since the previous edge in 2/3 us ticks.
     * Records not reached are zeros; the last byte is the format version once the buffer is full.
     */
    private fun loggerBuffer(edges: Int): ByteArray {
        val out = ByteArray(LOGGER_SIZE)
        val n = edges.coerceIn(0, RECORDS)
        val rpm = (source.values(clock())["rpm"] ?: 0.0).coerceAtLeast(50.0)
        val ticks = (60.0 / rpm / 60.0 / TICK_S).toLong().coerceAtMost(0xFFFFF)
        for (i in 0 until n) {
            val gap = if (i % 58 == 57) (ticks * 3).coerceAtMost(0xFFFFF) else ticks // missing teeth
            val level = if (i % 2 == 0) 0x40L shl 16 else 0L
            val v = (0x10L shl 16) or level or gap
            out[i * 3] = (v shr 16).toByte(); out[i * 3 + 1] = (v shr 8).toByte(); out[i * 3 + 2] = v.toByte()
        }
        if (n == RECORDS) out[LOGGER_SIZE - 1] = 1
        return out
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
        if (loggerFull()) (ini.outputChannels["status3"] as? OutputChannel.Scalar)?.let { ch ->
            if (ch.offset in block.indices) block[ch.offset] = (block[ch.offset].toInt() or 0x02).toByte()
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

    private companion object {
        val LOGGER_TABLES = 0xf0..0xf8
        const val LOGGER_SIZE = 1024
        const val RECORDS = LOGGER_SIZE / 3
        /** 1.5 MHz timer of the MS2. */
        const val TICK_S = 2.0 / 3.0 * 1e-6
    }

    /** Wall-clock seconds since creation. */
    class SystemClock : () -> Double {
        private val start = System.nanoTime()
        override fun invoke() = (System.nanoTime() - start) / 1e9
    }
}
