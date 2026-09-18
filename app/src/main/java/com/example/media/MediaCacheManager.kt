package com.example.media

import android.content.Context
import android.util.Log
import com.example.mtp.MtpClient
import java.io.File
import java.io.FileOutputStream

class MediaCacheManager(private val context: Context) {
    private val tag = "MediaCacheManager"
    private val cacheDir: File = File(context.cacheDir, "directusb_stream_cache").apply { mkdirs() }
    private val thumbDir: File = File(context.cacheDir, "directusb_thumbs").apply { mkdirs() }

    private var activeCachedFile: File? = null

    fun getCacheFileForObject(handle: Int, extension: String): File {
        val safeExt = if (extension.isNotBlank()) ".$extension" else ""
        return File(cacheDir, "media_obj_${handle}$safeExt")
    }

    fun getThumbnailFile(handle: Int): File {
        return File(thumbDir, "thumb_${handle}.jpg")
    }

    fun cacheMediaLocally(
        client: MtpClient,
        objectHandle: Int,
        extension: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): File? {
        val targetFile = getCacheFileForObject(objectHandle, extension)
        if (targetFile.exists() && targetFile.length() > 0) {
            activeCachedFile = targetFile
            return targetFile
        }

        // Clean previous cached media file to avoid filling TV storage
        cleanupPreviousCacheExcept(targetFile)

        Log.d(tag, "Streaming MTP object $objectHandle to TV temporary cache ${targetFile.name}")
        val tempFile = File(cacheDir, "${targetFile.name}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                val ok = client.getObject(objectHandle, fos, onProgress)
                if (!ok) {
                    Log.e(tag, "Failed to stream object $objectHandle from MTP")
                    tempFile.delete()
                    return null
                }
            }
            if (tempFile.renameTo(targetFile)) {
                activeCachedFile = targetFile
                return targetFile
            }
        } catch (e: Exception) {
            Log.e(tag, "Error caching media file", e)
            tempFile.delete()
        }
        return null
    }

    fun cacheThumbnail(client: MtpClient, objectHandle: Int): File? {
        val thumbFile = getThumbnailFile(objectHandle)
        if (thumbFile.exists()) return thumbFile

        try {
            val thumbBytes = client.getThumb(objectHandle)
            if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                thumbFile.writeBytes(thumbBytes)
                return thumbFile
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to cache thumb for $objectHandle", e)
        }
        return null
    }

    private fun cleanupPreviousCacheExcept(keepFile: File) {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.absolutePath != keepFile.absolutePath) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error cleaning cache", e)
        }
    }

    fun clearAllCache() {
        try {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            thumbDir.deleteRecursively()
            thumbDir.mkdirs()
        } catch (e: Exception) {
            Log.e(tag, "Error clearing all cache", e)
        }
    }
}
