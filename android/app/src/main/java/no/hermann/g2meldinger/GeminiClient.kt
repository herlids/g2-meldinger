package no.hermann.g2meldinger

import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gratis svarforslag via Googles Gemini API (gratisnivå, nøkkel fra aistudio.google.com).
 * Merk: Google oppgir at data på gratisnivået brukes til å forbedre produktene deres.
 */
object GeminiClient {
    // «-latest»-aliaset følger nyeste Flash-Lite; faller tilbake til fast modell hvis aliaset mangler.
    private val MODELS = listOf("gemini-flash-lite-latest", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")

    fun suggest(apiKey: String, c: Conversation): List<String> {
        val prompt = AiClient.buildPrompt(c)
        var last: Exception? = null
        for (model in MODELS) {
            try {
                return call(apiKey, model, prompt)
            } catch (e: ModelMissing) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Ingen Gemini-modell tilgjengelig")
    }

    private class ModelMissing(msg: String) : Exception(msg)

    private fun call(apiKey: String, model: String, prompt: String): List<String> {
        val body = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.7)
                .put("maxOutputTokens", 512)
                .put("responseMimeType", "application/json"))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = try { stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (_: FileNotFoundException) { "" }
            if (code == 404) throw ModelMissing("$model finnes ikke")
            if (code !in 200..299) throw IllegalStateException("Gemini $code: ${text.take(200)}")
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (!part.optBoolean("thought", false)) out.append(part.optString("text"))
            }
            return AiClient.parseList(out.toString())
        } finally {
            conn.disconnect()
        }
    }
}
