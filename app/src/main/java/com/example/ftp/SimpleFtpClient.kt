package com.example.ftp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

class SimpleFtpClient {

    companion object {
        private const val TAG = "SimpleFtpClient"
        private const val TIMEOUT_MS = 10000
    }

    private var controlSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private val mutex = Mutex()

    var connectedHost: String = ""
        private set
    var connectedPort: Int = 2121
        private set

    val isConnected: Boolean
        get() = controlSocket?.isConnected == true && controlSocket?.isClosed == false

    suspend fun connect(
        host: String,
        port: Int = 2121,
        username: String = "anonymous",
        password: String = ""
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            disconnectInternal()
            try {
                connectedHost = host.trim()
                connectedPort = port

                Log.i(TAG, "Connecting to FTP server at $connectedHost:$connectedPort...")
                val socket = Socket()
                socket.connect(InetSocketAddress(connectedHost, connectedPort), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS

                controlSocket = socket
                reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

                // Read welcome banner
                val initialResp = readResponse()
                if (!initialResp.startsWith("220")) {
                    Log.e(TAG, "Unexpected welcome banner: $initialResp")
                    disconnectInternal()
                    return@withContext false
                }

                // Send USER
                val userCmd = if (username.isNotBlank()) username else "anonymous"
                val userResp = sendCommand("USER $userCmd")
                if (userResp.startsWith("331")) {
                    // Password required
                    val passResp = sendCommand("PASS $password")
                    if (!passResp.startsWith("230")) {
                        Log.e(TAG, "PASS rejected: $passResp")
                        disconnectInternal()
                        return@withContext false
                    }
                } else if (!userResp.startsWith("230")) {
                    Log.e(TAG, "USER rejected: $userResp")
                    disconnectInternal()
                    return@withContext false
                }

                // Set binary transfer mode
                sendCommand("TYPE I")
                Log.i(TAG, "FTP connected and authenticated successfully to $connectedHost:$connectedPort")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to FTP server", e)
                disconnectInternal()
                false
            }
        }
    }

    suspend fun getCurrentDirectory(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val resp = sendCommand("PWD")
                // Format: 257 "/path/to/dir" is current directory.
                val startIdx = resp.indexOf('"')
                val endIdx = resp.lastIndexOf('"')
                if (startIdx >= 0 && endIdx > startIdx) {
                    resp.substring(startIdx + 1, endIdx)
                } else {
                    resp.substringAfter("257 ").trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "PWD error", e)
                "/"
            }
        }
    }

    suspend fun changeDirectory(path: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val resp = sendCommand("CWD $path")
                resp.startsWith("250")
            } catch (e: Exception) {
                Log.w(TAG, "CWD error for $path", e)
                false
            }
        }
    }

    suspend fun changeToParentDirectory(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val resp = sendCommand("CDUP")
                resp.startsWith("200") || resp.startsWith("250")
            } catch (e: Exception) {
                Log.w(TAG, "CDUP error", e)
                false
            }
        }
    }

    suspend fun listFiles(): List<FtpFileItem> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val items = mutableListOf<FtpFileItem>()
            try {
                val dataSocket = openPassiveDataSocket() ?: return@withContext emptyList()
                val listResp = sendCommand("LIST")
                if (!listResp.startsWith("150") && !listResp.startsWith("125")) {
                    Log.w(TAG, "LIST failed: $listResp")
                    dataSocket.close()
                    return@withContext emptyList()
                }

                BufferedReader(InputStreamReader(dataSocket.getInputStream(), Charsets.UTF_8)).use { dataReader ->
                    var line: String? = dataReader.readLine()
                    while (line != null) {
                        val parsed = parseListLine(line)
                        if (parsed != null && parsed.name != "." && parsed.name != "..") {
                            items.add(parsed)
                        }
                        line = dataReader.readLine()
                    }
                }
                dataSocket.close()
                readResponse() // Read 226 Transfer complete

                // Sort: directories first, then alphabetical
                items.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                items
            } catch (e: Exception) {
                Log.e(TAG, "Error listing FTP files", e)
                items
            }
        }
    }

    /**
     * Download / Stream file from FTP into an OutputStream with chunking and progress.
     */
    suspend fun downloadFile(
        remoteFileName: String,
        outputStream: OutputStream,
        expectedSize: Long = 0L,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val dataSocket = openPassiveDataSocket() ?: return@withContext false
                val retrResp = sendCommand("RETR $remoteFileName")
                if (!retrResp.startsWith("150") && !retrResp.startsWith("125")) {
                    Log.e(TAG, "RETR failed: $retrResp")
                    dataSocket.close()
                    return@withContext false
                }

                // Determine file size if not provided
                var totalSize = expectedSize
                if (totalSize <= 0L) {
                    val matcher = Pattern.compile("\\(([0-9]+)\\s+bytes\\)").matcher(retrResp)
                    if (matcher.find()) {
                        totalSize = matcher.group(1)?.toLongOrNull() ?: 0L
                    }
                }

                val buffer = ByteArray(64 * 1024) // 64 KB chunk
                var bytesTransferred = 0L
                val inputStream = dataSocket.getInputStream()

                while (coroutineContext.isActive) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    outputStream.write(buffer, 0, read)
                    bytesTransferred += read
                    onProgress?.invoke(bytesTransferred, totalSize)
                }

                outputStream.flush()
                dataSocket.close()
                val compResp = readResponse() // 226 Transfer complete

                if (!coroutineContext.isActive) {
                    Log.w(TAG, "FTP download cancelled")
                    return@withContext false
                }

                compResp.startsWith("226") || bytesTransferred > 0
            } catch (e: Exception) {
                Log.e(TAG, "Error during FTP download for $remoteFileName", e)
                false
            }
        }
    }

    suspend fun deleteFile(remoteFileName: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val resp = sendCommand("DELE $remoteFileName")
                resp.startsWith("250")
            } catch (e: Exception) {
                Log.w(TAG, "DELE error for $remoteFileName", e)
                false
            }
        }
    }

    fun disconnect() {
        try {
            disconnectInternal()
        } catch (_: Exception) {}
    }

    private fun disconnectInternal() {
        try {
            if (controlSocket != null && !controlSocket!!.isClosed) {
                writer?.write("QUIT\r\n")
                writer?.flush()
            }
        } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        reader = null
        writer = null
    }

    private fun openPassiveDataSocket(): Socket? {
        val pasvResp = sendCommand("PASV")
        if (!pasvResp.startsWith("227")) {
            Log.e(TAG, "PASV command failed: $pasvResp")
            return null
        }

        // Format: 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
        val matcher = Pattern.compile("\\(([0-9]+),([0-9]+),([0-9]+),([0-9]+),([0-9]+),([0-9]+)\\)").matcher(pasvResp)
        if (!matcher.find()) {
            Log.e(TAG, "Failed to parse PASV response: $pasvResp")
            return null
        }

        val p1 = matcher.group(5)?.toIntOrNull() ?: return null
        val p2 = matcher.group(6)?.toIntOrNull() ?: return null
        val dataPort = (p1 and 0xFF shl 8) or (p2 and 0xFF)

        // Always prefer the host IP we are already connected to
        // because Android FTP servers behind hotspot/NAT often return internal IP or 0.0.0.0
        val dataHost = connectedHost

        val socket = Socket()
        socket.connect(InetSocketAddress(dataHost, dataPort), TIMEOUT_MS)
        socket.soTimeout = 30000 // 30s timeout for large file streaming
        return socket
    }

    private fun sendCommand(command: String): String {
        val w = writer ?: throw IllegalStateException("Not connected")
        w.write("$command\r\n")
        w.flush()
        return readResponse()
    }

    private fun readResponse(): String {
        val r = reader ?: throw IllegalStateException("Not connected")
        var line = r.readLine() ?: return ""
        Log.d(TAG, "FTP << $line")

        // Handle multiline response (e.g. 220-Welcome ... 220 Done)
        if (line.length >= 4 && line[3] == '-') {
            val code = line.substring(0, 3)
            val sb = StringBuilder(line)
            while (true) {
                val next = r.readLine() ?: break
                Log.d(TAG, "FTP << $next")
                sb.append("\n").append(next)
                if (next.length >= 4 && next.startsWith(code) && next[3] == ' ') {
                    line = next
                    break
                }
            }
        }
        return line
    }

    /**
     * Parse Unix or Windows/DOS FTP listing line.
     */
    private fun parseListLine(line: String): FtpFileItem? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        // 1. Unix format: drwxr-xr-x 2 owner group 4096 Jan 1 12:00 my_folder
        if (trimmed.startsWith("d") || trimmed.startsWith("-") || trimmed.startsWith("l")) {
            val isDir = trimmed.startsWith("d")
            // Split tokens by whitespace (max 9 tokens to preserve filename with spaces)
            val tokens = trimmed.split(Regex("\\s+"), limit = 9)
            if (tokens.size >= 9) {
                val size = tokens[4].toLongOrNull() ?: 0L
                val name = tokens[8]
                return FtpFileItem(name = name, isDirectory = isDir, sizeBytes = size)
            }
        }

        // 2. Windows/DOS format: 01-01-23 12:00PM <DIR> my_folder
        //                        01-01-23 12:00PM 1234567 my_video.mp4
        val dosTokens = trimmed.split(Regex("\\s+"), limit = 4)
        if (dosTokens.size >= 4) {
            if (dosTokens[2].equals("<DIR>", ignoreCase = true)) {
                return FtpFileItem(name = dosTokens[3], isDirectory = true, sizeBytes = 0L)
            } else {
                val size = dosTokens[2].toLongOrNull() ?: 0L
                return FtpFileItem(name = dosTokens[3], isDirectory = false, sizeBytes = size)
            }
        }

        return null
    }
}
