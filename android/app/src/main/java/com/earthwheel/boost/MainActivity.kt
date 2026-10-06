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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.earthwheel.boost.BleManager.Companion as B
import com.earthwheel.boost.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var ui: ActivityMainBinding
    private lateinit var store: SecureStore
    private lateinit var ble: BleManager

    private var unlocked = false
    private var relayOn = false
    private var pinDialog: AlertDialog? = null
    private var pinPromptSnoozed = false   // utilizatorul a ales "Mai târziu"
    private var keyOk = false              // cheia aplicației e prezentă în APK
    private val io = Executors.newSingleThreadExecutor()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) startBle()
        else toast("Fără permisiunea Bluetooth aplicația nu se poate conecta la scooter.")
    }

    private val btEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        store = SecureStore(this)
        ble = BleManager(applicationContext, store, this)
        keyOk = Crypto.init(applicationContext)

        ui.btnMode.setOnClickListener { onModeClick(it) }
        ui.btnChangePin.setOnClickListener { showChangePin() }
        ui.btnRemovePin.setOnClickListener { confirmRemovePin() }
        ui.btnEmail.setOnClickListener { showEmailSettings() }
        ui.btnRecover.setOnClickListener { recoverPin() }
        ui.btnRfLearn.setOnClickListener { rfLearn() }
        ui.btnRfClear.setOnClickListener { if (ble.rfClear()) toast("Se șterge telecomanda…") else notReady() }
        ui.btnForget.setOnClickListener { confirmForget() }
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
                toast("Conectează-te întâi la scooter cu un cod activ.")
            } else store.appLock = checked
        }

        renderMode(false, false)
        renderSecurity(null)
        renderRf(null)

        ContextCompat.registerReceiver(
            this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (!keyOk) {
            info("Cheie lipsă", "Această copie a aplicației nu conține cheia de securitate (assets/ew_key.bin) și nu se poate conecta la scooter. Instalează APK-ul primit de la Claude sau compilează proiectul complet din arhivă.")
            return
        }
        if (store.appLock && !store.pin.isNullOrEmpty()) showAppLock() else unlocked = true
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
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startBle() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startBle() {
        val adapter = btAdapter()
        if (adapter == null) { toast("Telefonul nu are Bluetooth."); return }
        if (!adapter.isEnabled) {
            onConnState(BleManager.Conn.BT_DISABLED, 0)
            try { btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: SecurityException) { }
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
            ui.tvModeTitle.text = "MOD MELC"
            ui.tvModeSub.text = "Așteaptă conexiunea cu scooterul"
            return
        }
        ui.btnMode.alpha = 1f
        if (on) {
            ui.btnMode.setBackgroundResource(R.drawable.bg_rocket)
            ui.tvEmoji.text = "🚀"
            ui.tvModeTitle.text = "MOD RACHETĂ"
            ui.tvModeSub.text = "Viteză maximă · releu CUPLAT"
            ui.tvModeTitle.setTextColor(ContextCompat.getColor(this, R.color.ew_rocket_b))
        } else {
            ui.btnMode.setBackgroundResource(R.drawable.bg_snail)
            ui.tvEmoji.text = "🐌"
            ui.tvModeTitle.text = "MOD MELC"
            ui.tvModeSub.text = "Viteză normală · releu OPRIT"
            ui.tvModeTitle.setTextColor(ContextCompat.getColor(this, R.color.ew_green))
        }
    }

    // ======================= callback-uri BLE =======================
    private var shownState: BleManager.Conn? = null

    override fun onConnState(state: BleManager.Conn, extra: Int) {
        val prev = shownState
        shownState = state
        val (text, color) = when (state) {
            BleManager.Conn.OFF -> "●  Deconectat" to R.color.ew_gray
            BleManager.Conn.BT_DISABLED -> "●  Bluetooth oprit – atinge aici" to R.color.ew_red
            BleManager.Conn.SCANNING -> "●  Se caută scooterul…" to R.color.ew_gray
            BleManager.Conn.CONNECTING -> "●  Se conectează…" to R.color.ew_gray
            BleManager.Conn.AUTHENTICATING -> "●  Verificare securitate…" to R.color.ew_gray
            BleManager.Conn.NEED_PIN -> "●  Introdu codul (atinge aici)" to R.color.ew_red
            BleManager.Conn.LOCKED -> "●  Blocat $extra s – prea multe coduri greșite" to R.color.ew_red
            BleManager.Conn.READY -> "●  Conectat la scooter" to R.color.ew_green
        }
        ui.tvConn.text = text
        ui.tvConn.setTextColor(ContextCompat.getColor(this, color))

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
                if (prev != BleManager.Conn.LOCKED) toast("Prea multe încercări greșite. Așteaptă $extra secunde.")
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
            B.RES_PIN_OK -> toast("Codul a fost schimbat ✔")
            B.RES_PIN_REMOVED -> toast("Codul a fost scos. Rămâne activă cheia unică a aplicației.")
            B.RES_RF_LEARNED -> toast("Telecomanda a fost asociată ✔")
            B.RES_RF_CLEARED -> toast("Telecomanda a fost ștearsă")
            B.RES_RF_TIMEOUT -> toast("Nu s-a primit niciun semnal de la telecomandă.")
            B.RES_RF_DISABLED -> toast("Modulul RF nu este activat în firmware (ENABLE_RF 1).")
            B.RES_RF_LEARNING -> toast("Apasă acum butonul telecomenzii (${status.extra} s)…")
            B.RES_BAD_MAC, B.RES_BAD_CMD, B.RES_NOT_AUTH -> toast("Comandă respinsă de placă.")
        }
    }

    private fun renderSecurity(s: BleManager.Status?) {
        val ready = s != null
        ui.btnChangePin.isEnabled = ready
        ui.btnRemovePin.isEnabled = ready && s!!.pinSet
        ui.btnChangePin.text = if (s?.pinSet == false) "Setează un cod" else "Schimbă codul"
        ui.tvPinInfo.text = when {
            s == null -> "Conectează-te la scooter pentru setări."
            s.pinSet -> "Cod activ. Doar această aplicație + codul tău pot comanda scooterul."
            else -> "Fără cod: doar această aplicație (cheie unică) se poate conecta."
        }
    }

    private fun renderRf(s: BleManager.Status?) {
        val on = s?.rfEnabled == true
        ui.btnRfLearn.isEnabled = on
        ui.btnRfClear.isEnabled = on && s!!.rfLearned
        ui.tvRf.text = when {
            s == null -> "—"
            !s.rfEnabled -> "Neactivat în firmware. Pregătit pentru receptor 433 MHz (vezi README)."
            s.rfLearning -> "Aștept semnal… apasă butonul telecomenzii."
            s.rfLearned -> "Telecomandă asociată – un clic comută MELC/RACHETĂ."
            else -> "Nicio telecomandă asociată."
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
        val input = pinField("Codul scooterului")
        val msg = if (triesLeft in 1..9) "Cod greșit. Mai ai $triesLeft încercări." else "Introdu codul de acces al scooterului."
        pinDialog = MaterialAlertDialogBuilder(this)
            .setTitle("🔒 Cod de acces")
            .setMessage(msg)
            .setView(column(input))
            .setCancelable(false)
            .setPositiveButton("Conectează") { _, _ ->
                pinDialog = null
                val p = input.text.toString()
                pinPromptSnoozed = false
                if (validPin(p)) ble.authenticate(p) else { toast("Codul are 4–16 cifre."); showPinDialog(triesLeft) }
            }
            .setNeutralButton("Am uitat codul") { _, _ -> pinDialog = null; recoverPin() }
            .setNegativeButton("Mai târziu") { _, _ -> pinDialog = null; pinPromptSnoozed = true }
            .show()
    }

    private fun showChangePin() {
        if (!ble.isReady()) { notReady(); return }
        val a = pinField("Cod nou (4–16 cifre)")
        val b = pinField("Repetă codul nou")
        MaterialAlertDialogBuilder(this)
            .setTitle("Cod nou")
            .setView(column(a, b))
            .setPositiveButton("Salvează") { _, _ ->
                val p1 = a.text.toString(); val p2 = b.text.toString()
                when {
                    !validPin(p1) -> toast("Codul trebuie să aibă 4–16 cifre.")
                    p1 != p2 -> toast("Codurile nu coincid.")
                    !ble.changePin(p1) -> notReady()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun confirmRemovePin() {
        if (!ble.isReady()) { notReady(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Scoți codul?")
            .setMessage("Scooterul va putea fi comandat din această aplicație fără cod. Conexiunea rămâne protejată de cheia unică a aplicației.")
            .setPositiveButton("Scoate codul") { _, _ ->
                if (!ble.removePin()) notReady()
                store.appLock = false; ui.swAppLock.isChecked = false
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    // ======================= email recuperare =======================
    private fun showEmailSettings() {
        val email = EditText(this).apply {
            hint = "Email de recuperare"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(store.recoveryEmail ?: "")
        }
        val info = TextView(this).apply {
            text = "\nOpțional – trimitere automată prin Gmail:\n(cont Gmail + parolă de aplicație din Google › Securitate › Parole pentru aplicații)"
            textSize = 12f
        }
        val user = EditText(this).apply {
            hint = "Cont Gmail expeditor"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(store.smtpUser ?: "")
        }
        val pass = EditText(this).apply {
            hint = if (store.smtpConfigured) "Parolă aplicație (salvată)" else "Parolă de aplicație (16 caractere)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("📧 Email de recuperare")
            .setView(column(email, info, user, pass))
            .setPositiveButton("Salvează") { _, _ ->
                val e = email.text.toString().trim()
                if (e.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(e).matches()) { toast("Email invalid."); return@setPositiveButton }
                store.recoveryEmail = e
                store.smtpUser = user.text.toString()
                if (pass.text.isNotBlank()) store.smtpPass = pass.text.toString()
                if (user.text.isBlank()) store.smtpPass = null
                toast(if (e.isEmpty()) "Email șters." else "Email salvat ✔")
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun recoverPin() {
        val pin = store.pin
        val email = store.recoveryEmail
        if (pin.isNullOrEmpty()) {
            info("Recuperare cod",
                "Codul nu este salvat pe acest telefon.\n\nReset din placă: cu scooterul pornit, ține apăsat butonul BOOT de pe ESP32 timp de 10 secunde (LED-ul clipește). Codul revine la cel implicit din fabrică.")
            return
        }
        if (email.isNullOrEmpty()) {
            info("Recuperare cod", "Nu ai setat un email de recuperare. Setează-l din „Email de recuperare” cât timp știi codul.\n\nAlternativ: reset din placă (BOOT 10 s).")
            return
        }
        val subject = "Earthwheel Boost – codul tău de acces"
        val body = "Salut!\n\nCodul de acces pentru scooterul tău Earthwheel este: $pin\n\nDacă nu ai cerut acest email, schimbă codul din aplicație.\n\n— Earthwheel Boost"
        if (store.smtpConfigured) {
            toast("Se trimite emailul…")
            val u = store.smtpUser!!; val pw = store.smtpPass!!
            io.execute {
                val ok = try { MailSender.send(u, pw, email, subject, body); true } catch (e: Exception) { false }
                runOnUiThread {
                    if (ok) info("Email trimis", "Codul a fost trimis la ${mask(email)}.")
                    else info("Eroare", "Emailul nu a putut fi trimis. Verifică internetul și parola de aplicație Gmail.")
                }
            }
        } else {
            // fără Gmail configurat: se deschide aplicația de email cu mesajul pregătit către adresa ta
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            try { startActivity(i) } catch (_: Exception) { toast("Nu există o aplicație de email instalată.") }
        }
    }

    private fun mask(e: String): String {
        val at = e.indexOf('@'); if (at < 2) return e
        return e.take(2) + "•••" + e.substring(at)
    }

    // ======================= blocare aplicație =======================
    private fun showAppLock() {
        val input = pinField("Cod")
        MaterialAlertDialogBuilder(this)
            .setTitle("🔒 Earthwheel Boost")
            .setMessage("Introdu codul pentru a deschide aplicația.")
            .setView(column(input))
            .setCancelable(false)
            .setPositiveButton("Deschide") { _, _ ->
                if (input.text.toString() == store.pin) {
                    unlocked = true
                    ensurePermissionsAndStart()
                } else { toast("Cod greșit."); showAppLock() }
            }
            .setNeutralButton("Am uitat codul") { _, _ ->
                if (store.smtpConfigured && !store.recoveryEmail.isNullOrEmpty()) recoverPin()
                else info("Recuperare cod", "Pentru siguranță, de pe ecranul blocat codul se poate trimite doar automat pe emailul de recuperare (Gmail configurat).\n\nAlternativ: reinstalează aplicația și resetează placa (BOOT 10 s).")
                showAppLock()
            }
            .setNegativeButton("Ieșire") { _, _ -> finish() }
            .show()
    }

    // ======================= altele =======================
    private fun rfLearn() {
        if (!ble.rfLearn()) notReady()
    }

    private fun confirmForget() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Asociezi altă placă?")
            .setMessage("Aplicația va uita placa actuală și codul salvat, apoi va căuta din nou.")
            .setPositiveButton("Da") { _, _ -> ble.forgetDevice() }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun info(title: String, msg: String) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun notReady() = toast("Scooterul nu este conectat încă.")
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
