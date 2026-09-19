package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PtpMediaRepository(private val context: Context) {

    private var activeClient: PtpClient? = null
    private var reportedDeviceName: String = "PTP Device"

    val photoItems = mutableListOf<PtpMediaItem>()
    val videoItems = mutableListOf<PtpMediaItem>()

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

            // 3. Media discovery
            Log.i(PtpConstants.TAG, "Step 4: Media discovery...")
            discoverMedia(primaryStorageId)

            Log.i(PtpConstants.TAG, "PTP Ready: ${photoItems.size} photos, ${videoItems.size} videos")
            true
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "PTP initialization failed with exception", e)
            false
        }
    }

    suspend fun discoverMedia(storageId: Int = PtpConstants.STORAGE_ALL): Int = withContext(Dispatchers.IO) {
        val ptpClient = activeClient ?: return@withContext 0

        photoItems.clear()
        videoItems.clear()

        val foundPhotos = mutableSetOf<Int>()
        val foundVideos = mutableSetOf<Int>()

        // 1. Quick JPEG format query
        try {
            val jpegHandles = ptpClient.getObjectHandles(
                storageId = storageId,
                formatCode = PtpConstants.FORMAT_EXIF_JPEG,
                parentHandle = PtpConstants.PARENT_ALL
            )
            for (h in jpegHandles) foundPhotos.add(h)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Quick JPEG discovery error", e)
        }

        // 2. Quick MP4 format query
        try {
            val mp4Handles = ptpClient.getObjectHandles(
                storageId = storageId,
                formatCode = PtpConstants.FORMAT_MP4,
                parentHandle = PtpConstants.PARENT_ALL
            )
            for (h in mp4Handles) foundVideos.add(h)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Quick MP4 discovery error", e)
        }

        for (h in foundPhotos) {
            photoItems.add(PtpMediaItem(handle = h, isVideo = false))
        }
        for (h in foundVideos) {
            videoItems.add(PtpMediaItem(handle = h, isVideo = true))
        }

        // 3. Fallback handle retrieval if both format queries returned nothing
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

        photoItems.size + videoItems.size
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
        }
    }
}
