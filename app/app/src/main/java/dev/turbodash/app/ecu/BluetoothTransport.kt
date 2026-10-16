package dev.turbodash.app.ecu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import dev.turbodash.core.protocol.StreamTransport
import dev.turbodash.core.protocol.Transport
import java.io.IOException
import java.util.UUID

/** Classic Bluetooth serial (SPP), as used by HC-05/HC-06 modules on MegaSquirt boards. */
object BluetoothTransport {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Caller must hold BLUETOOTH_CONNECT on Android 12+. */
    @SuppressLint("MissingPermission")
    fun open(adapter: BluetoothAdapter, address: String): Transport {
        if (!adapter.isEnabled) throw IOException("Bluetooth desligado")
        val device = adapter.getRemoteDevice(address)
        // Only matters if a scan is running; on Android 12+ it needs BLUETOOTH_SCAN, which we don't ask for.
        try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
        val socket = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (first: IOException) {
            // Many HC-05 clones only accept the channel-1 fallback.
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            } catch (e: Exception) {
                throw IOException("Não foi possível conectar em $address: ${first.message}")
            }
        }
        return StreamTransport(socket.inputStream, socket.outputStream) {
            try { socket.close() } catch (_: IOException) {}
        }
    }
}
