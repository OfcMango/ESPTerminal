package com.espterminal

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

interface LinkCallback {
    fun onConnected()
    fun onData(bytes: ByteArray)
    fun onDisconnected(reason: String?)
}

interface Link {
    fun connect()
    fun send(data: ByteArray)
    fun setLowLatency(on: Boolean)
    fun close()
}

/** Decodes a byte stream to text without garbling multi-byte characters split across chunks. */
class Utf8Stream {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)

    fun decode(chunk: ByteArray): String {
        val input = ByteBuffer.wrap(carry + chunk)
        val out = CharBuffer.allocate(input.remaining() + 2)
        decoder.reset()
        decoder.decode(input, out, false)
        carry = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
    }
}

/* ------------------------------------------------------------------ */
/*  Bluetooth Classic (SPP / BluetoothSerial on ESP32)                 */
/* ------------------------------------------------------------------ */

@SuppressLint("MissingPermission")
class ClassicLink(
    private val device: BluetoothDevice,
    private val cb: LinkCallback
) : Link {

    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    override fun connect() {
        thread(name = "spp-reader") {
            var reason: String? = null
            try {
                val s = device.createRfcommSocketToServiceRecord(sppUuid)
                socket = s
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                s.connect()
                if (closed) return@thread
                cb.onConnected()
                val input = s.inputStream
                val buf = ByteArray(2048)
                while (!closed) {
                    val n = input.read(buf)      // blocks; returns the moment bytes arrive
                    if (n < 0) break
                    if (n > 0) cb.onData(buf.copyOf(n))
                }
            } catch (e: IOException) {
                reason = e.message ?: "Connection failed"
            }
            if (!closed) {
                closed = true
                try { socket?.close() } catch (_: IOException) {}
                cb.onDisconnected(reason)
            }
        }
    }

    override fun send(data: ByteArray) {
        writer.execute {
            try {
                socket?.outputStream?.apply { write(data); flush() }
            } catch (_: IOException) { /* reader thread will report the drop */ }
        }
    }

    // Classic SPP is already low-latency: the reader thread forwards every read immediately.
    override fun setLowLatency(on: Boolean) {}

    override fun close() {
        closed = true
        writer.shutdown()
        try { socket?.close() } catch (_: IOException) {}
    }
}

/* ------------------------------------------------------------------ */
/*  BLE using the Nordic UART Service (NUS)                            */
/* ------------------------------------------------------------------ */

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleLink(
    context: Context,
    private val device: BluetoothDevice,
    private val cb: LinkCallback
) : Link {

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone -> ESP32
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // ESP32 -> phone
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var closed = false
    @Volatile private var lowLatency = false
    @Volatile private var payload = 20
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private val writing = AtomicBoolean(false)

    override fun connect() {
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun fail(reason: String?) {
        if (closed) return
        closed = true
        try { gatt?.close() } catch (_: Exception) {}
        cb.onDisconnected(reason)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (lowLatency) g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(247)) g.discoverServices()
            } else {
                fail(if (status == BluetoothGatt.GATT_SUCCESS) null else "GATT error $status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payload = mtu - 3
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NUS_SERVICE)
            val tx = svc?.getCharacteristic(NUS_TX)
            if (tx == null) { fail("Nordic UART service not found on this device"); return }
            rx = svc.getCharacteristic(NUS_RX)
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(CCCD)
            if (d == null) { fail("Notification descriptor missing"); return }
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (closed) return
            if (status == BluetoothGatt.GATT_SUCCESS) cb.onConnected()
            else fail("Could not enable notifications ($status)")
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (!closed) cb.onData(value)
        }

        // Android 12 and below
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            if (!closed) cb.onData(v)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing.set(false)
            pump()
        }
    }

    override fun send(data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val end = minOf(i + payload, data.size)
            queue.add(data.copyOfRange(i, end))
            i = end
        }
        pump()
    }

    private fun pump() {
        val ch = rx ?: return
        val g = gatt ?: return
        if (!writing.compareAndSet(false, true)) return
        val next = queue.poll()
        if (next == null) { writing.set(false); return }
        ch.writeType =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = next
        if (!g.writeCharacteristic(ch)) writing.set(false)
    }

    override fun setLowLatency(on: Boolean) {
        lowLatency = on
        gatt?.requestConnectionPriority(
            if (on) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        )
    }

    override fun close() {
        closed = true
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
    }
}
