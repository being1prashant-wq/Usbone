package com.example.media

import android.content.Context
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class VideoCacheManager(private val context: Context) {

    private var currentTempFile: File? = null
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

    /**
     * Start progressive chunked streaming.
     * Once [initialThresholdBytes] is written to disk (e.g. 6MB to 8MB),
     * [onInitialBufferReady] is invoked so playback can begin immediately without
     * waiting for the entire 3-4 GB file to download.
     * The background stream continues writing data to the file while playback proceeds.
     */
    suspend fun streamVideoProgressive(
        client: PtpClient,
        handle: Int,
        filename: String = "",
        totalSizeBytes: Long = -1L,
        initialThresholdBytes: Long = 6 * 1024 * 1024L, // 6 MB for fast initial startup
        onInitialBufferReady: suspend (file: File, initialBytes: Long, totalBytes: Long) -> Unit,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null,
        onComplete: suspend (file: File) -> Unit,
        onError: suspend (Exception) -> Unit
    ) = withContext(Dispatchers.IO) {
        clearCache()
        val ext = filename.substringAfterLast('.', "mp4").lowercase().trim().ifBlank { "mp4" }
        val targetFile = File(context.cacheDir, "current_video_playback.$ext")
        currentTempFile = targetFile
        isBuffering = true
        currentWrittenBytes = 0L
        currentTotalBytes = totalSizeBytes

        Log.i(PtpConstants.TAG, "Starting progressive video buffer for handle $handle ($filename)...")

        val effectiveThreshold = if (totalSizeBytes in 1..initialThresholdBytes) totalSizeBytes else initialThresholdBytes
        var initialReadyNotified = false

        try {
            FileOutputStream(targetFile).use { fos ->
                val progressiveOutputStream = object : OutputStream() {
                    var bytesWritten: Long = 0L
                    var lastReportTime: Long = 0L

                    override fun write(b: Int) {
                        fos.write(b)
                        bytesWritten++
                        currentWrittenBytes = bytesWritten
                        checkInitialReady()
                        checkReport()
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        fos.write(b, off, len)
                        bytesWritten += len
                        currentWrittenBytes = bytesWritten
                        checkInitialReady()
                        checkReport()
                    }

                    private fun checkInitialReady() {
                        if (!initialReadyNotified && bytesWritten >= effectiveThreshold) {
                            initialReadyNotified = true
                            try {
                                fos.flush()
                            } catch (_: Exception) {}
                            Log.i(PtpConstants.TAG, "Initial progressive buffer reached: $bytesWritten bytes. Ready for playback!")
                            CoroutineScope(Dispatchers.Main).launch {
                                onInitialBufferReady(targetFile, bytesWritten, totalSizeBytes)
                            }
                        }
                    }

                    private fun checkReport() {
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 400) {
                            lastReportTime = now
                            onProgress?.invoke(bytesWritten, totalSizeBytes)
                        }
                    }

                    override fun flush() {
                        fos.flush()
                    }

                    override fun close() {
                        fos.close()
                    }
                }

                val success = client.streamObject(handle, progressiveOutputStream)
                isBuffering = false

                if (success && targetFile.exists() && targetFile.length() > 0) {
                    Log.i(PtpConstants.TAG, "Progressive stream completed: ${targetFile.length()} bytes")
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
            Log.e(PtpConstants.TAG, "Error during progressive video streaming", e)
            onError(e)
        }
    }

    /**
     * Cache video with dual workflow (fallback full-buffer method):
     * Workflow 1: Fast progressive write (with progress callback)
     * Workflow 2: Standard fallback write
     */
    suspend fun cacheVideo(
        client: PtpClient,
        handle: Int,
        filename: String = "",
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        clearCache()
        val ext = filename.substringAfterLast('.', "mp4").lowercase().trim().ifBlank { "mp4" }
        val targetFile = File(context.cacheDir, "current_video_playback.$ext")
        currentTempFile = targetFile

        Log.i(PtpConstants.TAG, "Caching video handle $handle ($filename) to ${targetFile.name}...")

        try {
            // Workflow 1: Progressive stream with progress monitoring
            var streamSuccess = false
            FileOutputStream(targetFile).use { fos ->
                val progressOutputStream = object : OutputStream() {
                    var bytesWritten: Long = 0L
                    var lastReportTime: Long = 0L

                    override fun write(b: Int) {
                        fos.write(b)
                        bytesWritten++
                        checkReport()
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
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
                            onProgress?.invoke(bytesWritten, -1L)
                        }
                    }
                }

                streamSuccess = client.streamObject(handle, progressOutputStream)
            }

            if (streamSuccess && targetFile.exists() && targetFile.length() > 0) {
                Log.i(PtpConstants.TAG, "Video cached successfully: ${targetFile.length()} bytes")
                return@withContext targetFile
            }

            // Workflow 2: Fallback to direct raw stream if workflow 1 encountered any issue
            Log.w(PtpConstants.TAG, "Retrying caching with safe fallback workflow for handle $handle...")
            if (targetFile.exists()) targetFile.delete()

            FileOutputStream(targetFile).use { fos ->
                val fallbackSuccess = client.streamObject(handle, fos)
                if (fallbackSuccess && targetFile.exists() && targetFile.length() > 0) {
                    Log.i(PtpConstants.TAG, "Fallback video cache successful: ${targetFile.length()} bytes")
                    return@withContext targetFile
                }
            }

            clearCache()
            null
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error caching video", e)
            clearCache()
            null
        }
    }

    fun clearCache() {
        try {
            currentTempFile?.let {
                if (it.exists()) {
                    it.delete()
                    Log.i(PtpConstants.TAG, "Temporary video cache cleared: ${it.name}")
                }
            }
            val files = context.cacheDir.listFiles { _, name ->
                name.startsWith("current_video_playback")
            }
            files?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Failed to delete temp video file", e)
        }
    }
}
