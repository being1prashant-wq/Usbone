package com.example.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class PtpHeader(
    val length: Int,
    val type: Short,
    val code: Int,
    val transactionId: Int
)

data class PtpResponse(
    val responseCode: Int,
    val transactionId: Int,
    val params: IntArray
) {
    val isOk: Boolean get() = responseCode == PtpConstants.RESPONSE_OK

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PtpResponse
        if (responseCode != other.responseCode) return false
        if (transactionId != other.transactionId) return false
        return params.contentEquals(other.params)
    }

    override fun hashCode(): Int {
        var result = responseCode
        result = 31 * result + transactionId
        result = 31 * result + params.contentHashCode()
        return result
    }
}

object PtpPacket {

    fun buildCommand(operationCode: Int, transactionId: Int, vararg params: Int): ByteArray {
        val totalLength = PtpConstants.HEADER_SIZE + (params.size * 4)
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalLength)
        buffer.putShort(PtpConstants.CONTAINER_TYPE_COMMAND)
        buffer.putShort(operationCode.toShort())
        buffer.putInt(transactionId)
        for (param in params) {
            buffer.putInt(param)
        }
        return buffer.array()
    }

    fun parseHeader(buffer: ByteBuffer): PtpHeader {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.getInt()
        val type = buffer.getShort()
        val code = buffer.getShort().toInt() and 0xFFFF
        val transactionId = buffer.getInt()
        return PtpHeader(length, type, code, transactionId)
    }

    fun parseResponse(buffer: ByteBuffer): PtpResponse {
        val header = parseHeader(buffer)
        val remainingBytes = buffer.remaining()
        val paramCount = remainingBytes / 4
        val params = IntArray(paramCount)
        for (i in 0 until paramCount) {
            params[i] = buffer.getInt()
        }
        return PtpResponse(header.code, header.transactionId, params)
    }

    fun parseString(buffer: ByteBuffer): String {
        if (!buffer.hasRemaining()) return ""
        val charCount = buffer.get().toInt() and 0xFF
        if (charCount == 0) return ""

        val byteCount = (charCount - 1) * 2
        if (buffer.remaining() < byteCount) return ""

        val stringBytes = ByteArray(byteCount)
        buffer.get(stringBytes)
        // Skip null terminator (2 bytes in UTF-16LE)
        if (buffer.remaining() >= 2) {
            buffer.getShort()
        }
        return String(stringBytes, StandardCharsets.UTF_16LE)
    }

    fun parseUInt32Array(buffer: ByteBuffer): IntArray {
        if (buffer.remaining() < 4) return IntArray(0)
        val count = buffer.getInt()
        if (count <= 0 || buffer.remaining() < count * 4) return IntArray(0)
        val result = IntArray(count)
        for (i in 0 until count) {
            result[i] = buffer.getInt()
        }
        return result
    }
}
