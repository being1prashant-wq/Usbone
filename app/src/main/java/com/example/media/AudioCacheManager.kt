package com.example.media

import android.content.Context
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AudioCacheManager(private val context: Context) {

    private var currentTempFile: File? = null

    fun getCachedAudioFile(): File? {
        val file = currentTempFile
        return if (file != null && file.exists() && file.length() > 0) file else null
    }

    suspend fun cacheAudio(client: PtpClient, handle: Int, filename: String = ""): File? = withContext(Dispatchers.IO) {
        clearCache()
        val ext = filename.substringAfterLast('.', "mp3").lowercase().trim().ifBlank { "mp3" }
        val targetFile = File(context.cacheDir, "current_audio_playback.$ext")
        currentTempFile = targetFile

        Log.i(PtpConstants.TAG, "Caching selected audio handle $handle ($filename) to ${targetFile.name}...")

        try {
            FileOutputStream(targetFile).use { fos ->
                val success = client.streamObject(handle, fos)
                if (!success) {
                    Log.e(PtpConstants.TAG, "Failed to stream audio handle $handle")
                    clearCache()
                    return@withContext null
                }
            }
            if (targetFile.exists() && targetFile.length() > 0) {
                Log.i(PtpConstants.TAG, "Audio cached successfully: ${targetFile.length()} bytes")
                targetFile
            } else {
                clearCache()
                null
            }
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error caching audio handle $handle", e)
            clearCache()
            null
        }
    }

    fun clearCache() {
        try {
            currentTempFile?.let {
                if (it.exists()) {
                    it.delete()
                    Log.i(PtpConstants.TAG, "Temporary audio cache cleared: ${it.name}")
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
