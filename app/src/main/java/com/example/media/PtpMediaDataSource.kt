package com.example.media

import android.media.MediaDataSource
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.math.min

/**
 * Seekable MediaDataSource backed directly by a PTP object.
 *
 * MediaPlayer asks for arbitrary byte ranges through readAt(). Each missing
 * range is fetched from the phone with GetPartialObject / GetPartialObject64.
 * A small LRU cache keeps recent chunks available without downloading the
 * complete multi-GB video to TV storage or RAM.
 */
class PtpMediaDataSource(
    private val client: PtpClient,
    private val handle: Int,
    totalSizeBytes: Long,
    private val chunkSizeBytes: Int = 1024 * 1024,
    private val maxCachedChunks: Int = 6
) : MediaDataSource() {

    private val closed = AtomicBoolean(false)
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Long, ByteArray>(maxCachedChunks + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            return size > maxCachedChunks
        }
    }

    private val totalSize: Long = if (totalSizeBytes > 0L) totalSizeBytes else -1L

    override fun getSize(): Long = totalSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed.get()) return -1
        if (position < 0L || offset < 0 || size < 0 || offset > buffer.size || size > buffer.size - offset) {
            throw IOException("Invalid MediaDataSource readAt arguments")
        }
        if (size == 0) return 0
        if (totalSize >= 0L && position >= totalSize) return 0

        val available = if (totalSize >= 0L) {
            min(size.toLong(), totalSize - position).toInt()
        } else {
            size
        }
        if (available <= 0) return 0

        synchronized(cacheLock) {
            var copied = 0

            while (copied < available) {
                if (closed.get()) return if (copied > 0) copied else -1

                val absolutePosition = position + copied
                val chunkStart = (absolutePosition / chunkSizeBytes.toLong()) * chunkSizeBytes.toLong()
                val inChunkOffset = (absolutePosition - chunkStart).toInt()

                var chunk = cache[chunkStart]
                if (chunk == null) {
                    chunk = fetchChunk(chunkStart)
                    if (chunk == null || chunk.isEmpty()) {
                        Log.e(PtpConstants.TAG, "PTP read failed: handle=" + handle + " start=" + chunkStart)
                        return if (copied > 0) copied else -1
                    }
                    cache[chunkStart] = chunk
                }

                if (inChunkOffset >= chunk.size) {
                    cache.remove(chunkStart)
                    Log.e(
                        PtpConstants.TAG,
                        "PTP short chunk: start=" + chunkStart + " offset=" + inChunkOffset + " size=" + chunk.size
                    )
                    return if (copied > 0) copied else -1
                }

                val copyCount = min(available - copied, chunk.size - inChunkOffset)
                System.arraycopy(chunk, inChunkOffset, buffer, offset + copied, copyCount)
                copied += copyCount
            }

            return copied
        }
    }

    private fun fetchChunk(start: Long): ByteArray? {
        if (closed.get()) return null

        val requested = if (totalSize > 0L) {
            min(chunkSizeBytes.toLong(), totalSize - start).toInt()
        } else {
            chunkSizeBytes
        }
        if (requested <= 0) return ByteArray(0)

        return try {
            runBlocking(Dispatchers.IO) {
                if (closed.get() || !client.isSessionOpen) return@runBlocking null

                var result: ByteArray? = null

                // GetPartialObject is implemented by more PTP devices, so use
                // it first for offsets that fit its 32-bit offset parameter.
                if (start <= 0xFFFFFFFFL) {
                    result = client.getPartialObject(handle, start.toInt(), requested)
                }

                // Fall back to GetPartialObject64 for devices exposing the 64-bit
                // operation, including offsets beyond 4 GiB.
                if ((result == null || result!!.isEmpty()) && start >= 0L) {
                    result = client.getPartialObject64(handle, start, requested)
                }

                result
            }
        } catch (e: Exception) {
            Log.e(
                PtpConstants.TAG,
                "Exception reading PTP range start=" + start + " size=" + requested,
                e
            )
            null
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(cacheLock) {
                cache.clear()
            }
        }
    }
}
