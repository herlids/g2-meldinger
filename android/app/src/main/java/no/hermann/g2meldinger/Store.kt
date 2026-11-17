package no.hermann.g2meldinger

import android.app.Notification
import org.json.JSONArray
import org.json.JSONObject

data class ChatLine(val sender: String, val text: String, val ts: Long)

class Conversation(
    val id: String,
    val app: AppDef,
    var title: String,
    var messages: List<ChatLine>,
    var replyAction: Notification.Action?,
    var notificationKey: String?,
    var updatedAt: Long,
) {
    var ai: List<String> = emptyList()
    var aiStatus: String = "off"   // off | pending | ready | error
    var aiFor: String = ""         // signatur av meldingen forslagene gjelder
    var systemReplies: List<String> = emptyList() // Androids egne svarforslag (lokalt, gratis)

    val canReply get() = replyAction != null || id == Store.TEST_ID
    val signature get() = messages.lastOrNull()?.let { "${it.ts}|${it.text}" } ?: ""

    fun toJson() = JSONObject().apply {
        put("id", id)
        put("app", app.key)
        put("appLabel", app.label)
        put("title", title)
        put("messages", JSONArray().apply {
            messages.forEach { m ->
                put(JSONObject().put("sender", m.sender).put("text", m.text).put("ts", m.ts))
            }
        })
        put("canReply", canReply)
        put("ai", JSONArray(ai))
        put("aiStatus", aiStatus)
        put("updatedAt", updatedAt)
    }
}

/** Per-app diagnose: har vi sett varsler, og hadde de svarknapp? */
data class AppStat(var lastSeen: Long = 0, var withReply: Int = 0, var withoutReply: Int = 0)

/** Innboksen i minnet. All tilgang er synkronisert på objektet. */
object Store {
    const val TEST_ID = "test:demo"

    private val conversations = LinkedHashMap<String, Conversation>()
    val stats = HashMap<String, AppStat>()
    @Volatile var rev = 0L; private set
    @Volatile var lastTestReply: String? = null
    @Volatile var lastAiError: String? = null
    var onChange: (() -> Unit)? = null

    @Synchronized fun get(id: String) = conversations[id]

    @Synchronized fun upsert(c: Conversation) {
        conversations[c.id] = c
        bump()
    }

    @Synchronized fun remove(id: String) {
        if (conversations.remove(id) != null) bump()
    }

    @Synchronized fun removeByNotificationKey(key: String) {
        val ids = conversations.values.filter { it.notificationKey == key }.map { it.id }
        if (ids.isNotEmpty()) {
            ids.forEach { conversations.remove(it) }
            bump()
        }
    }

    @Synchronized fun count() = conversations.size

    @Synchronized fun findByNotificationKey(key: String) = conversations.values.firstOrNull { it.notificationKey == key }

    @Synchronized fun recordStat(app: AppDef, hasReply: Boolean) {
        val s = stats.getOrPut(app.key) { AppStat() }
        s.lastSeen = System.currentTimeMillis()
        if (hasReply) s.withReply++ else s.withoutReply++
    }

    @Synchronized fun bump() {
        rev++
        onChange?.invoke()
    }

    @Synchronized fun stateJson(quickReplies: List<String>): JSONObject {
        val list = conversations.values.sortedByDescending { it.updatedAt }
        return JSONObject().apply {
            put("rev", rev)
            put("conversations", JSONArray().apply { list.forEach { put(it.toJson()) } })
            put("quickReplies", JSONArray(quickReplies))
        }
    }

    fun injectTest() {
        val now = System.currentTimeMillis()
        upsert(
            Conversation(
                id = TEST_ID,
                app = AppDef("test", "Test", emptySet()),
                title = "Testperson",
                messages = listOf(ChatLine("Testperson", "Dette er en testmelding. Svar med ringen!", now)),
                replyAction = null,
                notificationKey = null,
                updatedAt = now,
            ).apply {
                ai = listOf("Det fungerer!", "Ser bra ut", "Testen virker")
                aiStatus = "ready"
            }
        )
    }
}
