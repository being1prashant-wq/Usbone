package com.example.media

import android.content.Context
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class VideoCacheManager(private val context: Context) {

    private var currentTempFile: File? = null
    @Volatile private var currentSessionId: Long = 0L
    @Volatile private var isBuffering = false
    @Volatile private var currentWrittenBytes = 0L
    @Volatile private var currentTotalBytes = 0L

    fun getCachedVideoFile(): File? {
        val file = currentTempFile
        return if (file != null && file.exists() && file.length() > 0) file else null
    }

    fun isBufferingActive(): Boolean = isBuffering
    fun getBytesBuffered(): Long = currentWrittenBytes
    fun getTotalBytes(): Long = currentTotalBytes
    fun getCurrentSessionId(): Long = currentSessionId

    fun cancelBuffering() {
        currentSessionId = -1L
        isBuffering = false
        clearCache()
    }

    /**
     * Start progressive chunked streaming with strict session token isolation.
     * Once [initialThresholdBytes] is written to disk (e.g. 6MB to 8MB),
     * [onInitialBufferReady] is invoked so playback can begin immediately.
     */
    suspend fun streamVideoProgressive(
        client: PtpClient,
        handle: Int,
        filename: String = "",
        totalSizeBytes: Long = -1L,
        sessionId: Long,
        initialThresholdBytes: Long = 6 * 1024 * 1024L,
        onInitialBufferReady: suspend (file: File, initialBytes: Long, totalBytes: Long) -> Unit,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null,
        onComplete: suspend (file: File) -> Unit,
        onError: suspend (Exception) -> Unit
    ) = withContext(Dispatchers.IO) {
        currentSessionId = sessionId
        clearCache()

        val ext = filename.substringAfterLast('.', "mp4").lowercase().trim().ifBlank { "mp4" }
        val targetFile = File(context.cacheDir, "current_video_playback_${sessionId}.$ext")
        currentTempFile = targetFile
        isBuffering = true
        currentWrittenBytes = 0L
        currentTotalBytes = totalSizeBytes

        Log.i(PtpConstants.TAG, "Starting progressive video buffer for handle $handle (session=$sessionId, file=$filename)...")

        val effectiveThreshold = if (totalSizeBytes in 1..initialThresholdBytes) totalSizeBytes else initialThresholdBytes
        var initialReadyNotified = false

        try {
            FileOutputStream(targetFile).use { fos ->
                val progressiveOutputStream = object : OutputStream() {
                    var bytesWritten: Long = 0L
                    var lastReportTime: Long = 0L

                    override fun write(b: Int) {
                        if (currentSessionId != sessionId) {
                            return
                        }
                        fos.write(b)
                        bytesWritten++
                        currentWrittenBytes = bytesWritten
                        checkInitialReady()
                        checkReport()
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (currentSessionId != sessionId) {
                            return
                        }
                        fos.write(b, off, len)
                        bytesWritten += len
                        currentWrittenBytes = bytesWritten
                        checkInitialReady()
                        checkReport()
                    }

                    private fun checkInitialReady() {
                        if (!initialReadyNotified && bytesWritten >= effectiveThreshold && currentSessionId == sessionId) {
                            initialReadyNotified = true
                            try {
                                fos.flush()
                            } catch (_: Exception) {}
                            Log.i(PtpConstants.TAG, "Initial progressive buffer reached: $bytesWritten bytes for session $sessionId")
                            CoroutineScope(Dispatchers.Main).launch {
                                if (currentSessionId == sessionId) {
                                    onInitialBufferReady(targetFile, bytesWritten, totalSizeBytes)
                                }
                            }
                        }
                    }

                    private fun checkReport() {
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 350) {
                            lastReportTime = now
                            if (currentSessionId == sessionId) {
                                onProgress?.invoke(bytesWritten, totalSizeBytes)
                            }
                        }
                    }

                    override fun flush() {
                        fos.flush()
                    }

                    override fun close() {
                        fos.close()
                    }
                }

                val success = client.streamObject(
                    handle = handle,
                    outputStream = progressiveOutputStream,
                    isCancelled = { currentSessionId != sessionId || !isActive }
                )

                if (currentSessionId != sessionId) {
                    Log.i(PtpConstants.TAG, "Session $sessionId superseded, cancelling video cache completion")
                    isBuffering = false
                    if (targetFile.exists()) targetFile.delete()
                    return@withContext
                }

                isBuffering = false

                if (success && targetFile.exists() && targetFile.length() > 0) {
                    Log.i(PtpConstants.TAG, "Progressive stream completed: ${targetFile.length()} bytes (session=$sessionId)")
                    if (!initialReadyNotified) {
                        initialReadyNotified = true
                        onInitialBufferReady(targetFile, targetFile.length(), totalSizeBytes)
                    }
                    onComplete(targetFile)
                } else if (!initialReadyNotified) {
                    throw IllegalStateException("Failed to stream sufficient video data from device")
                }
            }
        } catch (e: Exception) {
            isBuffering = false
            if (currentSessionId == sessionId) {
                Log.e(PtpConstants.TAG, "Error during progressive video streaming for session $sessionId", e)
                onError(e)
            } else {
                Log.i(PtpConstants.TAG, "Ignoring video stream error for superseded session $sessionId")
                if (targetFile.exists()) targetFile.delete()
            }
        }
    }

    /**
     * Cache video with dual workflow (fallback full-buffer method)
     */
    suspend fun cacheVideo(
        client: PtpClient,
        handle: Int,
        filename: String = "",
        sessionId: Long,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        currentSessionId = sessionId
        clearCache()
        val ext = filename.substringAfterLast('.', "mp4").lowercase().trim().ifBlank { "mp4" }
        val targetFile = File(context.cacheDir, "current_video_playback_${sessionId}.$ext")
        currentTempFile = targetFile

        Log.i(PtpConstants.TAG, "Caching video handle $handle (session=$sessionId, file=$filename)...")

        try {
            var streamSuccess = false
            FileOutputStream(targetFile).use { fos ->
                val progressOutputStream = object : OutputStream() {
                    var bytesWritten: Long = 0L
                    var lastReportTime: Long = 0L

                    override fun write(b: Int) {
                        if (currentSessionId != sessionId) return
                        fos.write(b)
                        bytesWritten++
                        checkReport()
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (currentSessionId != sessionId) return
                        fos.write(b, off, len)
                        bytesWritten += len
                        checkReport()
                    }

                    override fun flush() {
                        fos.flush()
                    }

                    override fun close() {
                        fos.close()
                    }

                    private fun checkReport() {
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 300) {
                            lastReportTime = now
                            if (currentSessionId == sessionId) {
                                onProgress?.invoke(bytesWritten, -1L)
                            }
                        }
                    }
                }

                streamSuccess = client.streamObject(
                    handle = handle,
                    outputStream = progressOutputStream,
                    isCancelled = { currentSessionId != sessionId || !isActive }
                )
            }

            if (currentSessionId != sessionId) {
                if (targetFile.exists()) targetFile.delete()
                return@withContext null
            }

            if (streamSuccess && targetFile.exists() && targetFile.length() > 0) {
                Log.i(PtpConstants.TAG, "Video cached successfully: ${targetFile.length()} bytes")
                return@withContext targetFile
            }

            // Fallback
            if (targetFile.exists()) targetFile.delete()
            FileOutputStream(targetFile).use { fos ->
                val fallbackSuccess = client.streamObject(
                    handle = handle,
                    outputStream = fos,
                    isCancelled = { currentSessionId != sessionId || !isActive }
                )
                if (fallbackSuccess && currentSessionId == sessionId && targetFile.exists() && targetFile.length() > 0) {
                    return@withContext targetFile
                }
            }

            clearCache()
            null
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error caching video session $sessionId", e)
            clearCache()
            null
        }
    }

    fun clearCache() {
        try {
            currentTempFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
            val files = context.cacheDir.listFiles { _, name ->
                name.startsWith("current_video_playback")
            }
            files?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Failed to delete temp video files", e)
        }
    }
}

