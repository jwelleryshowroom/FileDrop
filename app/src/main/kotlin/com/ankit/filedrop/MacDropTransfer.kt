package com.ankit.filedrop

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class TransferResult {
    SUCCESS, DECLINED, ERROR
}

object MacDropTransfer {
    private const val TAG = "MacDropTransfer"

    private fun getFormattedDeviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        
        val rawName = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
        
        // Capitalize each word properly and clean up extra spaces
        return rawName.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }.trim()
    }

    suspend fun requestTransfer(ipAddress: String, fileName: String, fileSize: Long): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(50, TimeUnit.SECONDS) // Slightly longer than server popup timeout
            .build()

        val json = JSONObject().apply {
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("deviceName", getFormattedDeviceName())
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = RequestBody.create(mediaType, json.toString())

        val request = Request.Builder()
            .url("http://$ipAddress:8000/request-transfer")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val result = responseBody?.let { JSONObject(it) }
                    result?.optBoolean("accepted", false) ?: false
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error requesting transfer: ${e.message}")
            false
        }
    }

    suspend fun uploadFile(
        context: Context,
        fileUri: Uri,
        ipAddress: String,
        onProgress: (progress: Float, speed: String, eta: String) -> Unit
    ): TransferResult {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val fileName = FileHelper.getFileName(context, fileUri)
        val totalBytes = FileHelper.getFileSize(context, fileUri)
        val inputStream = context.contentResolver.openInputStream(fileUri) ?: return TransferResult.ERROR

        val mediaType = context.contentResolver.getType(fileUri)?.toMediaTypeOrNull()
        val progressRequestBody = ProgressRequestBody(inputStream, mediaType, totalBytes, onProgress)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, progressRequestBody)
            .build()

        val request = Request.Builder()
            .url("http://$ipAddress:8000/upload")
            .addHeader("X-Device-Name", getFormattedDeviceName())
            .post(requestBody)
            .build()

        return try {
            // Proceed with the actual file transfer
            Log.d(TAG, "🚀 Starting upload: $fileName to $ipAddress")
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        Log.d(TAG, "✅ Upload successful: ${response.body?.string()}")
                        TransferResult.SUCCESS
                    }
                    403 -> {
                        Log.e(TAG, "❌ Transfer declined by Mac.")
                        TransferResult.DECLINED
                    }
                    else -> {
                        Log.e(TAG, "❌ Upload failed: ${response.code} - ${response.message}")
                        TransferResult.ERROR
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during upload: ${e.message}", e)
            TransferResult.ERROR
        }
    }
}
