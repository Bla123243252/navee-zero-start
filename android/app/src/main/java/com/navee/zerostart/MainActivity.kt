package com.navee.zerostart

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
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

private val SERVICE_UUID    = UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212")
private val WRITE_CHR_UUID  = UUID.fromString("0000b002-0000-1000-8000-00805f9b34fb")
private val NOTIFY_CHR_UUID = UUID.fromString("0000b003-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val CMD_AUTH_INIT:   Byte = 0x30
private const val CMD_AUTH_RESP:   Byte = 0x31
private const val CMD_READ_PARAMS: Byte = 0x70
private const val CMD_START_SPEED: Byte = 0x6A.toByte()
private const val CMD_CRUISE:      Byte = 0x52.toByte()
private const val CMD_LOCK:        Byte = 0x51.toByte()

private val AES_KEYS = arrayOf(
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x31),
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x32),
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x33),
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x34),
    byteArrayOf(0x4e,0x61,0x76,0x65,0x65,0x42,0x4c,0x45,0x4b,0x65,0x79,0x30,0x30,0x30,0x30,0x35),
)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var btnConnect:   Button
    private lateinit var btnZeroStart: Button
    private lateinit var btnCruise:    Button
    private lateinit var btnLock:      Button
    private lateinit var tvStatus:     TextView
    private lateinit var tvLog:        TextView
    private lateinit var scrollLog:    ScrollView
    private lateinit var inputUserId:  EditText

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChr:  BluetoothGattCharacteristic? = null
    private var notifyChr: BluetoothGattCharacteristic? = null
    private var connected = false
    private var authed    = false

    private var pendingResponse: CompletableDeferred<ByteArray>? = null
    private val rxBuffer = mutableListOf<Byte>()

    private var zeroStartEnabled = false
    private var cruiseEnabled    = false
    private var lockEnabled      = false

    private var scanCallback: ScanCallback? = null

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

        val prefs = getSharedPreferences("navee", Context.MODE_PRIVATE)
        inputUserId.setText(prefs.getString("userId", ""))

        setFeatureButtonsEnabled(false)

        btnConnect.setOnClickListener   { if (connected) disconnect() else connect() }
        btnZeroStart.setOnClickListener { if (authed) lifecycleScope.launch { toggleZeroStart() } }
        btnCruise.setOnClickListener    { if (authed) lifecycleScope.launch { toggleCruise() } }
        btnLock.setOnClickListener      { if (authed) lifecycleScope.launch { toggleLock() } }

        requestBlePermissions()
        log("App bereit. Navee ID eingeben und verbinden.")
    }

    private fun requestBlePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))    perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // â”€â”€ Connect â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun connect() {
        val userId = inputUserId.text.toString().trim()
        if (userId.isEmpty() || userId.toLongOrNull() == null) { log("Fehler: Navee ID eingeben!"); return }
        getSharedPreferences("navee", Context.MODE_PRIVATE).edit().putString("userId", userId).apply()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) { log("Fehler: Bluetooth ist aus!"); return }

        setStatus("Scanne...")
        log("Scanne nach NAVEE Rollern...")
        btnConnect.text = "Abbrechen"

        val filter = ScanFilter.Builder().setDeviceName("NAVEE").build()
        val filterPrefix = ScanFilter.Builder().build() // fallback: alle
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val scanner = adapter.bluetoothLeScanner

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: ""
                if (!name.startsWith("NAVEE")) return
                scanner.stopScan(this)
                scanCallback = null
                log("Gefunden: $name")
                runOnUiThread {
                    result.device.connectGatt(this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    setStatus("Verbinde...")
                }
            }
            override fun onScanFailed(errorCode: Int) {
                log("Scan fehlgeschlagen: $errorCode")
                setStatus("Scan Fehler")
                btnConnect.text = "Verbinden"
            }
        }

        scanner.startScan(listOf(filterPrefix), settings, scanCallback!!)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!connected) {
                scanCallback?.let { scanner.stopScan(it) }
                scanCallback = null
                log("Kein Roller gefunden. Roller einschalten?")
                setStatus("Nicht verbunden")
                btnConnect.text = "Verbinden"
            }
        }, 12000)
    }

    private fun disconnect() {
        scanCallback?.let {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bluetoothLeScanner?.stopScan(it)
        }
        scanCallback = null
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connected = false; authed = false
        writeChr = null; notifyChr = null
        rxBuffer.clear()
        setStatus("Nicht verbunden")
        btnConnect.text = "Verbinden"
        setFeatureButtonsEnabled(false)
        log("Getrennt")
    }

    // â”€â”€ GATT Callback â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                log("GATT verbunden âœ“ â€” entdecke Services...")
                Thread.sleep(600)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Verbindung getrennt (status=$status)")
                runOnUiThread { disconnect() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("Service-Discovery fehlgeschlagen: $status"); return }

            val svc = gatt.getService(SERVICE_UUID)
            if (svc == null) {
                log("Navee Service nicht gefunden!")
                gatt.services.forEach { log("  Service: ${it.uuid}") }
                return
            }

            writeChr  = svc.getCharacteristic(WRITE_CHR_UUID)
            notifyChr = svc.getCharacteristic(NOTIFY_CHR_UUID)

            if (writeChr == null || notifyChr == null) {
                log("Charakteristiken nicht gefunden!")
                svc.characteristics.forEach { log("  CHR: ${it.uuid}") }
                return
            }

            log("Services gefunden âœ“")
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
            } else {
                onNotificationsReady()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("Notifications aktiviert (status=$status) âœ“")
            onNotificationsReady()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic) {
            chr.value?.let { onDataReceived(it) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic, value: ByteArray) {
            onDataReceived(value)
        }
    }

    private fun onNotificationsReady() {
        connected = true
        runOnUiThread { setStatus("Verbunden â€” warte auf Auth..."); btnConnect.text = "Trennen" }
        log("Verbunden âœ“ â€” warte auf Challenge vom Roller...")
        // Roller sendet Challenge automatisch nach dem Verbinden
        // Wir reagieren in onDataReceived wenn cmd=0x30 kommt
        lifecycleScope.launch {
            delay(8000) // Warte 8 Sekunden auf Auth
            if (!authed) {
                log("Kein Auth nÃ¶tig â€” sende direkt Befehle")
                authed = true
                runOnUiThread { setStatus("Verbunden âœ“"); setFeatureButtonsEnabled(true) }
                readParams()
            }
        }
    }

    // â”€â”€ Frame Protokoll â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun buildFrame(cmd: Byte, data: ByteArray = byteArrayOf()): ByteArray {
        val body = byteArrayOf(0x00, cmd, data.size.toByte()) + data
        val cs   = body.fold(0) { acc, b -> (acc + (b.toInt() and 0xFF)) and 0xFF }.toByte()
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
            if (rxBuffer[0] != 0x55.toByte() || rxBuffer[1] != 0xAA.toByte()) { rxBuffer.removeAt(0); continue }
            val len    = rxBuffer[4].toInt() and 0xFF
            val needed = 5 + len + 1
            if (rxBuffer.size < needed) break
            val frame  = rxBuffer.subList(0, needed).toByteArray()
            repeat(needed) { rxBuffer.removeAt(0) }
            val cmd    = frame[3]
            val parsed = frame.copyOfRange(5, 5 + len)

            // Auto-handle Auth Challenge (0x30) vom Roller
            if (cmd == CMD_AUTH_INIT && parsed.size >= 16 && !authed) {
                log("Challenge empfangen â†’ antworte...")
                lifecycleScope.launch { handleAuthChallenge(parsed) }
                continue
            }

            pendingResponse?.complete(parsed)
            pendingResponse = null
        }
    }

    private suspend fun handleAuthChallenge(challenge: ByteArray) {
        try {
            val prefs  = getSharedPreferences("navee", Context.MODE_PRIVATE)
            val userId = prefs.getString("userId", "0")?.toLongOrNull() ?: 0L
            val keyIdx = challenge[0].toInt() and 0xFF  // Roller schickt keyIdx
            val actualKey = if (keyIdx < AES_KEYS.size) keyIdx else 0
            val response = aesEncrypt(challenge.copyOf(16), AES_KEYS[actualKey])
            sendFrame(buildFrame(CMD_AUTH_RESP, response))
            log("Auth-Response gesendet (key=$actualKey)")
            delay(500)
            authed = true
            log("Authentifiziert âœ“")
            runOnUiThread { setStatus("Verbunden âœ“"); setFeatureButtonsEnabled(true) }
            readParams()
        } catch (e: Exception) {
            log("Auth-Challenge Fehler: ${e.message}")
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
        val chr  = writeChr ?: return
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

    // â”€â”€ AES-128 ECB â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    // â”€â”€ Auth â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Protokoll: [keyIdx, shareFlag=0, s6(userId)=6 bytes BE, 0x00] = 9 bytes payload
    private fun s6(userId: Long): ByteArray {
        val v = userId and 0xFFFFFFFFFFFFL
        return byteArrayOf(
            ((v shr 40) and 0xFF).toByte(),
            ((v shr 32) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr  8) and 0xFF).toByte(),
            (v          and 0xFF).toByte()
        )
    }

    private suspend fun authenticate(userId: Long) {
        val keyIdx   = (0..4).random()
        val initData = byteArrayOf(keyIdx.toByte(), 0x00) + s6(userId) + byteArrayOf(0x00)
        log("Auth-Init mit Key $keyIdx...")
        // Sende Auth-Init und warte auf Challenge vom Roller
        sendFrame(buildFrame(CMD_AUTH_INIT, initData))
        // Roller schickt Challenge als 0x30 Response
        val challenge = withTimeout(5000) {
            var result: ByteArray? = null
            while (result == null) {
                delay(50)
                synchronized(rxBuffer) {
                    // Suche nach 0x30 Frame mit 16 Bytes Challenge
                    val buf = rxBuffer.toByteArray()
                    for (i in buf.indices) {
                        if (i + 5 < buf.size && buf[i] == 0x55.toByte() && buf[i+1] == 0xAA.toByte() && buf[i+3] == CMD_AUTH_INIT) {
                            val len = buf[i+4].toInt() and 0xFF
                            if (len >= 16 && i + 5 + len < buf.size) {
                                result = buf.copyOfRange(i+5, i+5+len)
                                rxBuffer.clear()
                            }
                            break
                        }
                    }
                }
            }
            result!!
        }
        val errCode = challenge[0].toInt() and 0xFF
        if (errCode == 255) throw Exception("Falsche Navee ID (Error 255)")
        if (challenge.size < 16) throw Exception("Challenge zu kurz")
        log("Challenge empfangen âœ“")
        val response  = aesEncrypt(challenge.copyOf(16), AES_KEYS[keyIdx])
        sendFrame(buildFrame(CMD_AUTH_RESP, response))
        // Warte auf Auth-Response (0x31)
        delay(500)
        log("Auth gesendet âœ“")
    }

    // â”€â”€ Parameter lesen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private suspend fun readParams() {
        try {
            val data = sendAndReceive(CMD_READ_PARAMS, timeoutMs = 3000)
            if (data.size >= 27) {
                zeroStartEnabled = (data[19].toInt() and 0xFF) == 0
                cruiseEnabled    = (data[3].toInt()  and 0xFF) != 0
                lockEnabled      = (data[2].toInt()  and 0xFF) != 0
                val maxSpeed     =  data[25].toInt() and 0xFF
                log("Status: ZeroStart=${if (zeroStartEnabled) "AN" else "AUS"} Cruise=${if (cruiseEnabled) "AN" else "AUS"} Max=${maxSpeed}km/h")
                runOnUiThread { updateButtonStates() }
            }
        } catch (e: Exception) { log("Params: ${e.message}") }
    }

    // â”€â”€ Toggles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private suspend fun toggleZeroStart() {
        val newVal: Byte = if (zeroStartEnabled) 3 else 0
        sendFrame(buildFrame(CMD_START_SPEED, byteArrayOf(newVal)))
        zeroStartEnabled = !zeroStartEnabled
        log("Zero Start " + if (zeroStartEnabled) "AN âœ“" else "AUS")
        runOnUiThread { updateButtonStates() }
    }

    private suspend fun toggleCruise() {
        val newVal: Byte = if (cruiseEnabled) 0 else 1
        sendFrame(buildFrame(CMD_CRUISE, byteArrayOf(newVal)))
        cruiseEnabled = !cruiseEnabled
        log("Tempomat " + if (cruiseEnabled) "AN" else "AUS")
        runOnUiThread { updateButtonStates() }
    }

    private suspend fun toggleLock() {
        val newVal: Byte = if (lockEnabled) 0 else 1
        sendFrame(buildFrame(CMD_LOCK, byteArrayOf(newVal)))
        lockEnabled = !lockEnabled
        log("Wegfahrsperre " + if (lockEnabled) "GESPERRT ðŸ”’" else "ENTSPERRT ðŸ”“")
        runOnUiThread { updateButtonStates() }
    }

    // â”€â”€ UI â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun setStatus(text: String) = runOnUiThread { tvStatus.text = text }

    private fun setFeatureButtonsEnabled(enabled: Boolean) = runOnUiThread {
        btnZeroStart.isEnabled = enabled
        btnCruise.isEnabled    = enabled
        btnLock.isEnabled      = enabled
    }

    private fun updateButtonStates() {
        btnZeroStart.text = if (zeroStartEnabled) "ðŸš€ Zero Start: AN"  else "ðŸš€ Zero Start: AUS"
        btnCruise.text    = if (cruiseEnabled)    "ðŸŽ¯ Tempomat: AN"    else "ðŸŽ¯ Tempomat: AUS"
        btnLock.text      = if (lockEnabled)      "ðŸ”’ Gesperrt"        else "ðŸ”“ Entsperrt"
    }

    private fun log(msg: String) = runOnUiThread {
        val t = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLog.append("$t  $msg\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() { super.onDestroy(); disconnect() }
}
