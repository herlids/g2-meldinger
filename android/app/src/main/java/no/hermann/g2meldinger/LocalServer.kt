package no.hermann.g2meldinger

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Minimal HTTP-server på 127.0.0.1:8765 (kun tilgjengelig på telefonen selv).
 * G2-appen i Even-appen poller /api/state og poster svar til /api/reply.
 * Alle kall krever paringskoden (?token=…), så nettsider i nettleseren ikke kan lese meldingene.
 */
class LocalServer(
    private val prefs: Prefs,
    private val onReply: (id: String, text: String) -> Result,
    private val onDismiss: (id: String) -> Unit,
    private val onRefreshAi: (id: String) -> Unit,
) {
    data class Result(val ok: Boolean, val error: String? = null)

    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
                ss.reuseAddress = true
                socket = ss
                Log.i(TAG, "Lytter på 127.0.0.1:$PORT")
                while (running) {
                    val client = ss.accept()
                    pool.execute { handle(client) }
                }
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "Server stoppet", e)
            } catch (e: Exception) {
                Log.e(TAG, "Serverfeil", e)
            } finally {
                running = false
            }
        }, "g2-server").start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    val isRunning get() = running

    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 10_000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]

                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals("content-length", true)) {
                        contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength in 1..65_536) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                val (code, json) = route(method, target, body)
                write(sock, code, json)
            } catch (e: Exception) {
                Log.w(TAG, "Forespørsel feilet: ${e.message}")
            }
        }
    }

    private fun route(method: String, target: String, body: String): Pair<Int, String?> {
        if (method == "OPTIONS") return 204 to null

        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        if (!sameToken(query["token"] ?: "", prefs.token)) {
            return 401 to JSONObject().put("ok", false).put("error", "unauthorized").toString()
        }

        val data = if (body.isNotBlank()) runCatching { JSONObject(body) }.getOrNull() ?: JSONObject() else JSONObject()
        return when ("$method $path") {
            "GET /api/state" -> 200 to Store.stateJson(prefs.quickReplies).toString()
            "POST /api/reply" -> {
                val id = data.optString("id")
                val text = data.optString("text").trim()
                if (id.isEmpty() || text.isEmpty()) return 400 to err("Mangler id eller tekst")
                val r = onReply(id, text)
                (if (r.ok) 200 else 409) to JSONObject().put("ok", r.ok).put("error", r.error ?: "").toString()
            }
            "POST /api/dismiss" -> {
                onDismiss(data.optString("id"))
                200 to """{"ok":true}"""
            }
            "POST /api/ai" -> {
                onRefreshAi(data.optString("id"))
                200 to """{"ok":true}"""
            }
            "GET /api/ping" -> 200 to """{"ok":true,"app":"g2meldinger"}"""
            else -> 404 to err("Ukjent adresse")
        }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    private fun write(sock: Socket, code: Int, json: String?) {
        val status = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 409 -> "Conflict"; else -> "Error"
        }
        val bytes = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Access-Control-Max-Age: 600\r\n")
            append("Cache-Control: no-store\r\n")
            if (json != null) append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) break
        }
        return buf.toString("UTF-8")
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=')
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun sameToken(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val PORT = 8765
        private const val TAG = "G2Server"
    }
}
