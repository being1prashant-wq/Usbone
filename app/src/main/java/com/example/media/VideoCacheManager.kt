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
import kotlin.coroutines.coroutineContext

class VideoCacheManager(private val context: Context) {

    private val sharedDir = File(context.cacheDir, "shared_videos")
    private val localPlaybackFile = File(context.cacheDir, "current_video_playback.mp4")

    init {
        if (!sharedDir.exists()) {
            sharedDir.mkdirs()
        }
    }

    /**
     * Cache the selected video to app-private cache with progress reporting.
     * Uses 64-bit Long sizes and streaming chunked reads.
     * Never loads the full video into RAM.
     *
     * @param client The active PtpClient
     * @param item The selected PtpMediaItem
     * @param isForExternalShare Whether the file will be shared via FileProvider
     * @param onProgress Progress listener reporting (transferredBytes, totalBytes)
     * @return Cached File or null if failed/cancelled
     */
    suspend fun cacheVideo(
        client: PtpClient,
        item: PtpMediaItem,
        isForExternalShare: Boolean = false,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val targetFile = if (isForExternalShare) {
            pruneSharedCache()
            val ext = item.filename.substringAfterLast('.', "").ifBlank { "mp4" }
            val safeName = "shared_video_${item.handle}.$ext"
            File(sharedDir, safeName)
        } else {
            localPlaybackFile
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }

        Log.i(
            PtpConstants.TAG,
            "Caching video handle=${item.handle} ('${item.filename}', ${item.sizeBytes} bytes) to ${targetFile.name}..."
        )

        var transferSucceeded = false
        try {
            FileOutputStream(targetFile).use { fos ->
                // 1. If device supports GetPartialObject64 and size is known, try 64-bit chunked reads
                if (client.supportsPartialObject64 && item.sizeBytes > 0) {
                    val partialSuccess = streamViaPartial64(client, item, fos, onProgress)
                    if (partialSuccess) {
                        transferSucceeded = true
                    } else {
                        Log.w(PtpConstants.TAG, "PartialObject64 transfer incomplete or failed, falling back to standard streamObject")
                        // Reset target file for fallback
                        targetFile.delete()
                        FileOutputStream(targetFile).use { fallbackFos ->
                            transferSucceeded = client.streamObject(item.handle, fallbackFos, item.sizeBytes, onProgress)
                        }
                    }
                } else {
                    // 2. Safe streaming using bounded 512KB buffer directly to FileOutputStream
                    transferSucceeded = client.streamObject(item.handle, fos, item.sizeBytes, onProgress)
                }
            }

            if (!coroutineContext.isActive) {
                Log.w(PtpConstants.TAG, "Video caching cancelled by user")
                if (targetFile.exists()) targetFile.delete()
                return@withContext null
            }

            if (transferSucceeded && targetFile.exists() && targetFile.length() > 0) {
                Log.i(PtpConstants.TAG, "Video cached successfully: ${targetFile.length()} bytes")
                targetFile
            } else {
                Log.e(PtpConstants.TAG, "Video caching failed or produced empty file")
                if (targetFile.exists()) targetFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Exception during video caching", e)
            if (targetFile.exists()) targetFile.delete()
            null
        }
    }

    private suspend fun streamViaPartial64(
        client: PtpClient,
        item: PtpMediaItem,
        fos: FileOutputStream,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val totalSize = item.sizeBytes
        val chunkSize = 512 * 1024 // 512 KB chunks
        var offset = 0L

        while (offset < totalSize && coroutineContext.isActive) {
            val toRead = ((totalSize - offset).coerceAtMost(chunkSize.toLong())).toInt()
            val chunk = client.getPartialObject64(item.handle, offset, toRead)
            if (chunk == null || chunk.isEmpty()) {
                Log.w(PtpConstants.TAG, "GetPartialObject64 returned empty chunk at offset $offset")
                return@withContext false
            }
            fos.write(chunk)
            offset += chunk.size
            onProgress?.invoke(offset, totalSize)
        }
        fos.flush()
        offset >= totalSize && coroutineContext.isActive
    }

    fun clearCache() {
        try {
            if (localPlaybackFile.exists()) {
                localPlaybackFile.delete()
            }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Failed to delete local playback file", e)
        }
    }

    fun pruneSharedCache() {
        try {
            if (sharedDir.exists()) {
                val files = sharedDir.listFiles()
                files?.forEach { file ->
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Failed to prune shared video cache", e)
        }
    }

    fun clearAll() {
        clearCache()
        pruneSharedCache()
    }
}
