package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MediaThumbnailLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ptpClientProvider: () -> PtpClient?
) {
    // Keep max 30 thumbnails in RAM (~5-6 MB total)
    private val memoryCache = object : LruCache<Int, Bitmap>(30) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int = 1
    }

    private val loadingJobs = mutableMapOf<ImageView, Job>()

    fun loadThumbnail(
        item: PtpMediaItem,
        targetImageView: ImageView,
        placeholderResId: Int? = null,
        onLoaded: ((Bitmap?) -> Unit)? = null
    ) {
        val handle = item.handle

        // Check cache first
        val cached = memoryCache.get(handle)
        if (cached != null) {
            loadingJobs.remove(targetImageView)?.cancel()
            targetImageView.setImageBitmap(cached)
            onLoaded?.invoke(cached)
            return
        }

        placeholderResId?.let { targetImageView.setImageResource(it) }
        loadingJobs.remove(targetImageView)?.cancel()

        val job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadMediaThumbnailInternal(item)
            }

            if (bitmap != null) {
                memoryCache.put(handle, bitmap)
            }

            withContext(Dispatchers.Main) {
                if (targetImageView.tag == handle) {
                    if (bitmap != null) {
                        targetImageView.setImageBitmap(bitmap)
                    } else if (placeholderResId != null) {
                        targetImageView.setImageResource(placeholderResId)
                    }
                    onLoaded?.invoke(bitmap)
                }
            }
        }

        loadingJobs[targetImageView] = job
    }

    private suspend fun loadMediaThumbnailInternal(item: PtpMediaItem): Bitmap? {
        val client = ptpClientProvider()
        if (client == null || !client.isSessionOpen) return null

        try {
            // 1. First priority for all items: PTP thumbnail data if available on device
            val ptpThumb = client.getThumb(item.handle, targetWidth = 240)
            if (ptpThumb != null) return ptpThumb

            // 2. Audio artwork extraction: sample beginning of audio file without downloading whole file
            if (item.isAudio) {
                return extractAudioArtwork(client, item)
            }

            // 3. Video thumbnail: for small video or if partial preview is feasible
            if (item.isVideo) {
                return extractVideoThumbnail(client, item)
            }
        } catch (e: Exception) {
            Log.d(PtpConstants.TAG, "Thumbnail extraction exception for handle ${item.handle}: ${e.message}")
        }
        return null
    }

    private suspend fun extractAudioArtwork(client: PtpClient, item: PtpMediaItem): Bitmap? {
        // Read first 128KB where ID3 tags & APIC frames usually live
        val headerBytes = client.getPartialObject(item.handle, 0, 128 * 1024) ?: return null
        val tempThumbFile = File(context.cacheDir, "thumb_audio_${item.handle}.tmp")
        try {
            FileOutputStream(tempThumbFile).use { it.write(headerBytes) }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempThumbFile.absolutePath)
                val rawArt = retriever.embeddedPicture
                if (rawArt != null && rawArt.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeByteArray(rawArt, 0, rawArt.size, opts)
                    val sampleSize = PtpClient.calculateInSampleSize(opts, 240, 240)
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    return BitmapFactory.decodeByteArray(rawArt, 0, rawArt.size, decodeOpts)
                }
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            // Non-fatal, fallback to default icon
        } finally {
            if (tempThumbFile.exists()) tempThumbFile.delete()
        }
        return null
    }

    private suspend fun extractVideoThumbnail(client: PtpClient, item: PtpMediaItem): Bitmap? {
        // If file is very small (< 2MB) or we already have cached video
        val cached = File(context.cacheDir, "current_video_playback.${item.filename.substringAfterLast('.', "mp4")}")
        if (cached.exists() && cached.length() > 0) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(cached.absolutePath)
                return retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
        }
        return null
    }

    fun clear() {
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        memoryCache.evictAll()
    }
}
