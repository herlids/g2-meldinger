package no.hermann.g2meldinger

import android.app.ActivityOptions
import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

/**
 * Leser varsler fra SMS, Messenger og Snapchat, holder innboksen oppdatert,
 * og sender svar via varselets innebygde «Svar»-knapp (RemoteInput).
 * Kjører også den lokale serveren som G2-appen kobler til.
 */
class MessageListener : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private var server: LocalServer? = null
    private val aiPool = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        startServer()
        // Ta med varsler som allerede ligger der når tjenesten starter.
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        server?.stop()
        server = null
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        server?.stop()
        aiPool.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startServer() {
        if (server?.isRunning == true) return
        server = LocalServer(
            prefs,
            onReply = { id, text -> reply(id, text) },
            onDismiss = { id -> Store.remove(id) },
            onRefreshAi = { id -> Store.get(id)?.let { requestAi(it, force = true) } },
        ).also { it.start() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Lest eller avvist på telefonen → fjern fra innboksen på brillene også.
        if (Apps.forPackage(sbn.packageName) != null) Store.removeByNotificationKey(sbn.key)
    }

    // ---------- Tolking av varsler ----------

    private fun handle(sbn: StatusBarNotification) {
        val app = Apps.forPackage(sbn.packageName) ?: return
        if (!prefs.appEnabled(app)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras ?: return
        val messages = readMessages(extras)
        if (messages.isEmpty()) return

        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
            .ifEmpty { messages.last().sender.ifEmpty { app.label } }

        val reply = findReplyAction(n)
        Store.recordStat(app, reply != null)

        // Siste melding er fra meg (appen oppdaterte varselet etter at jeg svarte) → ikke ulest lenger.
        if (messages.last().sender.isEmpty()) {
            Store.removeByNotificationKey(sbn.key)
            return
        }

        val convKey = n.shortcutId ?: title
        val id = "${app.key}:${sbn.packageName.hashCode()}:$convKey"
        val existing = Store.get(id)
        val conv = existing ?: Conversation(id, app, title, messages, reply, sbn.key, sbn.postTime)
        val changed = existing == null || existing.signature != Conversation(id, app, title, messages, null, null, 0).signature
        conv.title = title
        conv.messages = messages
        conv.replyAction = reply
        conv.notificationKey = sbn.key
        conv.systemReplies = systemReplies(sbn, reply)
        if (changed) conv.updatedAt = maxOf(sbn.postTime, messages.last().ts)
        Store.upsert(conv)

        if (changed) requestAi(conv, force = false)
    }

    /** Androids egne svarforslag: fra systemet (Smart Reply) og fra appens egne valg i svarfeltet. */
    private fun systemReplies(sbn: StatusBarNotification, reply: Notification.Action?, map: RankingMap? = null): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            val ranking = Ranking()
            if ((map ?: currentRanking)?.getRanking(sbn.key, ranking) == true) {
                ranking.smartReplies?.forEach { out.add(it.toString().trim()) }
            }
        }
        reply?.remoteInputs?.forEach { ri -> ri.choices?.forEach { out.add(it.toString().trim()) } }
        return out.filter { it.isNotEmpty() }.take(3)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        super.onNotificationRankingUpdate(rankingMap)
        // Systemets forslag kommer ofte litt etter selve varselet.
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            if (Apps.forPackage(sbn.packageName) == null) continue
            val conv = Store.findByNotificationKey(sbn.key) ?: continue
            val replies = systemReplies(sbn, conv.replyAction, rankingMap)
            if (replies != conv.systemReplies) {
                conv.systemReplies = replies
                if (prefs.aiProvider == "android" || conv.aiStatus == "error") applySystemReplies(conv)
                Store.bump()
            }
        }
    }

    private fun applySystemReplies(conv: Conversation) {
        conv.ai = conv.systemReplies
        conv.aiStatus = if (conv.ai.isEmpty()) "off" else "ready"
    }

    private fun readMessages(extras: Bundle): List<ChatLine> {
        val raw: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        val out = ArrayList<ChatLine>()
        raw?.forEach { p ->
            val b = p as? Bundle ?: return@forEach
            val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@forEach
            val person: Person? = if (Build.VERSION.SDK_INT >= 33) {
                b.getParcelable("sender_person", Person::class.java)
            } else {
                @Suppress("DEPRECATION") b.getParcelable("sender_person")
            }
            // Tom avsender betyr «meg» i MessagingStyle.
            val sender = (person?.name ?: b.getCharSequence("sender"))?.toString()?.trim().orEmpty()
            out.add(ChatLine(sender, text, b.getLong("time")))
        }
        if (out.isNotEmpty()) return out.takeLast(10)

        // Enkle varsler uten MessagingStyle (f.eks. Snapchat).
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return listOf(ChatLine(title.ifEmpty { "?" }, text, System.currentTimeMillis()))
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        // Noen apper (bl.a. Gmail) legger svar-knappen bare i Wear OS-delen av varselet.
        @Suppress("DEPRECATION")
        val wear = runCatching { Notification.WearableExtender(n).actions }.getOrNull().orEmpty()
        val actions = (n.actions?.toList() ?: emptyList()) + wear
        val withInput = actions.filter { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        if (Build.VERSION.SDK_INT >= 28) {
            withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }?.let { return it }
        }
        return withInput.firstOrNull()
    }

    // ---------- Svar ----------

    private fun reply(id: String, text: String): LocalServer.Result {
        if (id == Store.TEST_ID) {
            Store.lastTestReply = text
            Store.remove(id)
            return LocalServer.Result(true)
        }
        val conv = Store.get(id) ?: return LocalServer.Result(false, "Meldingen er borte fra telefonen")
        val action = conv.replyAction ?: return LocalServer.Result(false, "${conv.app.label} tillater ikke svar fra varsel")
        val inputs = action.remoteInputs ?: return LocalServer.Result(false, "Mangler svarfelt")
        return try {
            val intent = Intent()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)

            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                action.actionIntent.send(this, 0, intent, null, null, null, opts.toBundle())
            } else {
                action.actionIntent.send(this, 0, intent)
            }
            Store.remove(id)
            LocalServer.Result(true)
        } catch (e: Exception) {
            Log.e(TAG, "Svar feilet", e)
            LocalServer.Result(false, "Kunne ikke sende: ${e.javaClass.simpleName}")
        }
    }

    // ---------- AI-forslag ----------

    private fun requestAi(conv: Conversation, force: Boolean) {
        if (!prefs.aiEnabled || !conv.canReply || conv.id == Store.TEST_ID) {
            conv.aiStatus = "off"; conv.ai = emptyList(); Store.bump(); return
        }
        val provider = prefs.aiProvider
        val key = when (provider) {
            "gemini" -> prefs.geminiKey
            "claude" -> prefs.apiKey
            else -> ""
        }
        if (provider == "android" || key.isBlank()) {
            applySystemReplies(conv); Store.bump(); return
        }
        val sig = conv.signature
        if (!force && conv.aiFor == sig && conv.aiStatus == "ready") return
        conv.aiFor = sig
        conv.aiStatus = "pending"
        conv.ai = emptyList()
        Store.bump()
        aiPool.execute {
            try {
                val list = if (provider == "gemini") GeminiClient.suggest(key, conv) else AiClient.suggest(key, conv)
                if (conv.aiFor == sig) {
                    if (list.isEmpty()) applySystemReplies(conv) else { conv.ai = list; conv.aiStatus = "ready" }
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI feilet: ${e.message}")
                Store.lastAiError = "${if (provider == "gemini") "Gemini" else "Claude"}: ${e.message?.take(120)}"
                // Fall tilbake på Androids egne forslag.
                if (conv.aiFor == sig) applySystemReplies(conv)
            }
            Store.bump()
        }
    }

    companion object {
        private const val TAG = "G2Listener"
        @Volatile var instance: MessageListener? = null
    }
}
