package no.hermann.g2meldinger

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** Innstillinger: paringskode, standardsvar, AI-nøkkel og hvilke apper som er på. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("g2meldinger", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString(KEY_TOKEN, null) ?: newToken()
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    fun newToken(): String {
        val t = (SecureRandom().nextInt(900_000) + 100_000).toString()
        sp.edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    var quickReplies: List<String>
        get() = (sp.getString(KEY_QUICK, null) ?: DEFAULT_QUICK)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.take(12)
        set(v) = sp.edit().putString(KEY_QUICK, v.joinToString("\n")).apply()

    var apiKey: String
        get() = sp.getString(KEY_API, "") ?: ""
        set(v) = sp.edit().putString(KEY_API, v.trim()).apply()

    var aiEnabled: Boolean
        get() = sp.getBoolean(KEY_AI, true)
        set(v) = sp.edit().putBoolean(KEY_AI, v).apply()

    fun appEnabled(app: AppDef): Boolean = sp.getBoolean("app_" + app.key, true)
    fun setAppEnabled(app: AppDef, on: Boolean) = sp.edit().putBoolean("app_" + app.key, on).apply()

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_QUICK = "quick"
        private const val KEY_API = "api_key"
        private const val KEY_AI = "ai"
        const val DEFAULT_QUICK = "Ok 👍\nPå vei!\nRinger deg snart\nKan ikke snakke nå\nTakk!\nSvarer deg senere"
    }
}

/** Meldingsappene vi lytter på. Flere pakkenavn kan dele samme etikett. */
data class AppDef(val key: String, val label: String, val packages: Set<String>)

object Apps {
    val ALL = listOf(
        AppDef("sms", "SMS", setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")),
        AppDef("messenger", "Messenger", setOf("com.facebook.orca")),
        AppDef("snapchat", "Snapchat", setOf("com.snapchat.android")),
        AppDef("gmail", "Gmail", setOf("com.google.android.gm")),
    )

    fun forPackage(pkg: String): AppDef? = ALL.firstOrNull { pkg in it.packages }
}
