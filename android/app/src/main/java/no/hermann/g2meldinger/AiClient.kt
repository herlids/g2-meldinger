package no.hermann.g2meldinger

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Henter tre korte svarforslag fra Claude. Kalles på en bakgrunnstråd. */
object AiClient {
    private const val MODEL = "claude-haiku-5-5"
    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"

    fun suggest(apiKey: String, c: Conversation): List<String> {
        val thread = c.messages.takeLast(8).joinToString("\n") { m ->
            val who = if (m.sender.isBlank()) "Meg" else m.sender
            "$who: ${m.text}"
        }
        val prompt = """
            Du hjelper Hermann å svare raskt på meldinger fra smartbriller. Han velger ett av forslagene med en ring.
            Samtale (${c.app.label}, med ${c.title}):
            $thread

            Lag 3 ulike, naturlige svar Hermann kan sende på den siste meldingen.
            - Skriv på samme språk som samtalen (vanligvis norsk bokmål), uformelt og vennlig.
            - Maks 40 tegn per svar. Ingen anførselstegn.
            - Gi gjerne ett ja-aktig, ett nei/utsett-aktig og ett spørsmål eller nøytralt svar når det passer.
            Svar KUN med en JSON-liste med tre strenger, f.eks. ["…","…","…"].
        """.trimIndent()

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 200)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

        val conn = (URL(URL_MESSAGES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Claude API $code: ${text.take(200)}")
            val content = JSONObject(text).getJSONArray("content")
            val out = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                if (part.optString("type") == "text") out.append(part.optString("text"))
            }
            return parseList(out.toString())
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseList(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = JSONArray(raw.substring(start, end + 1))
        return (0 until arr.length())
            .map { arr.optString(it).trim().trim('"', '«', '»') }
            .filter { it.isNotEmpty() }
            .map { if (it.length > 60) it.take(59) + "…" else it }
            .distinct()
            .take(3)
    }
}
