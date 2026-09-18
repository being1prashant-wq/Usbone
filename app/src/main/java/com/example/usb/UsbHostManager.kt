package com.example.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.example.mtp.MtpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class UsbConnectionState {
    object Disconnected : UsbConnectionState()
    data class DeviceDetected(val device: UsbDevice, val displayName: String, val isPhone: Boolean) : UsbConnectionState()
    data class PermissionRequired(val device: UsbDevice, val displayName: String) : UsbConnectionState()
    data class OpeningDevice(val displayName: String) : UsbConnectionState()
    data class IdentifyingProtocol(val displayName: String) : UsbConnectionState()
    data class MtpInitializing(val displayName: String) : UsbConnectionState()
    data class Ready(val device: UsbDevice, val displayName: String, val client: MtpClient) : UsbConnectionState()
    data class Error(val message: String, val canRetry: Boolean = true) : UsbConnectionState()
}

class UsbHostManager(private val context: Context) {
    private val tag = "UsbHostManager"
    private val actionUsbPermission = "com.example.directusb.USB_PERMISSION"

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _diagnostics = MutableStateFlow<UsbDeviceDiagnostics?>(null)
    val diagnostics: StateFlow<UsbDeviceDiagnostics?> = _diagnostics.asStateFlow()

    private var currentConnection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    var activeMtpClient: MtpClient? = null
        private set

    private var selectedDevice: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.d(tag, "USB Device Attached: ${device?.deviceName}")
                    scanDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.d(tag, "USB Device Detached: ${device?.deviceName}")
                    if (device == null || device == selectedDevice) {
                        disconnectCurrentDevice("Phone disconnected")
                    }
                }
                actionUsbPermission -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.d(tag, "Permission result for ${device?.deviceName}: granted=$granted")
                        if (device != null) {
                            if (granted) {
                                openAndInitializeDevice(device)
                            } else {
                                _connectionState.value = UsbConnectionState.Error("USB permission was denied.", canRetry = true)
                            }
                        }
                    }
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(actionUsbPermission)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        scanDevices()
    }

    fun stop() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering USB receiver", e)
        }
        disconnectCurrentDevice("Application closing")
    }

    fun scanDevices() {
        val manager = usbManager ?: run {
            _connectionState.value = UsbConnectionState.Error("USB Host API unavailable on this device.")
            return
        }

        val deviceList = manager.deviceList
        if (deviceList.isEmpty()) {
            if (_connectionState.value !is UsbConnectionState.Disconnected) {
                disconnectCurrentDevice("No USB device connected")
            }
            return
        }

        Log.d(tag, "Found ${deviceList.size} USB devices")

        // Find candidate phones
        var bestCandidate: Pair<UsbDevice, UsbDeviceCandidate>? = null

        for (device in deviceList.values) {
            val candidates = UsbDeviceInspector.findCandidates(device)
            val phoneCandidate = candidates.firstOrNull { it.deviceType == UsbDeviceType.MTP_PHONE }
            if (phoneCandidate != null) {
                bestCandidate = Pair(device, phoneCandidate)
                break
            } else if (candidates.isNotEmpty() && bestCandidate == null) {
                bestCandidate = Pair(device, candidates.first())
            }
        }

        if (bestCandidate != null) {
            val (device, candidate) = bestCandidate
            val displayName = UsbDeviceInspector.getDeviceDisplayName(device)
            selectedDevice = device

            if (manager.hasPermission(device)) {
                openAndInitializeDevice(device)
            } else {
                _connectionState.value = UsbConnectionState.PermissionRequired(device, displayName)
            }
        } else {
            val first = deviceList.values.first()
            _connectionState.value = UsbConnectionState.DeviceDetected(
                device = first,
                displayName = UsbDeviceInspector.getDeviceDisplayName(first),
                isPhone = false
            )
        }
    }

    fun requestPermissionForCurrentDevice() {
        val device = selectedDevice ?: return
        val manager = usbManager ?: return

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(actionUsbPermission),
            flags
        )

        Log.d(tag, "Requesting USB permission for ${device.deviceName}")
        manager.requestPermission(device, permissionIntent)
    }

    fun connectManually() {
        val device = selectedDevice ?: run {
            scanDevices()
            return
        }
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) {
            openAndInitializeDevice(device)
        } else {
            requestPermissionForCurrentDevice()
        }
    }

    private fun openAndInitializeDevice(device: UsbDevice) {
        val manager = usbManager ?: return
        val displayName = UsbDeviceInspector.getDeviceDisplayName(device)
        _connectionState.value = UsbConnectionState.OpeningDevice(displayName)

        val candidates = UsbDeviceInspector.findCandidates(device)
        if (candidates.isEmpty()) {
            _connectionState.value = UsbConnectionState.Error("No compatible MTP/PTP USB interface found on $displayName.\nOn your phone, open the USB notification and select File Transfer.")
            return
        }

        // Try candidates in order
        var success = false
        for (candidate in candidates) {
            _connectionState.value = UsbConnectionState.IdentifyingProtocol("Interface #${candidate.usbInterface.id}")
            val connection = manager.openDevice(device)
            if (connection == null) {
                Log.e(tag, "Failed to open UsbDevice ${device.deviceName}")
                continue
            }

            if (!connection.claimInterface(candidate.usbInterface, true)) {
                Log.e(tag, "Failed to claim interface #${candidate.usbInterface.id}")
                connection.close()
                continue
            }

            _connectionState.value = UsbConnectionState.MtpInitializing(displayName)

            val client = MtpClient(
                connection = connection,
                endpointIn = candidate.endpointIn,
                endpointOut = candidate.endpointOut
            )

            // Attempt to open MTP session
            if (client.openSession()) {
                currentConnection = connection
                claimedInterface = candidate.usbInterface
                activeMtpClient = client
                success = true

                // Update diagnostics
                _diagnostics.value = UsbDeviceDiagnostics(
                    isHostSupported = true,
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    manufacturer = try { device.manufacturerName ?: "Unknown" } catch (e: Exception) { "Unknown" },
                    productName = try { device.productName ?: "Unknown" } catch (e: Exception) { "Unknown" },
                    serialNumber = try { device.serialNumber ?: "Protected" } catch (e: Exception) { "N/A" },
                    interfaceCount = device.interfaceCount,
                    selectedInterfaceIndex = candidate.usbInterface.id,
                    interfaceClass = candidate.usbInterface.interfaceClass,
                    interfaceSubclass = candidate.usbInterface.interfaceSubclass,
                    interfaceProtocol = candidate.usbInterface.interfaceProtocol,
                    bulkInAddress = candidate.endpointIn.address,
                    bulkInMaxPacket = candidate.endpointIn.maxPacketSize,
                    bulkOutAddress = candidate.endpointOut.address,
                    bulkOutMaxPacket = candidate.endpointOut.maxPacketSize,
                    hasInterruptIn = candidate.endpointInterrupt != null,
                    permissionGranted = true,
                    isSessionOpen = true,
                    readSpeedMbPerSec = 0f,
                    bufferHealth = "Normal"
                )

                _connectionState.value = UsbConnectionState.Ready(
                    device = device,
                    displayName = displayName,
                    client = client
                )
                break
            } else {
                Log.w(tag, "MTP handshake failed on interface #${candidate.usbInterface.id}")
                connection.releaseInterface(candidate.usbInterface)
                connection.close()
            }
        }

        if (!success) {
            _connectionState.value = UsbConnectionState.Error(
                "Phone detected, but MTP storage interface is unavailable.\n" +
                        "On your phone, open the USB notification and select File Transfer.",
                canRetry = true
            )
        }
    }

    fun updateSpeedMeasurement(mbPerSec: Float) {
        _diagnostics.value = _diagnostics.value?.copy(readSpeedMbPerSec = mbPerSec)
    }

    fun disconnectCurrentDevice(reason: String) {
        Log.d(tag, "Disconnecting current USB device: $reason")
        try {
            activeMtpClient?.closeSession()
        } catch (e: Exception) {
            Log.e(tag, "Error closing MTP session", e)
        }
        activeMtpClient = null

        try {
            claimedInterface?.let { currentConnection?.releaseInterface(it) }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing USB interface", e)
        }
        claimedInterface = null

        try {
            currentConnection?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing USB connection", e)
        }
        currentConnection = null

        _diagnostics.value = null
        _connectionState.value = UsbConnectionState.Disconnected
    }
}
