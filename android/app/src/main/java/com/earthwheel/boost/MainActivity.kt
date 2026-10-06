package com.earthwheel.boost

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Patterns
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Switch
import com.earthwheel.boost.BleManager.Companion as B
import java.util.concurrent.Executors

class MainActivity : Activity(), BleManager.Listener {

    /** referințe la elementele din activity_main.xml */
    private class Views(a: Activity) {
        val tvConn: TextView = a.findViewById(R.id.tvConn)
        val btnMode: FrameLayout = a.findViewById(R.id.btnMode)
        val tvEmoji: TextView = a.findViewById(R.id.tvEmoji)
        val tvModeTitle: TextView = a.findViewById(R.id.tvModeTitle)
        val tvModeSub: TextView = a.findViewById(R.id.tvModeSub)
        val tvPinInfo: TextView = a.findViewById(R.id.tvPinInfo)
        val btnChangePin: Button = a.findViewById(R.id.btnChangePin)
        val btnRemovePin: Button = a.findViewById(R.id.btnRemovePin)
        val btnEmail: Button = a.findViewById(R.id.btnEmail)
        val btnRecover: Button = a.findViewById(R.id.btnRecover)
        val swAppLock: Switch = a.findViewById(R.id.swAppLock)
        val tvRf: TextView = a.findViewById(R.id.tvRf)
        val btnRfLearn: Button = a.findViewById(R.id.btnRfLearn)
        val btnRfClear: Button = a.findViewById(R.id.btnRfClear)
        val btnForget: Button = a.findViewById(R.id.btnForget)
        val tvLang: TextView = a.findViewById(R.id.tvLang)
    }

    private lateinit var ui: Views
    private lateinit var store: SecureStore
    private lateinit var ble: BleManager

    private var unlocked = false
    private var relayOn = false
    private var pinDialog: AlertDialog? = null
    private var pinPromptSnoozed = false   // utilizatorul a ales getString(R.string.btn_later)
    private var keyOk = false              // cheia aplicației e prezentă în APK
    private val io = Executors.newSingleThreadExecutor()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startBle()
        else toast(getString(R.string.perm_denied))
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BT) return
        if (btAdapter()?.isEnabled == true) ble.start()
        else onConnState(BleManager.Conn.BT_DISABLED, 0)
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> if (unlocked) ble.start()
                BluetoothAdapter.STATE_OFF -> { ble.stop(); onConnState(BleManager.Conn.BT_DISABLED, 0) }
            }
        }
    }

    // ======================= ciclu de viață =======================
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("unlocked", unlocked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ui = Views(this)
        store = SecureStore(this)
        ble = BleManager(applicationContext, store, this)
        keyOk = Crypto.init(applicationContext)

        ui.btnMode.setOnClickListener { onModeClick(it) }
        ui.btnChangePin.setOnClickListener { showChangePin() }
        ui.btnRemovePin.setOnClickListener { confirmRemovePin() }
        ui.btnEmail.setOnClickListener { showEmailSettings() }
        ui.btnRecover.setOnClickListener { recoverPin() }
        ui.btnRfLearn.setOnClickListener { rfLearn() }
        ui.btnRfClear.setOnClickListener { if (ble.rfClear()) toast(getString(R.string.clearing_rf)) else notReady() }
        ui.btnForget.setOnClickListener { confirmForget() }
        ui.tvLang.setOnClickListener {
            // comută română <-> greacă și redesenează ecranul
            LocaleHelper.set(this, if (LocaleHelper.current(this) == "el") "ro" else "el")
            recreate()
        }
        ui.tvConn.setOnClickListener {
            when (ble.state) {
                BleManager.Conn.NEED_PIN -> showPinDialog(-1)
                BleManager.Conn.BT_DISABLED, BleManager.Conn.OFF -> if (unlocked) ensurePermissionsAndStart()
                else -> {}
            }
        }

        ui.swAppLock.isChecked = store.appLock
        ui.swAppLock.setOnCheckedChangeListener { sw, checked ->
            if (checked && store.pin.isNullOrEmpty()) {
                sw.isChecked = false
                toast(getString(R.string.applock_need_pin))
            } else store.appLock = checked
        }

        renderMode(false, false)
        renderSecurity(null)
        renderRf(null)

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(btReceiver, filter)

        if (!keyOk) {
            info(getString(R.string.key_missing_title), getString(R.string.key_missing_msg))
            return
        }
        val wasUnlocked = savedInstanceState?.getBoolean("unlocked") == true
        if (store.appLock && !store.pin.isNullOrEmpty() && !wasUnlocked) showAppLock() else unlocked = true
    }

    override fun onStart() {
        super.onStart()
        if (unlocked) ensurePermissionsAndStart()
    }

    override fun onStop() {
        super.onStop()
        ble.stop()          // releul își păstrează starea; reconectare automată la revenire
        pinDialog?.dismiss(); pinDialog = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(btReceiver) } catch (_: Exception) { }
        io.shutdown()
    }

    // ======================= permisiuni / Bluetooth =======================
    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermissionsAndStart() {
        if (!keyOk) return
        val missing = requiredPerms().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startBle() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    private fun startBle() {
        val adapter = btAdapter()
        if (adapter == null) { toast(getString(R.string.no_bt)); return }
        if (!adapter.isEnabled) {
            onConnState(BleManager.Conn.BT_DISABLED, 0)
            try {
                @Suppress("DEPRECATION") startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
            } catch (_: Exception) { }
            return
        }
        ble.start()
    }

    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ======================= buton MELC / RACHETĂ =======================
    private fun onModeClick(v: View) {
        if (!ble.isReady()) { notReady(); return }
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
        // starea afișată se schimbă DOAR când placa confirmă (status real)
        ble.setRelay(!relayOn)
    }

    private fun renderMode(connected: Boolean, on: Boolean) {
        relayOn = on
        if (!connected) {
            ui.btnMode.setBackgroundResource(R.drawable.bg_disabled)
            ui.btnMode.alpha = 0.55f
            ui.tvEmoji.text = "🐌"
            ui.tvModeTitle.text = getString(R.string.mode_snail)
            ui.tvModeSub.text = getString(R.string.mode_wait)
            return
        }
        ui.btnMode.alpha = 1f
        if (on) {
            ui.btnMode.setBackgroundResource(R.drawable.bg_rocket)
            ui.tvEmoji.text = "🚀"
            ui.tvModeTitle.text = getString(R.string.mode_rocket)
            ui.tvModeSub.text = getString(R.string.mode_rocket_sub)
            ui.tvModeTitle.setTextColor(getColor(R.color.ew_rocket_b))
        } else {
            ui.btnMode.setBackgroundResource(R.drawable.bg_snail)
            ui.tvEmoji.text = "🐌"
            ui.tvModeTitle.text = getString(R.string.mode_snail)
            ui.tvModeSub.text = getString(R.string.mode_snail_sub)
            ui.tvModeTitle.setTextColor(getColor(R.color.ew_green))
        }
    }

    // ======================= callback-uri BLE =======================
    private var shownState: BleManager.Conn? = null

    override fun onConnState(state: BleManager.Conn, extra: Int) {
        val prev = shownState
        shownState = state
        val (text, color) = when (state) {
            BleManager.Conn.OFF -> getString(R.string.conn_off) to R.color.ew_gray
            BleManager.Conn.BT_DISABLED -> getString(R.string.conn_bt_off) to R.color.ew_red
            BleManager.Conn.SCANNING -> getString(R.string.conn_scanning) to R.color.ew_gray
            BleManager.Conn.CONNECTING -> getString(R.string.conn_connecting) to R.color.ew_gray
            BleManager.Conn.AUTHENTICATING -> getString(R.string.conn_auth) to R.color.ew_gray
            BleManager.Conn.NEED_PIN -> getString(R.string.conn_need_pin) to R.color.ew_red
            BleManager.Conn.LOCKED -> getString(R.string.conn_locked, extra) to R.color.ew_red
            BleManager.Conn.READY -> getString(R.string.conn_ready) to R.color.ew_green
        }
        ui.tvConn.text = text
        ui.tvConn.setTextColor(getColor(color))

        if (state != BleManager.Conn.READY) {
            renderMode(false, false)
            renderSecurity(null)
            renderRf(null)
        }
        when (state) {
            // popup automat doar la prima cerere sau după un cod greșit; altfel utilizatorul atinge bara de stare
            BleManager.Conn.NEED_PIN -> if (!pinPromptSnoozed || extra >= 0) showPinDialog(extra)
            BleManager.Conn.LOCKED -> {
                pinDialog?.dismiss(); pinDialog = null
                if (prev != BleManager.Conn.LOCKED) toast(getString(R.string.locked_toast, extra))
            }
            BleManager.Conn.READY -> { pinDialog?.dismiss(); pinDialog = null; ble.refresh() }
            else -> {}
        }
    }

    override fun onStatus(status: BleManager.Status) {
        if (ble.isReady() && status.authed) {
            renderMode(true, status.relay)
            renderSecurity(status)
            renderRf(status)
        }
        when (status.result) {
            B.RES_PIN_OK -> toast(getString(R.string.pin_changed))
            B.RES_PIN_REMOVED -> toast(getString(R.string.pin_removed))
            B.RES_RF_LEARNED -> toast(getString(R.string.rf_learned))
            B.RES_RF_CLEARED -> toast(getString(R.string.rf_cleared))
            B.RES_RF_TIMEOUT -> toast(getString(R.string.rf_timeout))
            B.RES_RF_DISABLED -> toast(getString(R.string.rf_disabled))
            B.RES_RF_LEARNING -> toast(getString(R.string.rf_press, status.extra))
            B.RES_BAD_MAC, B.RES_BAD_CMD, B.RES_NOT_AUTH -> toast(getString(R.string.cmd_rejected))
        }
    }

    private fun renderSecurity(s: BleManager.Status?) {
        val ready = s != null
        ui.btnChangePin.isEnabled = ready
        ui.btnRemovePin.isEnabled = ready && s!!.pinSet
        ui.btnChangePin.text = if (s?.pinSet == false) getString(R.string.btn_set_pin) else getString(R.string.btn_change_pin)
        ui.tvPinInfo.text = when {
            s == null -> getString(R.string.pin_info_offline)
            s.pinSet -> getString(R.string.pin_info_on)
            else -> getString(R.string.pin_info_off)
        }
    }

    private fun renderRf(s: BleManager.Status?) {
        val on = s?.rfEnabled == true
        ui.btnRfLearn.isEnabled = on
        ui.btnRfClear.isEnabled = on && s!!.rfLearned
        ui.tvRf.text = when {
            s == null -> "—"
            !s.rfEnabled -> getString(R.string.rf_not_enabled)
            s.rfLearning -> getString(R.string.rf_waiting)
            s.rfLearned -> getString(R.string.rf_paired)
            else -> getString(R.string.rf_none)
        }
    }

    // ======================= coduri =======================
    private fun pinField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(16))
    }

    private fun column(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        views.forEach { addView(it) }
    }

    private fun validPin(p: String) = p.length in 4..16 && p.all { it.isDigit() }

    private fun showPinDialog(triesLeft: Int) {
        if (pinDialog?.isShowing == true || isFinishing) return
        val input = pinField(getString(R.string.hint_pin))
        val msg = if (triesLeft in 1..9) getString(R.string.pin_wrong_tries, triesLeft) else getString(R.string.pin_enter)
        pinDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.pin_title))
            .setMessage(msg)
            .setView(column(input))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_connect)) { _, _ ->
                pinDialog = null
                val p = input.text.toString()
                pinPromptSnoozed = false
                if (validPin(p)) ble.authenticate(p) else { toast(getString(R.string.pin_digits)); showPinDialog(triesLeft) }
            }
            .setNeutralButton(getString(R.string.btn_forgot)) { _, _ -> pinDialog = null; recoverPin() }
            .setNegativeButton(getString(R.string.btn_later)) { _, _ -> pinDialog = null; pinPromptSnoozed = true }
            .show()
    }

    private fun showChangePin() {
        if (!ble.isReady()) { notReady(); return }
        val a = pinField(getString(R.string.hint_new_pin))
        val b = pinField(getString(R.string.hint_repeat_pin))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_pin_title))
            .setView(column(a, b))
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val p1 = a.text.toString(); val p2 = b.text.toString()
                when {
                    !validPin(p1) -> toast(getString(R.string.pin_must_digits))
                    p1 != p2 -> toast(getString(R.string.pin_mismatch))
                    !ble.changePin(p1) -> notReady()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun confirmRemovePin() {
        if (!ble.isReady()) { notReady(); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_pin_title))
            .setMessage(getString(R.string.remove_pin_msg))
            .setPositiveButton(getString(R.string.btn_remove_pin)) { _, _ ->
                if (!ble.removePin()) notReady()
                store.appLock = false; ui.swAppLock.isChecked = false
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ======================= email recuperare =======================
    private fun showEmailSettings() {
        val email = EditText(this).apply {
            hint = getString(R.string.btn_email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(store.recoveryEmail ?: "")
        }
        val info = TextView(this).apply {
            text = getString(R.string.email_gmail_info)
            textSize = 12f
        }
        val user = EditText(this).apply {
            hint = getString(R.string.hint_gmail)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(store.smtpUser ?: "")
        }
        val pass = EditText(this).apply {
            hint = if (store.smtpConfigured) getString(R.string.hint_app_pass_saved) else getString(R.string.hint_app_pass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.email_title))
            .setView(column(email, info, user, pass))
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val e = email.text.toString().trim()
                if (e.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(e).matches()) { toast(getString(R.string.email_invalid)); return@setPositiveButton }
                store.recoveryEmail = e
                store.smtpUser = user.text.toString()
                if (pass.text.isNotBlank()) store.smtpPass = pass.text.toString()
                if (user.text.isBlank()) store.smtpPass = null
                toast(if (e.isEmpty()) getString(R.string.email_deleted) else getString(R.string.email_saved))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun recoverPin() {
        val pin = store.pin
        val email = store.recoveryEmail
        if (pin.isNullOrEmpty()) {
            info(getString(R.string.recover_title),
                getString(R.string.recover_not_saved))
            return
        }
        if (email.isNullOrEmpty()) {
            info(getString(R.string.recover_title), getString(R.string.recover_no_email))
            return
        }
        val subject = getString(R.string.mail_subject)
        val body = getString(R.string.mail_body, pin)
        if (store.smtpConfigured) {
            toast(getString(R.string.mail_sending))
            val u = store.smtpUser!!; val pw = store.smtpPass!!
            io.execute {
                val ok = try { MailSender.send(u, pw, email, subject, body); true } catch (e: Exception) { false }
                runOnUiThread {
                    if (ok) info(getString(R.string.mail_sent_title), getString(R.string.mail_sent_msg, mask(email)))
                    else info(getString(R.string.error_title), getString(R.string.mail_failed))
                }
            }
        } else {
            // fără Gmail configurat: se deschide aplicația de email cu mesajul pregătit către adresa ta
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            try { startActivity(i) } catch (_: Exception) { toast(getString(R.string.no_mail_app)) }
        }
    }

    private fun mask(e: String): String {
        val at = e.indexOf('@'); if (at < 2) return e
        return e.take(2) + "•••" + e.substring(at)
    }

    // ======================= blocare aplicație =======================
    private fun showAppLock() {
        val input = pinField(getString(R.string.hint_code))
        AlertDialog.Builder(this)
            .setTitle("🔒 Earthwheel Boost")
            .setMessage(getString(R.string.lock_msg))
            .setView(column(input))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_open)) { _, _ ->
                if (input.text.toString() == store.pin) {
                    unlocked = true
                    ensurePermissionsAndStart()
                } else { toast(getString(R.string.code_wrong)); showAppLock() }
            }
            .setNeutralButton(getString(R.string.btn_forgot)) { _, _ ->
                if (store.smtpConfigured && !store.recoveryEmail.isNullOrEmpty()) recoverPin()
                else info(getString(R.string.recover_title), getString(R.string.lock_recover_msg))
                showAppLock()
            }
            .setNegativeButton(getString(R.string.btn_exit)) { _, _ -> finish() }
            .show()
    }

    // ======================= altele =======================
    private fun rfLearn() {
        if (!ble.rfLearn()) notReady()
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.forget_title))
            .setMessage(getString(R.string.forget_msg))
            .setPositiveButton(getString(R.string.btn_yes)) { _, _ -> ble.forgetDevice() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun info(title: String, msg: String) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun notReady() = toast(getString(R.string.not_ready))
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_BT = 2
    }
}
