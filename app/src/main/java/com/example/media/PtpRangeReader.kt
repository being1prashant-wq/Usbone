package com.example.media

import com.example.usb.PtpClient
import com.example.usb.PtpConstants

class PtpRangeReader(private val client: PtpClient) {
    suspend fun readAt(handle: Int, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val safeLength = length.coerceAtMost(MAX_CHUNK)
        val low = (offset and 0xFFFFFFFFL).toInt()
        val high = ((offset ushr 32) and 0xFFFFFFFFL).toInt()

        val (resp64, payload64) = client.executeDataCommand(
            PtpConstants.OPERATION_GET_PARTIAL_OBJECT_64,
            handle, low, high, safeLength
        )
        if (resp64.isOk && payload64 != null) return payload64

        if (offset <= Int.MAX_VALUE.toLong()) {
            return client.getPartialObject(handle, offset.toInt(), safeLength)
        }
        return null
    }

    companion object { const val MAX_CHUNK = 1024 * 1024 }
}
