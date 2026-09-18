package com.example.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

object UsbDeviceInspector {
    private const val TAG = "UsbDeviceInspector"

    fun findCandidates(device: UsbDevice): List<UsbDeviceCandidate> {
        val candidates = mutableListOf<UsbDeviceCandidate>()

        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            val candidate = inspectInterface(device, usbInterface)
            if (candidate != null) {
                candidates.add(candidate)
            }
        }

        // Sort so that standard PTP/MTP class 6 or explicit MTP interfaces come first
        candidates.sortByDescending { candidate ->
            when {
                candidate.deviceType == UsbDeviceType.MTP_PHONE && candidate.usbInterface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE -> 100
                candidate.deviceType == UsbDeviceType.MTP_PHONE && candidate.usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC -> 80
                candidate.deviceType == UsbDeviceType.MTP_PHONE -> 60
                candidate.deviceType == UsbDeviceType.MASS_STORAGE -> 40
                else -> 10
            }
        }

        return candidates
    }

    private fun inspectInterface(device: UsbDevice, iface: UsbInterface): UsbDeviceCandidate? {
        val ifClass = iface.interfaceClass
        val ifSubclass = iface.interfaceSubclass
        val ifProtocol = iface.interfaceProtocol

        // Check endpoints
        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        var interruptIn: UsbEndpoint? = null

        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    bulkIn = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    bulkOut = ep
                }
            } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                interruptIn = ep
            }
        }

        if (bulkIn == null || bulkOut == null) {
            return null
        }

        // Identify type
        val isStillImage = ifClass == UsbConstants.USB_CLASS_STILL_IMAGE && ifSubclass == 1 && ifProtocol == 1
        val isVendorMtp = ifClass == UsbConstants.USB_CLASS_VENDOR_SPEC
        val isMassStorage = ifClass == UsbConstants.USB_CLASS_MASS_STORAGE

        val ifaceName = iface.name ?: ""

        val deviceType = when {
            isStillImage -> UsbDeviceType.MTP_PHONE
            isVendorMtp || ifaceName.contains("MTP", ignoreCase = true) || ifaceName.contains("PTP", ignoreCase = true) -> UsbDeviceType.MTP_PHONE
            isMassStorage -> UsbDeviceType.MASS_STORAGE
            else -> {
                // If it's a composite device with Bulk IN/OUT and endpoints typical of MTP, treat as candidate
                if (iface.endpointCount in 2..3) UsbDeviceType.MTP_PHONE else UsbDeviceType.OTHER
            }
        }

        val desc = "Interface #${iface.id} [Class: 0x${Integer.toHexString(ifClass)}, Subclass: 0x${Integer.toHexString(ifSubclass)}, Proto: 0x${Integer.toHexString(ifProtocol)}] $ifaceName"
        Log.d(TAG, "Device ${device.deviceName} (0x${Integer.toHexString(device.vendorId)}:0x${Integer.toHexString(device.productId)}) -> $desc, Type: $deviceType")

        return UsbDeviceCandidate(
            device = device,
            usbInterface = iface,
            endpointIn = bulkIn,
            endpointOut = bulkOut,
            endpointInterrupt = interruptIn,
            deviceType = deviceType,
            interfaceDescription = desc
        )
    }

    fun getDeviceDisplayName(device: UsbDevice): String {
        val manufacturer = try { device.manufacturerName } catch (e: Exception) { null }
        val product = try { device.productName } catch (e: Exception) { null }

        return when {
            !product.isNullOrBlank() && !manufacturer.isNullOrBlank() -> {
                if (product.startsWith(manufacturer, ignoreCase = true)) product else "$manufacturer $product"
            }
            !product.isNullOrBlank() -> product
            !manufacturer.isNullOrBlank() -> "$manufacturer USB Device"
            else -> "USB Device (0x${Integer.toHexString(device.vendorId)}:0x${Integer.toHexString(device.productId)})"
        }
    }
}
