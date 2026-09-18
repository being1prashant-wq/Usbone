package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.usb.PtpConstants
import com.example.usb.PtpPacket
import org.junit.Assert.assertEquals
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
  fun `verify PTP command packet structure`() {
    val packet = PtpPacket.buildCommand(
      operationCode = PtpConstants.OPERATION_OPEN_SESSION,
      transactionId = 1,
      1
    )

    assertEquals(16, packet.size)
    val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(16, bb.getInt())
    assertEquals(PtpConstants.CONTAINER_TYPE_COMMAND, bb.getShort())
    assertEquals(PtpConstants.OPERATION_OPEN_SESSION.toShort(), bb.getShort())
    assertEquals(1, bb.getInt())
    assertEquals(1, bb.getInt())
  }
}
