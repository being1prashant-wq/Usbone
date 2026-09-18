package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.DirectUsbViewModel
import com.example.ui.components.TvFocusableButton
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun UsbDiagnosticsScreen(
    viewModel: DirectUsbViewModel,
    modifier: Modifier = Modifier
) {
    val diagnostics by viewModel.diagnostics.collectAsState()
    val isTestingSpeed by viewModel.isTestingSpeed.collectAsState()
    val speedTestResult by viewModel.speedTestMbPerSec.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .testTag("usb_diagnostics_screen")
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvFocusableButton(
                text = "Back",
                onClick = { viewModel.handleBack() },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp).padding(end = 6.dp)
                    )
                },
                testTag = "btn_diag_back"
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Build, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "USB HARDWARE & BUS MONITOR",
                    color = CyanAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (diagnostics == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No Active USB Connection",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Connect your Android phone to the TV's USB-A host port to monitor bus endpoints and speeds.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            val d = diagnostics!!
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section: Live Speed Test Card
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceElevated, RoundedCornerShape(14.dp))
                            .border(1.dp, CyanAccent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "USB BUS REAL-TIME THROUGHPUT",
                                    color = CyanAccent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val speedDisplay = when {
                                    isTestingSpeed -> "Measuring raw transfer rate..."
                                    speedTestResult != null && speedTestResult!! > 0f -> String.format(Locale.US, "%.2f MB/s (High-Speed USB 2.0)", speedTestResult)
                                    d.readSpeedMbPerSec > 0f -> String.format(Locale.US, "%.2f MB/s (Active Streaming)", d.readSpeedMbPerSec)
                                    else -> "Idle (Press Run Speed Test below)"
                                }
                                Text(
                                    text = speedDisplay,
                                    color = if (speedTestResult != null && speedTestResult!! > 0f) SuccessGreen else TextPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (isTestingSpeed) {
                                CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(32.dp))
                            } else {
                                TvFocusableButton(
                                    text = "Run Speed Test",
                                    onClick = { viewModel.triggerSpeedTest() },
                                    isPrimary = true,
                                    testTag = "btn_run_speed_test"
                                )
                            }
                        }
                    }
                }

                // Section: Hardware & Device Identifiers
                item {
                    DiagnosticsGroupCard(title = "DEVICE & USB HOST CONTROLLER") {
                        DiagRow("Product Name:", d.productName)
                        DiagRow("Manufacturer:", d.manufacturer)
                        DiagRow("Device Node:", d.deviceName)
                        DiagRow("Vendor ID (VID):", "0x${Integer.toHexString(d.vendorId).uppercase().padStart(4, '0')}")
                        DiagRow("Product ID (PID):", "0x${Integer.toHexString(d.productId).uppercase().padStart(4, '0')}")
                        DiagRow("Serial Number:", d.serialNumber)
                        DiagRow("Host API Supported:", "Yes (android.hardware.usb.host)")
                        DiagRow("Permission Granted:", if (d.permissionGranted) "Granted" else "Denied")
                    }
                }

                // Section: Protocol & Endpoints
                item {
                    DiagnosticsGroupCard(title = "MTP INTERFACE & USB ENDPOINTS") {
                        DiagRow("Total Interfaces:", "${d.interfaceCount} available")
                        DiagRow("Selected Interface:", "Interface #${d.selectedInterfaceIndex}")
                        DiagRow("Interface Class:", "0x${Integer.toHexString(d.interfaceClass).uppercase()} (Subclass: 0x${Integer.toHexString(d.interfaceSubclass).uppercase()}, Proto: 0x${Integer.toHexString(d.interfaceProtocol).uppercase()})")
                        DiagRow("Bulk IN Endpoint:", "Address 0x${Integer.toHexString(d.bulkInAddress).uppercase()} (Max Packet: ${d.bulkInMaxPacket} bytes)")
                        DiagRow("Bulk OUT Endpoint:", "Address 0x${Integer.toHexString(d.bulkOutAddress).uppercase()} (Max Packet: ${d.bulkOutMaxPacket} bytes)")
                        DiagRow("Interrupt IN Endpoint:", if (d.hasInterruptIn) "Active" else "None")
                        DiagRow("MTP Session:", if (d.isSessionOpen) "Active (Session ID: 1)" else "Closed")
                        DiagRow("Streaming Buffer:", d.bufferHealth)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsGroupCard(
    title: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(14.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                color = CyanAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(180.dp))
        Text(text = value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
