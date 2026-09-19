package com.example.media

import android.content.Context
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AudioCacheManager(private val context: Context) {

    private var currentTempFile: File? = null
    @Volatile private var currentSessionId: Long = 0L

    fun getCachedAudioFile(): File? {
        val file = currentTempFile
        return if (file != null && file.exists() && file.length() > 0) file else null
    }

    suspend fun cacheAudio(
        client: PtpClient,
        handle: Int,
        filename: String = "",
        sessionId: Long = 0L
    ): File? = withContext(Dispatchers.IO) {
        currentSessionId = sessionId
        clearCache()
        val ext = filename.substringAfterLast('.', "mp3").lowercase().trim().ifBlank { "mp3" }
        val targetFile = File(context.cacheDir, "current_audio_playback_${sessionId}.$ext")
        currentTempFile = targetFile

        Log.i(PtpConstants.TAG, "Caching selected audio handle $handle (session=$sessionId, file=$filename)...")

        try {
            FileOutputStream(targetFile).use { fos ->
                val success = client.streamObject(
                    handle = handle,
                    outputStream = fos,
                    isCancelled = { currentSessionId != sessionId || !isActive }
                )
                if (!success || currentSessionId != sessionId) {
                    Log.w(PtpConstants.TAG, "Audio caching cancelled or failed for session $sessionId")
                    if (targetFile.exists()) targetFile.delete()
                    return@withContext null
                }
            }
            if (targetFile.exists() && targetFile.length() > 0 && currentSessionId == sessionId) {
                Log.i(PtpConstants.TAG, "Audio cached successfully: ${targetFile.length()} bytes")
                targetFile
            } else {
                if (targetFile.exists()) targetFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error caching audio handle $handle", e)
            if (targetFile.exists()) targetFile.delete()
            null
        }
    }

    fun cancelBuffering() {
        currentSessionId = -1L
        clearCache()
    }

    fun clearCache() {
        try {
            currentTempFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
            val files = context.cacheDir.listFiles { _, name ->
                name.startsWith("current_audio_playback")
            }
            files?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Failed to delete temp audio file", e)
        }
    }
}

