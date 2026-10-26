package com.espterminal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DeviceItem(val device: BluetoothDevice, val ble: Boolean, val name: String) {
    val address: String get() = device.address
}

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 42
        private const val MAX_CHARS = 60_000   // terminal scrollback cap
        private const val KEEP_CHARS = 40_000  // what remains after trimming
        private const val BATCH_MS = 120L      // UI refresh interval when Live mode is OFF
    }

    private lateinit var status: TextView
    private lateinit var connectBtn: Button
    private lateinit var liveSwitch: Switch
    private lateinit var speakSwitch: Switch
    private lateinit var autoScroll: CheckBox
    private lateinit var terminal: TextView
    private lateinit var input: EditText
    private lateinit var eol: Spinner
    private lateinit var stats: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val sb = StringBuilder()
    private val flushQueued = AtomicBoolean(false)
    private val rxBytes = AtomicLong(0)
    @Volatile private var live = false
    private var link: Link? = null
    private var afterPermission: (() -> Unit)? = null

    // ---- text-to-speech ----
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakOn = false
    private val speechBuf = StringBuilder()
    private val speakPartial = Runnable { drainSpeech(includePartial = true) }

    private val btAdapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val flush = Runnable {
        flushQueued.set(false)
        val snapshot = synchronized(sb) { sb.toString() }
        terminal.text = snapshot
        if (autoScroll.isChecked) {
            scrollToEnd()
            terminal.post { scrollToEnd() }
        }
        stats.text = "RX ${rxBytes.get()} B"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        connectBtn = findViewById(R.id.connectBtn)
        liveSwitch = findViewById(R.id.liveSwitch)
        speakSwitch = findViewById(R.id.speakSwitch)
        autoScroll = findViewById(R.id.autoScroll)
        terminal = findViewById(R.id.terminal)
        input = findViewById(R.id.input)
        eol = findViewById(R.id.eol)
        stats = findViewById(R.id.stats)

        terminal.movementMethod = ScrollingMovementMethod()
        eol.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("No ending", "\\n", "\\r", "\\r\\n")
        )
        eol.setSelection(1)

        connectBtn.setOnClickListener {
            if (link != null) disconnect() else withPermissions { showDevicePicker() }
        }
        findViewById<Button>(R.id.sendBtn).setOnClickListener { sendInput() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendInput(); true } else false
        }
        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            synchronized(sb) { sb.setLength(0) }
            rxBytes.set(0)
            terminal.text = ""
            stats.text = "RX 0 B"
        }
        findViewById<Button>(R.id.copyBtn).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ESP terminal", terminal.text))
            toast("Copied")
        }

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                ttsReady = true
            } else {
                toast("No text-to-speech engine found on this phone")
            }
        }
        speakSwitch.setOnCheckedChangeListener { _, on ->
            speakOn = on
            if (!on) {
                tts?.stop()
                ui.removeCallbacks(speakPartial)
                speechBuf.setLength(0)
            } else if (!ttsReady) {
                toast("Speech engine is still starting, try again in a moment")
            }
        }

        liveSwitch.setOnCheckedChangeListener { _, on ->
            live = on
            link?.setLowLatency(on)
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // push out anything waiting in the batch timer right now
            ui.removeCallbacks(flush)
            flushQueued.set(false)
            if (flushQueued.compareAndSet(false, true)) ui.post(flush)
            refreshStatus()
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        link?.close()
        super.onDestroy()
    }

    /* ---------------- terminal ---------------- */

    private fun append(raw: String) {
        val s = raw.replace("\r", "")
        if (s.isEmpty()) return
        synchronized(sb) {
            sb.append(s)
            if (sb.length > MAX_CHARS) {
                var cut = sb.length - KEEP_CHARS
                val nl = sb.indexOf("\n", cut)
                if (nl in 0..(cut + 2000)) cut = nl + 1
                sb.delete(0, cut)
            }
        }
        if (flushQueued.compareAndSet(false, true)) {
            // Live mode: repaint on the very next UI tick, nothing is held back.
            if (live) ui.post(flush) else ui.postDelayed(flush, BATCH_MS)
        }
    }

    /* ---------------- speech ---------------- */

    // Called on the UI thread with each decoded chunk. Speaks complete lines; a trailing
    // fragment with no newline is spoken after a short pause so nothing gets stuck.
    private fun speakIncoming(s: String) {
        if (!speakOn || !ttsReady) return
        speechBuf.append(s.replace("\r", ""))
        ui.removeCallbacks(speakPartial)
        drainSpeech(includePartial = false)
        if (speechBuf.isNotEmpty()) ui.postDelayed(speakPartial, 400)
    }

    private fun drainSpeech(includePartial: Boolean) {
        val lines = ArrayList<String>()
        while (true) {
            val i = speechBuf.indexOf("\n")
            if (i < 0) break
            lines.add(speechBuf.substring(0, i))
            speechBuf.delete(0, i + 1)
        }
        if (includePartial && speechBuf.isNotEmpty()) {
            lines.add(speechBuf.toString())
            speechBuf.setLength(0)
        }
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            // Live mode: always jump to the newest line (no backlog). Otherwise queue every line.
            tts?.speak(
                t,
                if (live) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "esp"
            )
        }
    }

    private fun scrollToEnd() {
        val l = terminal.layout ?: return
        val visible = terminal.height - terminal.totalPaddingTop - terminal.totalPaddingBottom
        terminal.scrollTo(0, maxOf(0, l.getLineTop(terminal.lineCount) - visible))
    }

    private fun sendInput() {
        val l = link ?: return toast("Not connected")
        val ending = when (eol.selectedItemPosition) { 1 -> "\n"; 2 -> "\r"; 3 -> "\r\n"; else -> "" }
        val typed = input.text.toString()
        val text = typed + ending
        if (text.isEmpty()) return
        l.send(text.toByteArray(Charsets.UTF_8))
        append("» $typed\n")
        input.text.clear()
    }

    /* ---------------- connection ---------------- */

    private fun connectTo(item: DeviceItem) {
        setStatus("Connecting to ${item.name}…", Color.parseColor("#FFCC00"))
        val decoder = Utf8Stream()
        val cb = object : LinkCallback {
            override fun onConnected() = runOnUiThread {
                connectBtn.text = "Disconnect"
                refreshStatus(item.name)
                link?.setLowLatency(live)
            }
            override fun onData(bytes: ByteArray) {
                rxBytes.addAndGet(bytes.size.toLong())
                val text = decoder.decode(bytes)
                append(text)
                if (text.isNotEmpty()) ui.post { speakIncoming(text) }
            }
            override fun onDisconnected(reason: String?) = runOnUiThread {
                link = null
                connectBtn.text = "Connect"
                setStatus(
                    if (reason == null) "Disconnected" else "Disconnected: $reason",
                    Color.parseColor("#FF5555")
                )
            }
        }
        link = if (item.ble) BleLink(this, item.device, cb) else ClassicLink(item.device, cb)
        connectBtn.text = "Cancel"
        link!!.connect()
    }

    private fun disconnect() {
        link?.close()
        link = null
        connectBtn.text = "Connect"
        setStatus("Disconnected", Color.parseColor("#FF5555"))
    }

    private var connectedName: String? = null
    private fun refreshStatus(name: String? = null) {
        if (name != null) connectedName = name
        if (link == null) return
        val n = connectedName ?: return
        setStatus(
            if (live) "● $n  ⚡ LIVE" else "● $n",
            Color.parseColor(if (live) "#00E5FF" else "#33FF66")
        )
    }

    private fun setStatus(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
    }

    /* ---------------- device picker ---------------- */

    private fun showDevicePicker() {
        val adapter = btAdapter ?: return toast("This device has no Bluetooth")
        if (!adapter.isEnabled) return toast("Turn Bluetooth on first")

        val items = ArrayList<DeviceItem>()
        adapter.bondedDevices?.forEach { items.add(DeviceItem(it, false, it.name ?: "Unknown")) }

        val listAdapter = object : ArrayAdapter<DeviceItem>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val d = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = d.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    "${d.address}  ·  ${if (d.ble) "BLE" else "Classic (paired)"}"
                return v
            }
        }

        val scanner = adapter.bluetoothLeScanner
        val scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val dev = r.device
                if (items.none { it.ble && it.address == dev.address }) {
                    val name = r.scanRecord?.deviceName ?: dev.name ?: "Unknown"
                    items.add(DeviceItem(dev, true, name))
                    listAdapter.notifyDataSetChanged()
                }
            }
        }
        try {
            scanner?.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCb
            )
        } catch (e: Exception) {
            toast("BLE scan failed: ${e.message}")
        }

        AlertDialog.Builder(this)
            .setTitle("Select your ESP32")
            .setAdapter(listAdapter) { _, which -> connectTo(items[which]) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Pair (Classic)") { _, _ ->
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setOnDismissListener {
                try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
            }
            .show()
    }

    /* ---------------- permissions ---------------- */

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun withPermissions(action: () -> Unit) {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action()
        else {
            afterPermission = action
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED })
                afterPermission?.invoke()
            else toast("Bluetooth permission is required")
            afterPermission = null
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
