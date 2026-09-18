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

    private val tempVideoFile = File(context.cacheDir, "current_video_playback.mp4")

    fun getCachedVideoFile(): File? {
        return if (tempVideoFile.exists() && tempVideoFile.length() > 0) tempVideoFile else null
    }

    suspend fun cacheVideo(client: PtpClient, handle: Int): File? = withContext(Dispatchers.IO) {
        clearCache()
        Log.i(PtpConstants.TAG, "Caching selected video handle $handle to temporary file...")

        try {
            FileOutputStream(tempVideoFile).use { fos ->
                val success = client.streamObject(handle, fos)
                if (!success) {
                    Log.e(PtpConstants.TAG, "Failed to stream video handle $handle")
                    clearCache()
                    return@withContext null
                }
            }
            if (tempVideoFile.exists() && tempVideoFile.length() > 0) {
                Log.i(PtpConstants.TAG, "Video cached successfully: ${tempVideoFile.length()} bytes")
                tempVideoFile
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
            if (tempVideoFile.exists()) {
                tempVideoFile.delete()
                Log.i(PtpConstants.TAG, "Temporary video cache cleared")
            }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Failed to delete temp video file", e)
        }
    }
}
