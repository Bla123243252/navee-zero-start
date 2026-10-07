package com.navee.zerostart

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// ─────────────────────────────────────────────────────────────
// Navee BLE Konstanten
// ─────────────────────────────────────────────────────────────
private val SERVICE_UUID  = UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212")
private val WRITE_CHR_UUID = UUID.fromString("0000b002-0000-1000-8000-00805f9b34fb")
private val NOTIFY_CHR_UUID = UUID.fromString("0000b003-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val CMD_AUTH_INIT:  Byte = 0x30
private const val CMD_AUTH_RESP:  Byte = 0x31
private const val CMD_READ_PARAMS:Byte = 0x70
private const val CMD_START_SPEED:Byte = 0x6A.toByte()

// AES-128 Keys aus der offiziellen Navee App v2.1.6
private val AES_KEYS = arrayOf(
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x31),
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x32),
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x33),
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x34),
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x35),
)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var btnConnect: Button
    private lateinit var btnZeroStart: Button
    private lateinit var btnCruise: Button
    private lateinit var btnLock: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var inputUserId: EditText

    // BLE
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChr: BluetoothGattCharacteristic? = null
    private var notifyChr: BluetoothGattCharacteristic? = null
    private var connected = false
    private var authed = false

    // Response Handler
    private var pendingResponse: CompletableDeferred<ByteArray>? = null
    private val rxBuffer = mutableListOf<Byte>()

    // State
    private var zeroStartEnabled = false
    private var cruiseEnabled = false
    private var lockEnabled = false

    // ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect   = findViewById(R.id.btnConnect)
        btnZeroStart = findViewById(R.id.btnZeroStart)
        btnCruise    = findViewById(R.id.btnCruise)
        btnLock      = findViewById(R.id.btnLock)
        tvStatus     = findViewById(R.id.tvStatus)
        tvLog        = findViewById(R.id.tvLog)
        scrollLog    = findViewById(R.id.scrollLog)
        inputUserId  = findViewById(R.id.inputUserId)

        // Gespeicherte UserId laden
        val prefs = getSharedPreferences("navee", Context.MODE_PRIVATE)
        inputUserId.setText(prefs.getString("userId", ""))

        setFeatureButtonsEnabled(false)

        btnConnect.setOnClickListener {
            if (connected) disconnect()
            else connect()
        }

        btnZeroStart.setOnClickListener {
            if (!authed) return@setOnClickListener
            lifecycleScope.launch {
                toggleZeroStart()
            }
        }

        btnCruise.setOnClickListener {
            if (!authed) return@setOnClickListener
            lifecycleScope.launch {
                toggleCruise()
            }
        }

        btnLock.setOnClickListener {
            if (!authed) return@setOnClickListener
            lifecycleScope.launch {
                toggleLock()
            }
        }

        requestBlePermissions()
        log("App bereit. UserId eingeben und verbinden.")
    }

    // ─────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────
    private fun requestBlePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    // ─────────────────────────────────────────────────────────────
    // BLE Scan & Connect
    // ─────────────────────────────────────────────────────────────
    private fun connect() {
        val userId = inputUserId.text.toString().trim()
        if (userId.isEmpty() || userId.toLongOrNull() == null) {
            log("Fehler: Navee ID eingeben!")
            return
        }
        getSharedPreferences("navee", Context.MODE_PRIVATE).edit()
            .putString("userId", userId).apply()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Fehler: Bluetooth ist aus!")
            return
        }

        setStatus("Scanne...")
        log("Scanne nach NAVEE Rollern...")
        btnConnect.text = "Abbrechen"

        val filter = ScanFilter.Builder().setDeviceNamePrefix("NAVEE").build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val scanner = adapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanner.stopScan(this)
                log("Gefunden: ${result.device.name ?: result.device.address}")
                runOnUiThread {
                    result.device.connectGatt(
                        this@MainActivity, false, gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                    setStatus("Verbinde...")
                }
            }
            override fun onScanFailed(errorCode: Int) {
                log("Scan fehlgeschlagen: $errorCode")
                setStatus("Scan Fehler")
                btnConnect.text = "Verbinden"
            }
        }

        scanner.startScan(listOf(filter), settings, scanCallback)

        // Timeout nach 10 Sekunden
        Handler(Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(scanCallback) } catch(e: Exception) {}
            if (!connected) {
                log("Kein Roller gefunden. Roller einschalten und nochmal versuchen.")
                setStatus("Nicht verbunden")
                btnConnect.text = "Verbinden"
            }
        }, 10000)
    }

    private fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connected = false
        authed = false
        writeChr = null
        notifyChr = null
        rxBuffer.clear()
        setStatus("Nicht verbunden")
        btnConnect.text = "Verbinden"
        setFeatureButtonsEnabled(false)
        log("Getrennt")
    }

    // ─────────────────────────────────────────────────────────────
    // GATT Callback
    // ─────────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT verbunden ✓")
                bluetoothGatt = gatt
                Thread.sleep(600) // Android braucht kurze Pause
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Verbindung getrennt (status=$status)")
                runOnUiThread { disconnect() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service-Discovery fehlgeschlagen: $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                log("Navee Service nicht gefunden!")
                // Liste alle Services
                gatt.services.forEach { log("  Service: ${it.uuid}") }
                return
            }

            writeChr = service.getCharacteristic(WRITE_CHR_UUID)
            notifyChr = service.getCharacteristic(NOTIFY_CHR_UUID)

            if (writeChr == null || notifyChr == null) {
                log("Charakteristiken nicht gefunden!")
                service.characteristics.forEach { log("  CHR: ${it.uuid}") }
                return
            }

            log("Write CHR: ${writeChr!!.uuid}")
            log("Notify CHR: ${notifyChr!!.uuid}")

            // Notifications aktivieren
            gatt.setCharacteristicNotification(notifyChr, true)
            val desc = notifyChr!!.getDescriptor(CCCD_UUID)
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(desc)
                }
                log("Notifications aktiviert ✓")
            } else {
                log("CCCD Descriptor nicht gefunden — fahre fort")
                onNotificationsReady()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("Descriptor geschrieben (status=$status)")
            onNotificationsReady()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = chr.value ?: return
            onDataReceived(data)
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            chr: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onDataReceived(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic, status: Int) {
            // Write bestätigt
        }
    }

    private fun onNotificationsReady() {
        connected = true
        runOnUiThread {
            setStatus("Verbunden — authentifiziere...")
            btnConnect.text = "Trennen"
        }
        lifecycleScope.launch {
            try {
                val userId = getSharedPreferences("navee", Context.MODE_PRIVATE)
                    .getString("userId", "") ?: ""
                authenticate(userId.toLong())
                authed = true
                log("Authentifiziert ✓")
                runOnUiThread {
                    setStatus("Verbunden ✓")
                    setFeatureButtonsEnabled(true)
                }
                // Parameter lesen
                readParams()
            } catch (e: Exception) {
                log("Auth Fehler: ${e.message}")
                runOnUiThread { setStatus("Auth fehlgeschlagen") }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Frame Protokoll: 55 AA 00 <cmd> <len> [data] <cs>
    // ─────────────────────────────────────────────────────────────
    private fun buildFrame(cmd: Byte, data: ByteArray = byteArrayOf()): ByteArray {
        val body = byteArrayOf(0x00, cmd, data.size.toByte()) + data
        val cs = body.fold(0) { acc, b -> (acc + (b.toInt() and 0xFF)) and 0xFF }.toByte()
        return byteArrayOf(0x55.toByte(), 0xAA.toByte()) + body + cs
    }

    private fun onDataReceived(data: ByteArray) {
        synchronized(rxBuffer) {
            rxBuffer.addAll(data.toList())
            flushBuffer()
        }
    }

    private fun flushBuffer() {
        while (rxBuffer.size >= 6) {
            if (rxBuffer[0] != 0x55.toByte() || rxBuffer[1] != 0xAA.toByte()) {
                rxBuffer.removeAt(0); continue
            }
            val len = rxBuffer[4].toInt() and 0xFF
            val needed = 5 + len + 1
            if (rxBuffer.size < needed) break
            val frame = rxBuffer.subList(0, needed).toByteArray()
            repeat(needed) { rxBuffer.removeAt(0) }
            val parsed = ByteArray(len).also { System.arraycopy(frame, 5, it, 0, len) }
            pendingResponse?.complete(parsed)
            pendingResponse = null
        }
    }

    private suspend fun sendAndReceive(cmd: Byte, data: ByteArray = byteArrayOf(), timeoutMs: Long = 4000): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        pendingResponse = deferred
        sendFrame(buildFrame(cmd, data))
        return withTimeout(timeoutMs) { deferred.await() }
    }

    private fun sendFrame(frame: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val chr = writeChr ?: return
        // Sende in Chunks von 20 Bytes
        var offset = 0
        while (offset < frame.size) {
            val chunk = frame.copyOfRange(offset, minOf(offset + 20, frame.size))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(chr, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                chr.value = chunk
                @Suppress("DEPRECATION")
                chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(chr)
            }
            offset += 20
            if (frame.size > 20) Thread.sleep(20)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AES-128 ECB Verschlüsselung
    // ─────────────────────────────────────────────────────────────
    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    // ─────────────────────────────────────────────────────────────
    // Authentifizierung
    // ─────────────────────────────────────────────────────────────
    private suspend fun authenticate(userId: Long) {
        val keyIdx = (0..4).random()
        val uid = byteArrayOf(
            ((userId shr 24) and 0xFF).toByte(),
            ((userId shr 16) and 0xFF).toByte(),
            ((userId shr 8)  and 0xFF).toByte(),
            (userId and 0xFF).toByte()
        )
        log("Auth-Init mit Key $keyIdx...")
        val initData = byteArrayOf(keyIdx.toByte()) + uid + byteArrayOf(0x00)
        val challenge = sendAndReceive(CMD_AUTH_INIT, initData)
        if (challenge.size < 16) throw Exception("Ungültige Challenge (${challenge.size} bytes)")

        val response = aesEncrypt(challenge.copyOf(16), AES_KEYS[keyIdx])
        val authReply = sendAndReceive(CMD_AUTH_RESP, response)
        val code = if (authReply.isNotEmpty()) authReply[0].toInt() and 0xFF else -1
        if (code != 0) {
            if (code == 255) throw Exception("Falsche Navee ID (Error 255)")
            throw Exception("Auth fehlgeschlagen (code $code)")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Parameter lesen
    // ─────────────────────────────────────────────────────────────
    private suspend fun readParams() {
        try {
            val data = sendAndReceive(CMD_READ_PARAMS, timeoutMs = 3000)
            if (data.size >= 27) {
                zeroStartEnabled = data[19].toInt() and 0xFF == 0
                cruiseEnabled    = (data[3].toInt() and 0xFF) != 0
                lockEnabled      = (data[2].toInt() and 0xFF) != 0
                val maxSpeed = data[25].toInt() and 0xFF
                log("Status: ZeroStart=${if (zeroStartEnabled) "AN" else "AUS"} Cruise=${if (cruiseEnabled) "AN" else "AUS"} MaxSpeed=${maxSpeed}km/h")
                runOnUiThread { updateButtonStates() }
            }
        } catch (e: Exception) {
            log("Parameter-Lesefehler: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Feature Toggles
    // ─────────────────────────────────────────────────────────────
    private suspend fun toggleZeroStart() {
        val newVal: Byte = if (zeroStartEnabled) 3 else 0
        try {
            sendFrame(buildFrame(CMD_START_SPEED, byteArrayOf(newVal)))
            zeroStartEnabled = !zeroStartEnabled
            val msg = if (zeroStartEnabled) "Zero Start AN ✓" else "Zero Start AUS"
            log(msg)
            runOnUiThread { updateButtonStates() }
        } catch (e: Exception) {
            log("Fehler: ${e.message}")
        }
    }

    private suspend fun toggleCruise() {
        val newVal: Byte = if (cruiseEnabled) 0 else 1
        try {
            sendFrame(buildFrame(0x52.toByte(), byteArrayOf(newVal)))
            cruiseEnabled = !cruiseEnabled
            log("Tempomat " + if (cruiseEnabled) "AN" else "AUS")
            runOnUiThread { updateButtonStates() }
        } catch (e: Exception) {
            log("Fehler: ${e.message}")
        }
    }

    private suspend fun toggleLock() {
        val newVal: Byte = if (lockEnabled) 0 else 1
        try {
            sendFrame(buildFrame(0x51.toByte(), byteArrayOf(newVal)))
            lockEnabled = !lockEnabled
            log("Wegfahrsperre " + if (lockEnabled) "GESPERRT" else "ENTSPERRT")
            runOnUiThread { updateButtonStates() }
        } catch (e: Exception) {
            log("Fehler: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UI Helfer
    // ─────────────────────────────────────────────────────────────
    private fun setStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
    }

    private fun setFeatureButtonsEnabled(enabled: Boolean) {
        runOnUiThread {
            btnZeroStart.isEnabled = enabled
            btnCruise.isEnabled = enabled
            btnLock.isEnabled = enabled
        }
    }

    private fun updateButtonStates() {
        btnZeroStart.text = if (zeroStartEnabled) "🚀 Zero Start: AN" else "🚀 Zero Start: AUS"
        btnZeroStart.setBackgroundColor(
            if (zeroStartEnabled) 0xFF22C55E.toInt() else 0xFF3B82F6.toInt()
        )
        btnCruise.text = if (cruiseEnabled) "🎯 Tempomat: AN" else "🎯 Tempomat: AUS"
        btnLock.text = if (lockEnabled) "🔒 Gesperrt" else "🔓 Entsperrt"
    }

    private fun log(msg: String) {
        runOnUiThread {
            val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLog.append("$time  $msg\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
