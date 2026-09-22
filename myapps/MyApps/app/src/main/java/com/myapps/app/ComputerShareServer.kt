package com.myapps.app

import android.content.Context
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import android.util.Base64

class ComputerShareServer(private val context: Context) {
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    var token: String = ""
        private set
    var port: Int = 0
        private set

    fun start(): Boolean {
        if (server != null) return true
        return try {
            token = Base64.encodeToString(ByteArray(18).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            server = ServerSocket(0)
            port = server!!.localPort
            thread = Thread {
                while (server?.isClosed == false) {
                    try { handle(server!!.accept()) } catch (_: Exception) { if (server?.isClosed == true) break }
                }
            }.apply { isDaemon = true; start() }
            true
        } catch (_: Exception) { false }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        thread = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = s.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            val one = ByteArray(1)
            while (headerBytes.size() < 64 * 1024) {
                val n = input.read(one)
                if (n <= 0) return
                headerBytes.write(one[0].toInt())
                val a = headerBytes.toByteArray()
                val size = a.size
                if (size >= 4 && a[size - 4] == 13.toByte() && a[size - 3] == 10.toByte() && a[size - 2] == 13.toByte() && a[size - 1] == 10.toByte()) break
            }
            val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
            val lines = headerText.split("\r\n")
            val parts = lines.firstOrNull()?.split(' ') ?: return
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            var contentLength = 0L
            for (line in lines.drop(1)) {
                if (line.lowercase().startsWith("content-length:")) contentLength = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            }
            val uri = java.net.URI("http://127.0.0.1$target")
            val query = parseQuery(uri.rawQuery ?: "")
            if (query["token"] != token) { respond(s, 403, "text/plain; charset=utf-8", "Forbidden"); return }
            when {
                method == "GET" && uri.path == "/" -> respond(s, 200, "text/html; charset=utf-8", html())
                method == "GET" && uri.path == "/download" -> download(s, query["name"] ?: "")
                method == "PUT" && uri.path == "/upload" -> upload(s, input, contentLength, query["name"] ?: "upload.bin")
                else -> respond(s, 404, "text/plain; charset=utf-8", "Not found")
            }
        }
    }

    private fun upload(socket: Socket, input: java.io.InputStream, length: Long, name: String) {
        if (length <= 0 || length > 20L * 1024 * 1024 * 1024) { respond(socket, 400, "text/plain; charset=utf-8", "Invalid size"); return }
        val dir = File(context.getExternalFilesDir("Received"), "My Apps/Computer").apply { mkdirs() }
        val safe = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "upload.bin" }
        val out = File(dir, safe)
        java.io.FileOutputStream(out).use { fos ->
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n <= 0) break
                fos.write(buffer, 0, n)
                remaining -= n
            }
        }
        if (out.length() != length) { out.delete(); respond(socket, 400, "text/plain; charset=utf-8", "Incomplete upload"); return }
        respond(socket, 200, "text/plain; charset=utf-8", "OK")
    }

    private fun download(socket: Socket, name: String) {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        val file = File(context.getExternalFilesDir("Received"), "My Apps/Computer/$safe")
        if (!file.exists() || !file.isFile) { respond(socket, 404, "text/plain; charset=utf-8", "Not found"); return }
        val out = socket.getOutputStream()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        file.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun html(): String {
        val dir = File(context.getExternalFilesDir("Received"), "My Apps/Computer").apply { mkdirs() }
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val links = files.joinToString("\n") { f ->
            val n = URLEncoder.encode(f.name, "UTF-8")
            "<li><a href=\"/download?token=$token&name=$n\">${escape(f.name)}</a> (${f.length()} bytes)</li>"
        }
        return """
<!doctype html><html lang="ar" dir="rtl"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>My Apps</title><style>body{font-family:sans-serif;max-width:760px;margin:30px auto;padding:0 16px}button{padding:10px 16px}li{margin:10px 0}</style>
<h1>My Apps — ربط الكمبيوتر</h1><p>ارفع ملفات من الكمبيوتر مباشرة إلى الهاتف.</p>
<input id="f" type="file"><button onclick="up()">رفع</button><p id="s"></p><h2>الملفات المستلمة</h2><ul>$links</ul>
<script>
async function up(){const f=document.getElementById('f').files[0];if(!f)return;document.getElementById('s').textContent='جاري الرفع...';
const r=await fetch('/upload?token=$token&name='+encodeURIComponent(f.name),{method:'PUT',body:f});document.getElementById('s').textContent=r.ok?'تم الرفع ✓':'فشل الرفع';if(r.ok)location.reload();}
</script></html>
""".trimIndent()
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }.associate {
        val k = it.substringBefore('='); val v = it.substringAfter('=')
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun respond(socket: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        out.write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        out.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }
}
