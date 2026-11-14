package no.hermann.g2meldinger

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Oppsett: varseltilgang, paringskode, standardsvar, AI og diagnose per app. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var accessButton: Button
    private lateinit var batteryButton: Button
    private lateinit var codeText: TextView
    private lateinit var diagText: TextView
    private val main = Handler(Looper.getMainLooper())
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        Store.onChange = { main.post { refresh() } }
        if (hasAccess()) {
            // Be systemet koble til lytteren på nytt hvis Samsung har stoppet den.
            NotificationListenerService.requestRebind(ComponentName(this, MessageListener::class.java))
        }
        refresh()
    }

    override fun onPause() {
        Store.onChange = null
        super.onPause()
    }

    // ---------- UI ----------

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(24), px(20), px(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(title("G2 Meldinger", 26f))
        root.addView(body("Les og svar på SMS, Messenger, Snapchat og Gmail fra Even G2 med ringen.", dim = true))

        // Status
        val status = card("STATUS")
        statusText = body("")
        status.addView(statusText)
        accessButton = button("Gi varseltilgang") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        status.addView(accessButton)
        batteryButton = button("Slå av batterioptimalisering") { askBattery() }
        status.addView(batteryButton)

        // Paringskode
        val pair = card("PARINGSKODE")
        pair.addView(body("Skriv inn denne koden i G2 Meldinger i Even-appen (Even Hub).", dim = true))
        codeText = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.15f
            setPadding(0, px(8), 0, px(8))
        }
        pair.addView(codeText)
        pair.addView(button("Lag ny kode") {
            prefs.newToken(); refresh()
            toast("Ny kode – skriv den inn i G2-appen på nytt")
        })

        // Standardsvar
        val quick = card("STANDARDSVAR")
        quick.addView(body("Ett svar per linje (maks 12). Vises under AI-forslagene på brillene.", dim = true))
        val quickEdit = EditText(this).apply {
            setText(prefs.quickReplies.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP
        }
        quick.addView(quickEdit)
        quick.addView(button("Lagre standardsvar") {
            prefs.quickReplies = quickEdit.text.toString().lines()
            Store.bump()
            toast("Lagret")
        })

        // AI
        val ai = card("AI-FORSLAG (CLAUDE)")
        val aiSwitch = Switch(this).apply {
            text = "Lag 3 svarforslag til hver ny melding"
            isChecked = prefs.aiEnabled
            setOnCheckedChangeListener { _, on -> prefs.aiEnabled = on }
        }
        ai.addView(aiSwitch)
        ai.addView(body("Meldingsteksten sendes til Anthropic for å lage forslagene. Nøkkel fra console.anthropic.com.", dim = true))
        val keyEdit = EditText(this).apply {
            hint = "sk-ant-…"
            setText(prefs.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ai.addView(keyEdit)
        ai.addView(button("Lagre nøkkel") {
            prefs.apiKey = keyEdit.text.toString()
            toast(if (prefs.apiKey.isBlank()) "Nøkkel fjernet" else "Nøkkel lagret")
        })

        // Apper
        val apps = card("APPER")
        Apps.ALL.forEach { app ->
            apps.addView(Switch(this).apply {
                text = app.label
                isChecked = prefs.appEnabled(app)
                setOnCheckedChangeListener { _, on -> prefs.setAppEnabled(app, on) }
                setPadding(0, px(6), 0, px(6))
            })
        }
        diagText = body("", dim = true)
        apps.addView(diagText)

        // Test
        val test = card("TEST")
        test.addView(body("Legger en falsk melding i innboksen på brillene. Svaret sendes ikke til noen.", dim = true))
        test.addView(button("Send testmelding til brillene") {
            Store.injectTest()
            toast("Åpne G2 Meldinger på brillene")
        })
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val access = hasAccess()
        val running = MessageListener.instance != null
        val battery = batteryOk()
        statusText.text = buildString {
            append(if (access) "● Varseltilgang gitt\n" else "○ Mangler varseltilgang\n")
            append(if (running) "● Tjenesten kjører (127.0.0.1:${LocalServer.PORT})\n" else "○ Tjenesten kjører ikke\n")
            append(if (battery) "● Batterioptimalisering av\n" else "○ Batterioptimalisering på – Samsung kan stoppe appen\n")
            append("Uleste samtaler: ${Store.count()}")
            Store.lastTestReply?.let { append("\nSiste testsvar fra brillene: «$it»") }
        }
        accessButton.text = if (access) "Varseltilgang (innstillinger)" else "Gi varseltilgang"
        batteryButton.visibility = if (battery) View.GONE else View.VISIBLE
        codeText.text = prefs.token

        diagText.text = buildString {
            append("\nSvarknapp i varslene:\n")
            Apps.ALL.forEach { app ->
                val s = synchronized(Store) { Store.stats[app.key]?.copy() }
                append("${app.label}: ")
                append(
                    when {
                        s == null -> "ingen varsler sett ennå"
                        s.withReply > 0 && s.withoutReply == 0 -> "ja ✓"
                        s.withReply > 0 -> "noen ganger (${s.withReply} av ${s.withReply + s.withoutReply})"
                        else -> "nei – kan bare leses"
                    }
                )
                if (s != null && s.lastSeen > 0) {
                    append(" · ").append(DateUtils.getRelativeTimeSpanString(s.lastSeen))
                }
                append("\n")
            }
        }
    }

    private fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun batteryOk(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    // ---------- Små byggeklosser ----------

    private fun px(v: Int) = (v * dp).toInt()

    private fun title(t: String, size: Float) = TextView(this).apply {
        text = t; textSize = size; typeface = Typeface.DEFAULT_BOLD
    }

    private fun body(t: String, dim: Boolean = false) = TextView(this).apply {
        text = t; textSize = 15f
        if (dim) alpha = 0.65f
        setPadding(0, px(4), 0, px(4))
    }

    private fun card(label: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0x14888888)
            }
        }
        c.addView(TextView(this).apply {
            text = label; textSize = 11f; letterSpacing = 0.06f; alpha = 0.6f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        return c
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
