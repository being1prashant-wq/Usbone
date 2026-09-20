package com.example.media

import android.util.Log
import com.example.usb.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class PtpLoopbackServer(
    private val scope: CoroutineScope,
    private val repository: PtpMediaRepository
) {
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int
        get() = server?.localPort ?: -1

    fun start() {
        if (server != null) return
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server?.reuseAddress = true

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val socket = server?.accept() ?: break
                    launch(Dispatchers.IO) { handle(socket) }
                } catch (e: Exception) {
                    if (isActive) Log.e(PtpConstants.TAG, "loopback accept failed", e)
                }
            }
        }
        Log.i(PtpConstants.TAG, "PTP loopback server on 127.0.0.1:" + port)
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    fun urlFor(item: PtpMediaItem): String {
        return "http://127.0.0.1:" + port + "/ptp/" + item.handle
    }

    private suspend fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15000
            val input = BufferedInputStream(s.getInputStream(), 8192)
            val output = BufferedOutputStream(s.getOutputStream(), 8192)

            val requestLine = readLine(input) ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val tokens = requestLine.split(' ')
            if (tokens.size < 2) {
                writeStatus(output, 400, "Bad Request", 0L, null)
                return
            }

            val method = tokens[0].uppercase()
            if (method != "GET" && method != "HEAD") {
                writeStatus(output, 405, "Method Not Allowed", 0L, null)
                return
            }

            val path = tokens[1].substringBefore('?')
            val parts = path.trim('/').split('/')
            if (parts.size != 2 || parts[0] != "ptp") {
                writeStatus(output, 404, "Not Found", 0L, null)
                return
            }

            val handle = parts[1].toIntOrNull()
            val item = handle?.let { findItem(it) }
            if (handle == null || item == null || item.sizeBytes <= 0L) {
                writeStatus(output, 404, "Not Found", 0L, null)
                return
            }

            val total = item.sizeBytes
            val range = parseRange(headers["range"], total)
            val start = range?.first ?: 0L
            val end = range?.second ?: (total - 1L)

            if (start < 0L || start >= total || end < start) {
                writeStatus(output, 416, "Range Not Satisfiable", 0L, total)
                return
            }

            val length = end - start + 1L
            val contentType = mimeFor(item.displayName)
            val statusLine = if (range == null) "HTTP/1.1 200 OK" else "HTTP/1.1 206 Partial Content"

            val response = StringBuilder()
                .append(statusLine).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .apply {
                    if (range != null) {
                        append("Content-Range: bytes ")
                        append(start).append("-").append(end).append("/").append(total).append("\r\n")
                    }
                }
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n\r\n")
                .toString()

            output.write(response.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            if (method == "HEAD") return

            val client = repository.client ?: return
            val reader = PtpRangeReader(client)
            var offset = start
            var remaining = length

            while (remaining > 0L) {
                val ask = minOf(PtpRangeReader.MAX_CHUNK.toLong(), remaining).toInt()
                val bytes = reader.readAt(handle, offset, ask) ?: break
                if (bytes.isEmpty()) break

                output.write(bytes)
                output.flush()
                offset += bytes.size
                remaining -= bytes.size.toLong()

                if (bytes.size < ask) break
            }
        }
    }

    private fun findItem(handle: Int): PtpMediaItem? {
        return repository.videoItems.firstOrNull { it.handle == handle }
            ?: repository.audioItems.firstOrNull { it.handle == handle }
            ?: repository.photoItems.firstOrNull { it.handle == handle }
    }

    private fun parseRange(value: String?, total: Long): Pair<Long, Long>? {
        if (value.isNullOrBlank() || !value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=").substringBefore(',')
        val dash = spec.indexOf('-')
        if (dash < 0) return null

        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()

        return try {
            if (left.isEmpty()) {
                val suffix = right.toLong()
                if (suffix <= 0L) null
                else Pair((total - suffix).coerceAtLeast(0L), total - 1L)
            } else {
                val start = left.toLong()
                if (start < 0L || start >= total) null
                else Pair(start, if (right.isEmpty()) total - 1L else right.toLong().coerceAtMost(total - 1L))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeStatus(
        output: BufferedOutputStream,
        code: Int,
        reason: String,
        length: Long,
        total: Long?
    ) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Connection: close\r\n")
            if (total != null) append("Content-Range: bytes */").append(total).append("\r\n")
            append("\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArray(8192)
        var count = 0
        while (count < bytes.size) {
            val b = input.read()
            if (b < 0) return if (count == 0) null else String(bytes, 0, count, StandardCharsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes[count++] = b.toByte()
        }
        return String(bytes, 0, count, StandardCharsets.UTF_8)
    }

    private fun mimeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp", "3g2" -> "video/3gpp"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "oga", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }
}
