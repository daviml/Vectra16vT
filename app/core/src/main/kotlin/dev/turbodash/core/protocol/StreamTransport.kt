package dev.turbodash.core.protocol

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [Transport] over blocking streams (Bluetooth RFCOMM socket, TCP...).
 *
 * Bluetooth input streams cannot time out, so a pump thread copies incoming bytes into a buffer
 * and [read] waits on that buffer with a deadline.
 */
class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : Transport {

    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private var buf = ByteArray(8192)
    private var head = 0
    private var tail = 0
    @Volatile private var closed = false
    @Volatile private var failure: IOException? = null

    private val pump = Thread({
        val chunk = ByteArray(1024)
        try {
            while (!closed) {
                val n = input.read(chunk)
                if (n < 0) throw IOException("conexão encerrada pelo dispositivo")
                if (n == 0) continue
                lock.withLock {
                    ensureCapacity(n)
                    chunk.copyInto(buf, tail, 0, n)
                    tail += n
                    available.signalAll()
                }
            }
        } catch (e: IOException) {
            if (!closed) failure = e
        } finally {
            lock.withLock { available.signalAll() }
        }
    }, "ecu-rx").apply { isDaemon = true; start() }

    private fun ensureCapacity(extra: Int) {
        if (tail + extra <= buf.size) return
        val used = tail - head
        if (used + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, used + extra + 1024).let { it })
        buf.copyInto(buf, 0, head, tail)
        head = 0
        tail = used
    }

    override fun write(data: ByteArray) {
        failure?.let { throw it }
        if (closed) throw IOException("transporte fechado")
        output.write(data)
        output.flush()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        lock.withLock {
            while (tail == head) {
                failure?.let { throw it }
                if (closed) throw IOException("transporte fechado")
                val left = deadline - System.nanoTime()
                if (left <= 0) return 0
                available.awaitNanos(left)
            }
            val n = minOf(length, tail - head)
            buf.copyInto(buffer, offset, head, head + n)
            head += n
            if (head == tail) { head = 0; tail = 0 }
            return n
        }
    }

    override fun drain() {
        lock.withLock { head = 0; tail = 0 }
    }

    override fun close() {
        closed = true
        try { input.close() } catch (_: IOException) {}
        try { output.close() } catch (_: IOException) {}
        onClose()
        lock.withLock { available.signalAll() }
    }
}
