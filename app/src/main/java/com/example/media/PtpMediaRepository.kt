package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

class PtpMediaRepository(private val context: Context) {

    private var activeClient: PtpClient? = null
    private var reportedDeviceName: String = "PTP Device"

    val photoItems = mutableListOf<PtpMediaItem>()
    val videoItems = mutableListOf<PtpMediaItem>()

    var isVideoScanInProgress: Boolean = false
        private set

    val isSessionReady: Boolean
        get() = activeClient?.isSessionOpen == true

    val deviceName: String
        get() = reportedDeviceName

    val client: PtpClient?
        get() = activeClient

    suspend fun initialize(ptpClient: PtpClient, fallbackName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            activeClient = ptpClient
            photoItems.clear()
            videoItems.clear()

            Log.i(PtpConstants.TAG, "Step 1: OpenSession...")
            if (!ptpClient.openSession()) {
                Log.e(PtpConstants.TAG, "OpenSession failed")
                return@withContext false
            }

            // 1. Get Device Info
            Log.i(PtpConstants.TAG, "Step 2: GetDeviceInfo...")
            val devInfo = ptpClient.getDeviceInfo()
            reportedDeviceName = if (devInfo != null && (devInfo.manufacturer.isNotBlank() || devInfo.model.isNotBlank())) {
                "${devInfo.manufacturer} ${devInfo.model}".trim()
            } else {
                fallbackName
            }
            Log.i(PtpConstants.TAG, "PTP session opened. Device name: $reportedDeviceName")

            // 2. Get Storage IDs
            Log.i(PtpConstants.TAG, "Step 3: GetStorageIDs...")
            val storageIds = ptpClient.getStorageIds()
            val primaryStorageId = if (storageIds.isNotEmpty()) storageIds[0] else PtpConstants.STORAGE_ALL

            // 3. Fast initial discovery for instantaneous UI response
            Log.i(PtpConstants.TAG, "Step 4: Fast initial media discovery...")
            discoverInitialMedia(ptpClient, primaryStorageId)

            Log.i(PtpConstants.TAG, "PTP Initial Ready: ${photoItems.size} photos, ${videoItems.size} videos")
            true
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "PTP initialization failed with exception", e)
            false
        }
    }

    private suspend fun discoverInitialMedia(ptpClient: PtpClient, storageId: Int) {
        val foundPhotos = mutableSetOf<Int>()
        val foundVideos = mutableSetOf<Int>()

        // 1. Quick Image format queries
        val imageFormats = intArrayOf(
            PtpConstants.FORMAT_EXIF_JPEG,
            PtpConstants.FORMAT_JFIF,
            PtpConstants.FORMAT_PNG,
            PtpConstants.FORMAT_HEIF
        )
        for (fmt in imageFormats) {
            try {
                val handles = ptpClient.getObjectHandles(
                    storageId = storageId,
                    formatCode = fmt,
                    parentHandle = PtpConstants.PARENT_ALL
                )
                for (h in handles) foundPhotos.add(h)
            } catch (e: Exception) {
                Log.w(PtpConstants.TAG, "Quick image discovery error for format 0x${fmt.toString(16)}", e)
            }
        }

        // 2. Quick Video format queries (MP4, MKV, AVI, MOV, 3GP, WMV, WEBM, etc.)
        val videoFormats = intArrayOf(
            PtpConstants.FORMAT_MP4,
            PtpConstants.FORMAT_MKV,
            PtpConstants.FORMAT_AVI,
            PtpConstants.FORMAT_MOV,
            PtpConstants.FORMAT_3GP,
            PtpConstants.FORMAT_3G2,
            PtpConstants.FORMAT_WMV,
            PtpConstants.FORMAT_MPEG,
            PtpConstants.FORMAT_WEBM
        )
        for (fmt in videoFormats) {
            try {
                val handles = ptpClient.getObjectHandles(
                    storageId = storageId,
                    formatCode = fmt,
                    parentHandle = PtpConstants.PARENT_ALL
                )
                for (h in handles) foundVideos.add(h)
            } catch (e: Exception) {
                Log.w(PtpConstants.TAG, "Quick video discovery error for format 0x${fmt.toString(16)}", e)
            }
        }

        for (h in foundPhotos) {
            photoItems.add(PtpMediaItem(handle = h, isVideo = false))
        }
        for (h in foundVideos) {
            videoItems.add(PtpMediaItem(handle = h, isVideo = true))
        }

        // 3. Fallback handle retrieval: if either format queries found nothing or to catch unclassified objects
        if (foundPhotos.isEmpty() && foundVideos.isEmpty()) {
            try {
                val allHandles = ptpClient.getObjectHandles(
                    storageId = storageId,
                    formatCode = PtpConstants.FORMAT_ALL,
                    parentHandle = PtpConstants.PARENT_ALL
                )
                val count = allHandles.size.coerceAtMost(1000)
                for (i in 0 until count) {
                    photoItems.add(PtpMediaItem(handle = allHandles[i], isVideo = false))
                }
            } catch (e: Exception) {
                Log.w(PtpConstants.TAG, "Fallback handle retrieval error", e)
            }
        }
    }

    /**
     * Fast, lightweight video discovery.
     * Restored to simple non-blocking operation: queries known video formats across available storages
     * without deep recursive scanning or heavy UI blocking.
     */
    suspend fun scanAllVideos(
        onProgress: ((foundCount: Int, isDone: Boolean) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val client = activeClient ?: return@withContext 0
        isVideoScanInProgress = true
        try {
            val storageIds = try {
                client.getStorageIds()
            } catch (e: Exception) {
                IntArray(0)
            }
            val storagesToQuery = if (storageIds.isNotEmpty()) storageIds else intArrayOf(PtpConstants.STORAGE_ALL)

            val videoFormats = intArrayOf(
                PtpConstants.FORMAT_MP4,
                PtpConstants.FORMAT_MKV,
                PtpConstants.FORMAT_AVI,
                PtpConstants.FORMAT_MOV,
                PtpConstants.FORMAT_3GP,
                PtpConstants.FORMAT_3G2,
                PtpConstants.FORMAT_WMV,
                PtpConstants.FORMAT_MPEG,
                PtpConstants.FORMAT_WEBM
            )

            val existingHandles = videoItems.map { it.handle }.toMutableSet()

            for (sId in storagesToQuery) {
                for (fmt in videoFormats) {
                    if (!coroutineContext.isActive) break
                    try {
                        val handles = client.getObjectHandles(
                            storageId = sId,
                            formatCode = fmt,
                            parentHandle = PtpConstants.PARENT_ALL
                        )
                        for (h in handles) {
                            if (existingHandles.add(h)) {
                                withContext(Dispatchers.Main) {
                                    videoItems.add(PtpMediaItem(handle = h, isVideo = true))
                                }
                                onProgress?.invoke(videoItems.size, false)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            onProgress?.invoke(videoItems.size, true)
            videoItems.size
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error in video scan", e)
            onProgress?.invoke(videoItems.size, true)
            videoItems.size
        } finally {
            isVideoScanInProgress = false
        }
    }

    suspend fun fetchMetadataIfNeeded(item: PtpMediaItem): PtpMediaItem = withContext(Dispatchers.IO) {
        if (item.isMetadataLoaded) return@withContext item
        val client = activeClient ?: return@withContext item

        val info = client.getObjectInfo(item.handle)
        if (info != null) {
            item.filename = info.filename
            item.sizeBytes = info.compressedSize
            item.format = info.format
            item.isMetadataLoaded = true
        }
        item
    }

    suspend fun loadFullPhoto(handle: Int, maxDim: Int = 1920): Bitmap? = withContext(Dispatchers.IO) {
        val client = activeClient ?: return@withContext null
        val tempFile = File(context.cacheDir, "full_photo_temp.jpg")
        if (tempFile.exists()) tempFile.delete()

        try {
            FileOutputStream(tempFile).use { fos ->
                val success = client.streamObject(handle, fos)
                if (!success) return@withContext null
            }

            // Decode dimensions
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOpts)

            val sampleSize = PtpClient.calculateInSampleSize(boundsOpts, maxDim, maxDim)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, decodeOpts)
        } catch (e: OutOfMemoryError) {
            Log.e(PtpConstants.TAG, "OOM loading full photo $handle", e)
            null
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error loading full photo $handle", e)
            null
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {}
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            try {
                activeClient?.closeSession()
            } catch (_: Exception) {}
            activeClient = null
            photoItems.clear()
            videoItems.clear()
            isVideoScanInProgress = false
        }
    }
}
