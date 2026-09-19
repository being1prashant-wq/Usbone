package com.example.media

import android.content.Context
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class VideoCacheManager(private val context: Context) {

    private var currentTempFile: File? = null

    fun getCachedVideoFile(): File? {
        val file = currentTempFile
        return if (file != null && file.exists() && file.length() > 0) file else null
    }

    /**
     * Cache video with dual workflow:
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
