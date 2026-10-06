package com.earthwheel.boost

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Conexiune BLE cu placa ESP32-C3: scanare, conectare automată,
 * autentificare securizată, comenzi semnate, status live, reconectare.
 * Toată logica rulează pe thread-ul principal (callback-urile sunt redirecționate).
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val store: SecureStore,
    private val listener: Listener
) {
    enum class Conn { OFF, BT_DISABLED, SCANNING, CONNECTING, AUTHENTICATING, NEED_PIN, LOCKED, READY }

    data class Status(
        val authed: Boolean,
        val relay: Boolean,
        val pinSet: Boolean,
        val rfEnabled: Boolean,
        val rfLearned: Boolean,
        val rfLearning: Boolean,
        val result: Int,
        val extra: Int
    )

    interface Listener {
        fun onConnState(state: Conn, extra: Int)
        fun onStatus(status: Status)
    }

    companion object {
        private const val TAG = "EWBoost"
        val SVC: UUID = UUID.fromString("00086dbb-4217-402e-9eec-18ebe775b223")
        val STATUS: UUID = UUID.fromString("ea66f4fc-2f2a-446d-b42c-aa629a27d0c9")
        val CHALLENGE: UUID = UUID.fromString("fb72bf2d-c29a-449f-86ba-081977ee7cef")
        val AUTH: UUID = UUID.fromString("fe5dcef1-fdce-41ed-8564-8f6b67a79d1d")
        val CMD: UUID = UUID.fromString("33e96412-2f79-4b7e-bc65-ce22418c2335")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val OP_RELAY_SET = 0x01
        const val OP_RELAY_TOGGLE = 0x02
        const val OP_PIN_SET = 0x10
        const val OP_PIN_REMOVE = 0x11
        const val OP_RF_LEARN = 0x20
        const val OP_RF_CLEAR = 0x21
        const val OP_RF_CANCEL = 0x22
        const val OP_STATUS = 0x30

        const val RES_NONE = 0
        const val RES_AUTH_OK = 1
        const val RES_AUTH_FAIL = 2
        const val RES_LOCKED = 3
        const val RES_BAD_MAC = 4
        const val RES_NOT_AUTH = 5
        const val RES_PIN_OK = 6
        const val RES_PIN_REMOVED = 7
        const val RES_RF_LEARNED = 8
        const val RES_RF_CLEARED = 9
        const val RES_RF_DISABLED = 10
        const val RES_BAD_CMD = 11
        const val RES_RELAY = 12
        const val RES_RF_TIMEOUT = 13
        const val RES_RF_LEARNING = 14
        const val RES_HEARTBEAT = 15

        private const val SCAN_RESTART_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val LINK_TIMEOUT_MS = 10_000L
        private const val OP_TIMEOUT_MS = 4_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var chStatus: BluetoothGattCharacteristic? = null
    private var chChallenge: BluetoothGattCharacteristic? = null
    private var chAuth: BluetoothGattCharacteristic? = null
    private var chCmd: BluetoothGattCharacteristic? = null

    private var discovered = false
    private var awaitingFirstStatus = false
    private var pinSetOnDevice = true
    private var manualPin: String? = null      // cod introdus de utilizator, încă neconfirmat
    private var attemptPin: String? = null     // codul folosit la încercarea curentă
    private var candidateKey: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var seq = 0
    private var pendingNewPin: String? = null
    private var lastStatusAt = 0L
    private var lockedUntil = 0L               // blocare după prea multe coduri greșite

    var state: Conn = Conn.OFF
        private set
    var lastStatus: Status? = null
        private set

    // ---------------- coadă operații GATT (Android permite doar una odată) ----------------
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private val opTimeout = Runnable { opBusy = false; nextOp() }

    private fun enqueue(op: () -> Boolean) { ops.addLast(op); nextOp() }

    private fun nextOp() {
        if (opBusy) return
        val op = ops.removeFirstOrNull() ?: return
        opBusy = true
        val started = try { op() } catch (e: Exception) { Log.w(TAG, "op error", e); false }
        if (started) main.postDelayed(opTimeout, OP_TIMEOUT_MS)
        else { opBusy = false; nextOp() }
    }

    private fun opDone() {
        main.removeCallbacks(opTimeout)
        opBusy = false
        nextOp()
    }

    // ---------------- API public ----------------
    fun start() {
        running = true
        if (gatt == null && !scanning) startScan()
    }

    fun stop() {
        running = false
        stopScan()
        main.removeCallbacksAndMessages(null)
        closeGatt()
        setState(Conn.OFF)
    }

    fun isReady() = state == Conn.READY

    /** utilizatorul a introdus codul */
    fun authenticate(pin: String) {
        manualPin = pin
        if (gatt != null && discovered && (state == Conn.NEED_PIN || state == Conn.AUTHENTICATING)) {
            beginAuth(pin)
        } else if (gatt == null) {
            start()
        }
    }

    /** uită placa asociată și caută din nou */
    fun forgetDevice() {
        store.deviceAddress = null
        store.pin = null
        manualPin = null
        closeGatt()
        if (running) { stopScan(); startScan() }
    }

    fun setRelay(on: Boolean) = send(OP_RELAY_SET, byteArrayOf(if (on) 1 else 0))
    fun refresh() = send(OP_STATUS)
    fun rfLearn() = send(OP_RF_LEARN)
    fun rfClear() = send(OP_RF_CLEAR)
    fun rfCancel() = send(OP_RF_CANCEL)
    fun removePin() = send(OP_PIN_REMOVE)

    fun changePin(newPin: String): Boolean {
        val k = sessionKey ?: return false
        if (state != Conn.READY) return false
        pendingNewPin = newPin
        val s = ++seq
        val pkt = Crypto.buildCmd(k, OP_PIN_SET, s, Crypto.encryptPin(k, s, newPin))
        enqueue { writeChar(chCmd, pkt) }
        return true
    }

    private fun send(op: Int, payload: ByteArray = ByteArray(0)): Boolean {
        val k = sessionKey ?: return false
        if (state != Conn.READY) return false
        val pkt = Crypto.buildCmd(k, op, ++seq, payload)
        enqueue { writeChar(chCmd, pkt) }
        return true
    }

    // ---------------- scanare ----------------
    private val scanRestart = Runnable {
        if (scanning) { stopScan(); startScan() }
    }

    private fun startScan() {
        if (!running || scanning || gatt != null) return
        val a = adapter
        if (a == null || !a.isEnabled) { setState(Conn.BT_DISABLED); return }
        val scanner = a.bluetoothLeScanner ?: run { setState(Conn.BT_DISABLED); return }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCb)
            scanning = true
            setState(Conn.SCANNING)
            main.removeCallbacks(scanRestart)
            main.postDelayed(scanRestart, SCAN_RESTART_MS)
        } catch (e: Exception) {
            Log.w(TAG, "scan error", e)
            main.postDelayed({ startScan() }, 3000)
        }
    }

    private fun stopScan() {
        main.removeCallbacks(scanRestart)
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) { }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { onFound(result.device) }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            main.post { scanning = false; main.postDelayed({ startScan() }, 3000) }
        }
    }

    private fun onFound(device: BluetoothDevice) {
        if (!scanning || gatt != null) return
        val saved = store.deviceAddress
        if (saved != null && !saved.equals(device.address, ignoreCase = true)) return  // nu e placa noastră
        stopScan()
        connect(device)
    }

    // ---------------- conectare ----------------
    private val connectTimeout = Runnable {
        if (state == Conn.CONNECTING || (state == Conn.AUTHENTICATING && !discovered)) {
            Log.w(TAG, "connect timeout")
            reconnectLater()
        }
    }

    private fun connect(device: BluetoothDevice) {
        setState(Conn.CONNECTING)
        discovered = false
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(linkWatchdog)
        main.removeCallbacks(opTimeout)
        ops.clear(); opBusy = false
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        chStatus = null; chChallenge = null; chAuth = null; chCmd = null
        discovered = false
        sessionKey = null; candidateKey = null
        seq = 0
    }

    private fun reconnectLater() {
        closeGatt()
        if (!running) return
        val lockLeft = lockedUntil - System.currentTimeMillis()
        if (lockLeft > 0) {
            // placa e blocată: nu reîncercăm până nu expiră blocarea
            setState(Conn.LOCKED, ((lockLeft + 999) / 1000).toInt())
            main.postDelayed({ if (running && gatt == null) startScan() }, lockLeft + 500)
            return
        }
        setState(Conn.SCANNING)
        main.postDelayed({ if (running && gatt == null) startScan() }, 1500)
    }

    private val linkWatchdog = object : Runnable {
        override fun run() {
            if (state == Conn.READY && System.currentTimeMillis() - lastStatusAt > LINK_TIMEOUT_MS) {
                Log.w(TAG, "link lost (no heartbeat)")
                reconnectLater()
                return
            }
            main.postDelayed(this, 2000)
        }
    }

    // ---------------- callback-uri GATT ----------------
    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) { try { g.close() } catch (_: Exception) { }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    setState(Conn.AUTHENTICATING)
                    // MTU mare pentru pachetele de autentificare (32 B) și comenzi
                    if (!g.requestMtu(185)) g.discoverServices()
                    else main.postDelayed({ if (gatt == g && !discovered) g.discoverServices() }, 1500)
                } else {
                    reconnectLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (gatt == g && !discovered) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (gatt != g || discovered) return@post
                val svc = g.getService(SVC)
                if (status != BluetoothGatt.GATT_SUCCESS || svc == null) { reconnectLater(); return@post }
                discovered = true
                main.removeCallbacks(connectTimeout)
                chStatus = svc.getCharacteristic(STATUS)
                chChallenge = svc.getCharacteristic(CHALLENGE)
                chAuth = svc.getCharacteristic(AUTH)
                chCmd = svc.getCharacteristic(CMD)
                if (chStatus == null || chChallenge == null || chAuth == null || chCmd == null) {
                    reconnectLater(); return@post
                }
                awaitingFirstStatus = true
                enqueue { enableNotify(chStatus!!) }
                enqueue { readChar(chStatus) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (gatt == g) opDone() }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: ByteArray(0)
            val u = c.uuid
            main.post { if (gatt == g) onRead(u, v, status) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val v = value.copyOf()
            val u = c.uuid
            main.post { if (gatt == g) onRead(u, v, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (gatt != g) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "write ${c.uuid} failed $status")
                opDone()
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return
            if (c.uuid == STATUS) main.post { if (gatt == g) onStatusBytes(v, true) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            if (c.uuid == STATUS) main.post { if (gatt == g) onStatusBytes(v, true) }
        }
    }

    // ---------------- logică protocol ----------------
    private fun onRead(uuid: UUID, value: ByteArray, status: Int) {
        opDone()
        if (status != BluetoothGatt.GATT_SUCCESS) { reconnectLater(); return }
        when (uuid) {
            STATUS -> {
                onStatusBytes(value, false)
                if (awaitingFirstStatus) {
                    awaitingFirstStatus = false
                    val pin = when {
                        !pinSetOnDevice -> ""
                        manualPin != null -> manualPin
                        else -> store.pin?.takeIf { it.isNotEmpty() }
                    }
                    if (pin == null) setState(Conn.NEED_PIN, -1) else beginAuth(pin)
                }
            }
            CHALLENGE -> {
                val pin = attemptPin ?: return
                if (value.size != 16) { reconnectLater(); return }
                val k = Crypto.sessionKey(value, pin)
                candidateKey = k
                val proof = Crypto.authProof(k)
                enqueue { writeChar(chAuth, proof) }
            }
        }
    }

    private fun beginAuth(pin: String) {
        attemptPin = pin
        setState(Conn.AUTHENTICATING)
        enqueue { readChar(chChallenge) }   // provocare nouă de la placă
    }

    private fun onStatusBytes(b: ByteArray, isNotify: Boolean) {
        if (b.size < 3) return
        val f = b[0].toInt() and 0xFF
        val st = Status(
            authed = f and 0x01 != 0,
            relay = f and 0x02 != 0,
            pinSet = f and 0x04 != 0,
            rfEnabled = f and 0x08 != 0,
            rfLearned = f and 0x10 != 0,
            rfLearning = f and 0x20 != 0,
            result = if (isNotify) b[1].toInt() and 0xFF else RES_NONE,
            extra = b[2].toInt() and 0xFF
        )
        pinSetOnDevice = st.pinSet
        lastStatusAt = System.currentTimeMillis()

        if (isNotify) when (st.result) {
            RES_AUTH_OK -> {
                sessionKey = candidateKey
                seq = 0
                val used = attemptPin
                if (used != null) store.pin = if (st.pinSet) used else ""
                manualPin = null
                gatt?.device?.address?.let { store.deviceAddress = it }   // asociere la această placă
                setState(Conn.READY)
                main.removeCallbacks(linkWatchdog)
                main.postDelayed(linkWatchdog, 2000)
            }
            RES_AUTH_FAIL -> {
                // codul salvat nu mai e valabil (schimbat de pe alt telefon / reset) -> nu-l mai reîncercăm
                if (attemptPin != null && attemptPin == store.pin) store.pin = null
                manualPin = null
                candidateKey = null
                setState(Conn.NEED_PIN, st.extra)
            }
            RES_LOCKED -> {
                manualPin = null
                lockedUntil = System.currentTimeMillis() + st.extra * 1000L
                setState(Conn.LOCKED, st.extra)
            }
            RES_PIN_OK -> { pendingNewPin?.let { store.pin = it }; pendingNewPin = null }
            RES_PIN_REMOVED -> store.pin = ""
        }

        if (st.authed || state == Conn.READY) lastStatus = st
        listener.onStatus(st)
    }

    // ---------------- helperi GATT (compatibili API 23..34+) ----------------
    private fun readChar(c: BluetoothGattCharacteristic?): Boolean {
        val g = gatt ?: return false
        return c != null && g.readCharacteristic(c)
    }

    private fun writeChar(c: BluetoothGattCharacteristic?, value: ByteArray): Boolean {
        val g = gatt ?: return false
        if (c == null) return false
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
        }
    }

    private fun enableNotify(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(CCCD) ?: return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { d.value = v; g.writeDescriptor(d) }
        }
    }

    private fun setState(s: Conn, extra: Int = 0) {
        state = s
        listener.onConnState(s, extra)
    }
}
