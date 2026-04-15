package com.ankit.filedrop

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import android.webkit.MimeTypeMap
import com.ankit.filedrop.TransferStatus
import com.ankit.filedrop.FileHelper

class QuickDropReceiverServer(
    private val context: Context,
    private val onRequest: suspend (fileName: String, fileSize: Long, deviceName: String, id: String?, count: Int, senderIp: String?) -> Boolean,
    private val onUploadComplete: (fileName: String, fileSize: Long) -> Unit,
    private val onUploadFailed: (fileName: String) -> Unit
) : NanoHTTPD(8000) {

    private var currentTotalSize = 0L
    private var lastHandshakeTotalSize = 0L

    init {
        Log.d("ReceiverServer", "🌐 Server running on port 8000")
        
        // Self-Healing Cache Cleanup: Delete any stranded 2GB+ cache files from previous experimental sessions
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles { _, name -> name.startsWith("NanoHTTPD-") || name.startsWith("nano-temp-") }?.forEach { 
                it.delete() 
            }
            Log.d("ReceiverServer", "🧹 Cleared residual temp files from cache.")
        } catch (e: Exception) {
            Log.e("ReceiverServer", "Error clearing residual cache: ${e.message}")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/request-transfer" -> handleRequestTransfer(session)
            "/upload" -> handleUpload(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun handleRequestTransfer(session: IHTTPSession): Response {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val jsonStr = map["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No data")
            val json = JSONObject(jsonStr)
            val fileName = json.getString("fileName")
            val fileSize = json.getLong("fileSize")
            lastHandshakeTotalSize = fileSize
            
            val deviceName = json.optString("deviceName", "Unknown Device")
            val id = json.optString("id", null)
            val count = json.optInt("count", 1)
            val senderIp = session.headers["http-client-ip"] ?: session.remoteIpAddress

            val accepted = runBlocking {
                onRequest(fileName, fileSize, deviceName, id, count, senderIp)
            }

            val responseJson = JSONObject().put("accepted", accepted)
            newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
        } catch (e: Exception) {
            Log.e("QuickDropServer", "Error handling request", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Only POST allowed")
        }

        // Restore the original polling logic as requested, with a surgical file filter for speed
        val totalSize = session.headers["content-length"]?.toLongOrNull() ?: lastHandshakeTotalSize
        val progressJob = if (totalSize > 0) {
            GlobalScope.launch {
                val cacheDir = context.cacheDir
                var startTime = System.currentTimeMillis()
                
                TransferStatus.setUploading(true) 
                
                while (true) {
                    // Optimized: Only list NanoHTTPD temporary files to prevent I/O disk contention speed drop
                    val nanoFiles = cacheDir.listFiles { _, name -> name.startsWith("NanoHTTPD-") }
                    val currentSize = nanoFiles?.maxOfOrNull { it.length() } ?: 0L
                    
                    if (currentSize >= totalSize) break
                    
                    if (currentSize > 0) {
                        val progress = currentSize.toFloat() / totalSize
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                        val speed = if (elapsed > 0) currentSize / elapsed else 0f
                        val eta = if (speed > 100) ((totalSize - currentSize) / speed).toInt() else 0
                        
                        TransferStatus.updateProgress(
                            progress,
                            FileHelper.formatBytes(speed.toLong()) + "/s",
                            if (eta > 0) "${eta}s left" else "Calculating..."
                        )
                    }
                    delay(200)
                }
            }
        } else null

        var fileName = "received_file"
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            progressJob?.cancel() // Stop polling
            TransferStatus.updateProgress(1f, "0 B/s", "Complete")

            var savedCount = 0
            for ((key, tempFilePath) in files) {
                val tempFile = File(tempFilePath)
                if (tempFile.exists() && tempFile.length() > 0) {
                    val originalFileName = session.parameters[key]?.firstOrNull()
                        ?: session.headers["filename"]
                        ?: session.headers["x-filename"]
                        ?: "received_file"
                    val cleanName = sanitizeFileName(originalFileName)

                    saveToDownloads(cleanName, tempFile)
                    onUploadComplete(cleanName, tempFile.length())
                    Log.d("QuickDropServer", "✅ Saved received file: $cleanName")
                    savedCount++
                }
            }

            if (savedCount == 0) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No valid files found in payload")
            }

            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Successfully saved $savedCount files")
        } catch (e: Exception) {
            onUploadFailed(fileName)
            Log.e("QuickDropServer", "Error handling upload", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun saveToDownloads(fileName: String, tempFile: File) {
        // [v1.9.5] Dynamic MIME Type Mapping (Fixes Gallery Corruption)
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName.replace(" ", "%20")) 
            ?: fileName.substringAfterLast('.', "")
        val mimeType = if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) 
                ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }

        Log.d("QuickDropServer", "💾 Saving as MIME: $mimeType for file: $fileName")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QuickDrop")
                put(MediaStore.MediaColumns.IS_PENDING, 1) // Android's atomic .part equivalent
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri("external")
            
            // Note: Native MediaStore.Downloads handles auto-renaming natively (Auto-appends (1), (2))
            val uri = resolver.insert(collection, values) 
                ?: throw IllegalStateException("Unable to create MediaStore entry for $fileName")

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Unable to open MediaStore output stream")

                // Atomic Commit: Complete the transfer and reveal the file to MediaScanner
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                // Self-Healing: Destroy the ghost record if the connection dropped/failed
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "QuickDrop"
            )
            downloadsDir.mkdirs()
            tempFile.inputStream().use { input ->
                FileOutputStream(File(downloadsDir, fileName)).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank {
            "received_file"
        }
    }
}
