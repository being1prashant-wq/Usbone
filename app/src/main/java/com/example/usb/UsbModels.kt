package com.example.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

enum class UsbDeviceType {
    MTP_PHONE,
    MASS_STORAGE,
    OTHER
}

data class UsbDeviceCandidate(
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val endpointIn: UsbEndpoint,
    val endpointOut: UsbEndpoint,
    val endpointInterrupt: UsbEndpoint?,
    val deviceType: UsbDeviceType,
    val interfaceDescription: String
)

data class UsbDeviceDiagnostics(
    val isHostSupported: Boolean,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String,
    val productName: String,
    val serialNumber: String,
    val interfaceCount: Int,
    val selectedInterfaceIndex: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val bulkInAddress: Int,
    val bulkInMaxPacket: Int,
    val bulkOutAddress: Int,
    val bulkOutMaxPacket: Int,
    val hasInterruptIn: Boolean,
    val permissionGranted: Boolean,
    val isSessionOpen: Boolean,
    val readSpeedMbPerSec: Float,
    val bufferHealth: String
)
