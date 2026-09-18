package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.mtp.MtpConstants
import com.example.mtp.MtpDataReader
import com.example.mtp.MtpPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("DirectUSB", appName)
  }

  @Test
  fun `verify MTP command packet structure`() {
    val packet = MtpPacket.buildCommandPacket(
      operationCode = MtpConstants.OPERATION_OPEN_SESSION,
      transactionId = 1,
      params = intArrayOf(1)
    )

    assertEquals(16, packet.size)
    val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(16, bb.getInt())
    assertEquals(MtpConstants.CONTAINER_TYPE_COMMAND, bb.getShort())
    assertEquals(MtpConstants.OPERATION_OPEN_SESSION.toShort(), bb.getShort())
    assertEquals(1, bb.getInt())
    assertEquals(1, bb.getInt())
  }
}

