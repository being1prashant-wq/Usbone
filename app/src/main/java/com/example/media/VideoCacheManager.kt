package com.example.media

import android.content.Context
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class VideoCacheManager(private val context: Context) {

    private var currentTempFile: File? = null

    fun getCachedVideoFile(): File? {
        val file = currentTempFile
        return if (file != null && file.exists() && file.length() > 0) file else null
    }

    suspend fun cacheVideo(client: PtpClient, handle: Int, filename: String = ""): File? = withContext(Dispatchers.IO) {
        clearCache()
        val ext = filename.substringAfterLast('.', "mp4").lowercase().trim().ifBlank { "mp4" }
        val targetFile = File(context.cacheDir, "current_video_playback.$ext")
        currentTempFile = targetFile

        Log.i(PtpConstants.TAG, "Caching selected video handle $handle ($filename) to ${targetFile.name}...")

        try {
            FileOutputStream(targetFile).use { fos ->
                val success = client.streamObject(handle, fos)
                if (!success) {
                    Log.e(PtpConstants.TAG, "Failed to stream video handle $handle")
                    clearCache()
                    return@withContext null
                }
            }
            if (targetFile.exists() && targetFile.length() > 0) {
                Log.i(PtpConstants.TAG, "Video cached successfully: ${targetFile.length()} bytes")
                targetFile
            } else {
                clearCache()
                null
            }
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
