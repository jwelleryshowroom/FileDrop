package com.ankit.filedrop

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

object FileHelper {
    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex != -1) {
                        result = cursor.getString(columnIndex)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown_file"
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long = 0L
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (columnIndex != -1) {
                        size = cursor.getLong(columnIndex)
                    }
                }
            }
        }
        if (size == 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (e: Exception) {
                // Fallback failed
            }
        }
        return size
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
