package com.example.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.example.R
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MediaContextMenuHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ptpClientProvider: () -> PtpClient?,
    private val onCopyStart: (String) -> Unit,
    private val onCopyProgress: (Long, Long) -> Unit,
    private val onCopyComplete: (Boolean, String) -> Unit
) {
    private var activeCopyJob: Job? = null

    fun showContextMenu(item: PtpMediaItem) {
        val options = arrayOf(
            context.getString(R.string.play_with),
            context.getString(R.string.copy_to_tv)
        )

        AlertDialog.Builder(context)
            .setTitle(item.displayName)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> handlePlayWith(item)
                    1 -> handleCopyToTv(item)
                }
            }
            .setNegativeButton("CANCEL") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun cancelCopy() {
        activeCopyJob?.cancel()
        activeCopyJob = null
        onCopyComplete(false, "Copy cancelled")
    }

    private fun handlePlayWith(item: PtpMediaItem) {
        val client = ptpClientProvider()
        if (client == null || !client.isSessionOpen) {
            Toast.makeText(context, "PTP device not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Cache file or use existing cache
        val ext = item.filename.substringAfterLast('.', if (item.isVideo) "mp4" else if (item.isAudio) "mp3" else "jpg")
        val mimeType = if (item.isVideo) {
            "video/*"
        } else if (item.isAudio) {
            "audio/*"
        } else {
            "image/*"
        }

        val cacheFile = File(context.cacheDir, "open_with_temp.$ext")

        scope.launch {
            Toast.makeText(context, "Preparing file for external player...", Toast.LENGTH_SHORT).show()
            val success = withContext(Dispatchers.IO) {
                try {
                    FileOutputStream(cacheFile).use { fos ->
                        client.streamObject(item.handle, fos)
                    }
                } catch (e: Exception) {
                    Log.e(PtpConstants.TAG, "Failed streaming for Play With", e)
                    false
                }
            }

            if (success && cacheFile.exists() && cacheFile.length() > 0) {
                try {
                    val uri: Uri = try {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cacheFile
                        )
                    } catch (_: Exception) {
                        Uri.fromFile(cacheFile)
                    }

                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    val chooser = Intent.createChooser(viewIntent, context.getString(R.string.play_with))
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Log.e(PtpConstants.TAG, "Could not launch external player", e)
                    Toast.makeText(context, context.getString(R.string.no_apps_found), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Could not prepare media for external playback", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleCopyToTv(item: PtpMediaItem) {
        val client = ptpClientProvider()
        if (client == null || !client.isSessionOpen) {
            Toast.makeText(context, "PTP device not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Destination: Downloads/DirectUSB or app internal media storage
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "DirectUSB")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val safeName = item.displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val destinationFile = File(targetDir, safeName)

        onCopyStart(safeName)

        activeCopyJob = scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // Check available disk space
                    val freeBytes = targetDir.freeSpace
                    if (item.sizeBytes > 0 && freeBytes < item.sizeBytes + (50 * 1024 * 1024)) {
                        Log.e(PtpConstants.TAG, "Insufficient storage: free=$freeBytes needed=${item.sizeBytes}")
                        return@withContext false
                    }

                    FileOutputStream(destinationFile).use { fos ->
                        client.streamObject(item.handle, fos)
                    }
                } catch (e: Exception) {
                    Log.e(PtpConstants.TAG, "File copy to TV failed", e)
                    false
                }
            }

            if (success && destinationFile.exists() && destinationFile.length() > 0) {
                onCopyComplete(true, destinationFile.absolutePath)
            } else {
                if (destinationFile.exists()) destinationFile.delete()
                onCopyComplete(false, "Copy failed or interrupted")
            }
        }
    }
}
