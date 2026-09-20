package com.example.media

import com.example.usb.PtpClient
import com.example.usb.PtpConstants

class PtpRangeReader(private val client: PtpClient) {
    suspend fun readAt(handle: Int, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val safeLength = length.coerceAtMost(MAX_CHUNK)
        val supports64 = client.deviceInfo?.operationsSupported?.contains(
            PtpConstants.OPERATION_GET_PARTIAL_OBJECT_64
        ) == true

        // Prefer the standard unsigned 32-bit partial-object operation below
        // 4 GiB. This avoids probing an unsupported 64-bit operation before
        // every normal range request.
        if (offset <= 0xFFFFFFFFL) {
            try {
                client.getPartialObject(handle, offset.toInt(), safeLength)?.let { return it }
            } catch (_: Throwable) {}
        }

        if (supports64) {
            val low = (offset and 0xFFFFFFFFL).toInt()
            val high = ((offset ushr 32) and 0xFFFFFFFFL).toInt()
            val (resp64, payload64) = client.executeDataCommand(
                PtpConstants.OPERATION_GET_PARTIAL_OBJECT_64,
                handle, low, high, safeLength
            )
            if (resp64.isOk && payload64 != null) return payload64
        }
        return null
    }

    companion object { const val MAX_CHUNK = 1024 * 1024 }
}
