package com.ankit.filedrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.Locale

enum class FilePreviewType(val label: String) {
    IMAGE("Image"),
    PDF("PDF"),
    VIDEO("Video"),
    FILE("File")
}

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

    fun getPreviewType(fileName: String, mimeType: String? = null): FilePreviewType {
        val normalizedMime = mimeType?.lowercase(Locale.US).orEmpty()
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)

        return when {
            normalizedMime.startsWith("image/") -> FilePreviewType.IMAGE
            normalizedMime == "application/pdf" -> FilePreviewType.PDF
            normalizedMime.startsWith("video/") -> FilePreviewType.VIDEO
            extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") -> FilePreviewType.IMAGE
            extension == "pdf" -> FilePreviewType.PDF
            extension in setOf("mp4", "mov", "m4v", "mkv", "webm", "avi") -> FilePreviewType.VIDEO
            else -> FilePreviewType.FILE
        }
    }

    fun generateThumbnail(context: Context, uri: Uri): String? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Modern API (Android 10+) - Target 512px for sharper previews
                context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } else {
                // Legacy API Fallback
                val type = getPreviewType(getFileName(context, uri), context.contentResolver.getType(uri))
                when (type) {
                    FilePreviewType.IMAGE -> {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            BitmapFactory.decodeStream(stream, null, options)
                            
                            options.inSampleSize = calculateInSampleSize(options, 512, 512)
                            options.inJustDecodeBounds = false
                            
                            context.contentResolver.openInputStream(uri)?.use { s ->
                                BitmapFactory.decodeStream(s, null, options)
                            }
                        }
                    }
                    FilePreviewType.VIDEO -> {
                        // For video, we try to use MediaStore or a File Descriptor
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                        } else {
                            @Suppress("DEPRECATION")
                            ThumbnailUtils.createVideoThumbnail(uri.path ?: "", MediaStore.Video.Thumbnails.MINI_KIND)
                        }
                    }
                    else -> null
                }
            }

            bitmap?.let { original ->
                val resized = resizeBitmap(original, 512)
                val outputStream = ByteArrayOutputStream()
                // Optimized 35% quality for better size/clarity balance over line-stream
                resized.compress(Bitmap.CompressFormat.JPEG, 35, outputStream)
                val bytes = outputStream.toByteArray()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        var width = bitmap.width
        var height = bitmap.height

        val bitmapRatio = width.toFloat() / height.toFloat()
        if (bitmapRatio > 1) {
            width = maxSize
            height = (width / bitmapRatio).toInt()
        } else {
            height = maxSize
            width = (height * bitmapRatio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
