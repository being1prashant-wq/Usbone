package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
        activeClient = ptpClient
        photoItems.clear()
        videoItems.clear()

        Log.i(PtpConstants.TAG, "Initializing PTP Session...")
        if (!ptpClient.openSession()) {
            Log.e(PtpConstants.TAG, "Could not start PTP session")
            return@withContext false
        }

        // 1. Get Device Info
        val devInfo = ptpClient.getDeviceInfo()
        reportedDeviceName = if (devInfo != null && (devInfo.manufacturer.isNotBlank() || devInfo.model.isNotBlank())) {
            "${devInfo.manufacturer} ${devInfo.model}".trim()
        } else {
            fallbackName
        }
        Log.i(PtpConstants.TAG, "PTP session opened. Device name: $reportedDeviceName")

        // 2. Discover Media Handles
        discoverMediaHandles(ptpClient)

        Log.i(PtpConstants.TAG, "PTP Ready: ${photoItems.size} photos, ${videoItems.size} videos")
        true
    }

    private suspend fun discoverMediaHandles(ptpClient: PtpClient) {
        val foundPhotos = mutableSetOf<Int>()
        val foundVideos = mutableSetOf<Int>()

        // Try format filtering first (fastest, 0 metadata overhead)
        val imageFormats = intArrayOf(
            PtpConstants.FORMAT_EXIF_JPEG,
            PtpConstants.FORMAT_PNG,
            PtpConstants.FORMAT_HEIF,
            PtpConstants.FORMAT_WEBP,
            PtpConstants.FORMAT_BMP,
            PtpConstants.FORMAT_GIF
        )

        for (fmt in imageFormats) {
            val handles = ptpClient.getObjectHandles(
                storageId = PtpConstants.STORAGE_ALL,
                formatCode = fmt,
                parentHandle = PtpConstants.PARENT_ALL
            )
            if (handles.isNotEmpty()) {
                for (h in handles) foundPhotos.add(h)
            }
        }

        val videoFormats = intArrayOf(
            PtpConstants.FORMAT_MP4,
            PtpConstants.FORMAT_3GP,
            PtpConstants.FORMAT_MKV,
            PtpConstants.FORMAT_MOV,
            PtpConstants.FORMAT_AVI,
            PtpConstants.FORMAT_WEBM
        )

        for (fmt in videoFormats) {
            val handles = ptpClient.getObjectHandles(
                storageId = PtpConstants.STORAGE_ALL,
                formatCode = fmt,
                parentHandle = PtpConstants.PARENT_ALL
            )
            if (handles.isNotEmpty()) {
                for (h in handles) foundVideos.add(h)
            }
        }

        // If format filtering returned items, we are done without needing any full-scan!
        if (foundPhotos.isNotEmpty() || foundVideos.isNotEmpty()) {
            for (h in foundPhotos) {
                photoItems.add(PtpMediaItem(handle = h, isVideo = false))
            }
            for (h in foundVideos) {
                videoItems.add(PtpMediaItem(handle = h, isVideo = true))
            }
            return
        }

        // If format filtering was not supported or returned nothing, query all handles
        Log.i(PtpConstants.TAG, "Format filtering returned no handles, querying all PTP handles")
        val allHandles = ptpClient.getObjectHandles(
            storageId = PtpConstants.STORAGE_ALL,
            formatCode = PtpConstants.FORMAT_ALL,
            parentHandle = PtpConstants.PARENT_ALL
        )

        Log.i(PtpConstants.TAG, "Total PTP handles retrieved: ${allHandles.size}")

        // For low-end TV: If device returned all handles without format filter,
        // we populate photos and videos by querying ObjectInfo lazily.
        // Initially, we add all handles as photos (or check first few to separate).
        for (h in allHandles) {
            photoItems.add(PtpMediaItem(handle = h, isVideo = false))
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
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOpts)
            bitmap
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
