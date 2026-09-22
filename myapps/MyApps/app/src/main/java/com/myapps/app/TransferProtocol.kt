package com.myapps.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.math.min

object TransferProtocol {
    private const val CHUNK = 1024 * 1024
    private const val GCM_TAG_BITS = 128
    private const val NONCE_SIZE = 12
    private const val SALT = "MyApps-v2-local-transfer"
    private const val PROTOCOL = "2"

    data class ShareTarget(val host: String, val port: Int, val secret: String, val name: String)
    data class SelectedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String?,
        val relativePath: String = name
    )

    fun keyFromSecret(secret: String): SecretKeySpec {
        val spec = PBEKeySpec(secret.toCharArray(), SALT.toByteArray(StandardCharsets.UTF_8), 120_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun encrypt(key: SecretKeySpec, plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plain)
    }

    private fun decrypt(key: SecretKeySpec, packet: ByteArray): ByteArray {
        require(packet.size > NONCE_SIZE) { "Invalid encrypted packet" }
        val nonce = packet.copyOfRange(0, NONCE_SIZE)
        val encrypted = packet.copyOfRange(NONCE_SIZE, packet.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(encrypted)
    }

    private fun writePacket(out: DataOutputStream, key: SecretKeySpec, payload: ByteArray) {
        val packet = encrypt(key, payload)
        out.writeInt(packet.size)
        out.write(packet)
        out.flush()
    }

    private fun readPacket(input: DataInputStream, key: SecretKeySpec): ByteArray {
        val size = input.readInt()
        require(size in 17..(CHUNK + 256 * 1024)) { "Invalid packet size: $size" }
        val packet = ByteArray(size)
        input.readFully(packet)
        return decrypt(key, packet)
    }

    fun fileId(file: SelectedFile): String = sha256("${file.relativePath}|${file.size}|${file.uri}")

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun sha256File(context: Context, file: SelectedFile): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openFileDescriptor(file.uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    md.update(buffer, 0, n)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun localIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            val addresses = intf.inetAddresses
            while (addresses.hasMoreElements()) {
                val a = addresses.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) return a.hostAddress
            }
        }
        return null
    }

    fun queryFile(context: Context, uri: Uri, relativePath: String? = null): SelectedFile {
        var name = "file"
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
        return SelectedFile(uri, name, size, context.contentResolver.getType(uri), relativePath ?: name)
    }

    /** Recursively expands a Storage Access Framework folder while keeping its relative paths. */
    fun queryTreeFiles(context: Context, treeUri: Uri): List<SelectedFile> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val result = mutableListOf<SelectedFile>()
        walkTree(context, treeUri, rootId, "", result)
        return result
    }

    private fun walkTree(context: Context, treeUri: Uri, documentId: String, prefix: String, out: MutableList<SelectedFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        context.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        ), null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val id = c.getString(idCol)
                val name = c.getString(nameCol) ?: "file"
                val mime = c.getString(mimeCol)
                val rel = if (prefix.isBlank()) name else "$prefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(context, treeUri, id, rel, out)
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val size = if (!c.isNull(sizeCol)) c.getLong(sizeCol) else 0L
                    out += SelectedFile(uri, name, size, mime, rel)
                }
            }
        }
    }

    fun send(
        context: Context,
        target: ShareTarget,
        files: List<SelectedFile>,
        selfDestructMinutes: Int = 0,
        onProgress: (Int, String) -> Unit
    ) {
        require(files.isNotEmpty())
        Socket(target.host, target.port).use { socket ->
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 0
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            val key = keyFromSecret(target.secret)
            writePacket(output, key, "HELLO|MyApps|$PROTOCOL".toByteArray())
            require(String(readPacket(input, key), StandardCharsets.UTF_8) == "READY|MyApps|$PROTOCOL")
            writePacket(output, key, "POLICY|$selfDestructMinutes".toByteArray())
            require(String(readPacket(input, key), StandardCharsets.UTF_8) == "POLICY_OK")

            for ((index, file) in files.withIndex()) {
                val id = fileId(file)
                val hash = sha256File(context, file)
                val meta = listOf("START", id, file.relativePath, file.size.toString(), file.mime ?: "application/octet-stream", hash)
                    .joinToString("\u001f").toByteArray(StandardCharsets.UTF_8)
                writePacket(output, key, meta)
                val offsetReply = String(readPacket(input, key), StandardCharsets.UTF_8).split("\u001f")
                require(offsetReply.firstOrNull() == "OFFSET")
                var offset = offsetReply.getOrNull(1)?.toLongOrNull() ?: 0L
                if (offset > file.size) offset = 0L

                context.contentResolver.openFileDescriptor(file.uri, "r")!!.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { stream ->
                        var skipped = 0L
                        while (skipped < offset) {
                            val n = stream.skip(offset - skipped)
                            if (n <= 0) break
                            skipped += n
                        }
                        var sent = offset
                        val buffer = ByteArray(CHUNK)
                        val startTime = System.nanoTime()
                        while (sent < file.size) {
                            val want = min(buffer.size.toLong(), file.size - sent).toInt()
                            var read = 0
                            while (read < want) {
                                val n = stream.read(buffer, read, want - read)
                                if (n < 0) break
                                read += n
                            }
                            if (read <= 0) break
                            val payload = ByteArray(read + 1)
                            payload[0] = 1
                            System.arraycopy(buffer, 0, payload, 1, read)
                            writePacket(output, key, payload)
                            sent += read
                            val elapsed = (System.nanoTime() - startTime).coerceAtLeast(1L) / 1_000_000_000.0
                            val speed = sent / elapsed
                            onProgress(
                                ((sent * 100L) / file.size.coerceAtLeast(1)).toInt(),
                                "إرسال ${index + 1}/${files.size}: ${file.relativePath} • ${formatSpeed(speed)}"
                            )
                        }
                    }
                }
                writePacket(output, key, byteArrayOf(2))
                require(String(readPacket(input, key), StandardCharsets.UTF_8) == "DONE")
            }
            writePacket(output, key, "ALL_DONE".toByteArray())
        }
    }

    fun receive(context: Context, server: ServerSocket, secret: String, onProgress: (Int, String) -> Unit) {
        server.use { ss ->
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (_: Exception) { break }
                socket.use { s ->
                    try {
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
                        val output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                        val key = keyFromSecret(secret)
                        val hello = String(readPacket(input, key), StandardCharsets.UTF_8)
                        if (hello != "HELLO|MyApps|$PROTOCOL") continue
                        writePacket(output, key, "READY|MyApps|$PROTOCOL".toByteArray())
                        val policy = String(readPacket(input, key), StandardCharsets.UTF_8)
                        val ttl = policy.removePrefix("POLICY|").toIntOrNull()?.coerceIn(0, 7 * 24 * 60) ?: 0
                        writePacket(output, key, "POLICY_OK".toByteArray())
                        while (true) {
                            val first = readPacket(input, key)
                            val text = String(first, StandardCharsets.UTF_8)
                            if (text == "ALL_DONE") break
                            val parts = text.split("\u001f")
                            if (parts.firstOrNull() != "START" || parts.size < 6) error("Bad START")
                            val id = parts[1]
                            val relativePath = parts[2]
                            val size = parts[3].toLong()
                            val expectedHash = parts[5]
                            val base = File(context.getExternalFilesDir("Received"), "My Apps").apply { mkdirs() }
                            val safeRelative = sanitizeRelativePath(relativePath)
                            val finalFile = File(base, safeRelative)
                            finalFile.parentFile?.mkdirs()
                            val partFile = File(base, ".${id}.part")
                            var offset = if (partFile.exists()) partFile.length() else 0L
                            if (offset > size) { partFile.delete(); offset = 0L }
                            writePacket(output, key, "OFFSET\u001f$offset".toByteArray())
                            RandomAccessFile(partFile, "rw").use { raf ->
                                raf.seek(offset)
                                var received = offset
                                while (received < size) {
                                    val packet = readPacket(input, key)
                                    if (packet.isEmpty()) continue
                                    if (packet[0].toInt() != 1) error("Unexpected end")
                                    val data = packet.copyOfRange(1, packet.size)
                                    raf.write(data)
                                    received += data.size
                                    onProgress(((received * 100L) / size.coerceAtLeast(1)).toInt(), "استقبال: $safeRelative")
                                }
                                val end = readPacket(input, key)
                                if (end.firstOrNull()?.toInt() != 2 || received != size) error("Incomplete transfer")
                            }
                            val actualHash = sha256File(partFile)
                            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                                partFile.delete()
                                writePacket(output, key, "HASH_ERROR".toByteArray())
                                error("Integrity check failed")
                            }
                            finalFile.delete()
                            if (!partFile.renameTo(finalFile)) error("Could not finalize file")
                            if (ttl > 0) scheduleDelete(context, finalFile, ttl)
                            writePacket(output, key, "DONE".toByteArray())
                        }
                    } catch (_: Exception) {
                        // Partial .part files remain for a later connection/resume.
                    }
                }
            }
        }
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeRelativePath(path: String): String = path.split('/', '\\')
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString(File.separator) { it.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180).ifBlank { "file" } }
        .ifBlank { "file" }

    private fun scheduleDelete(context: Context, file: File, minutes: Int) {
        val request = OneTimeWorkRequestBuilder<DeleteFileWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf("path" to file.absolutePath))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun formatSpeed(bytesPerSecond: Double): String = when {
        bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / 1024 / 1024)
        bytesPerSecond >= 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024)
        else -> "%.0f B/s".format(bytesPerSecond)
    }
}
