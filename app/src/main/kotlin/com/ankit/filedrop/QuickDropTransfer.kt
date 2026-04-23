package com.ankit.filedrop

import android.util.Log
import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed class TransferResult {
    object Success : TransferResult()
    data class Error(val message: String) : TransferResult()
    object Declined : TransferResult()
    object Cancelled : TransferResult()
}

object QuickDropTransfer {
    private const val TAG = "QuickDropTransfer"

    // SHARED CLIENT FOR REUSE
    val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(50, TimeUnit.SECONDS) // Long timeout for handshake popups
            .writeTimeout(0, TimeUnit.SECONDS) // No timeout for uploads
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    fun cancelAll() {
        sharedClient.dispatcher.cancelAll()
    }

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

    suspend fun requestTransfer(
        ipAddress: String, 
        fileName: String, 
        fileSize: Long, 
        preview: TransferPreview,
        transferId: String
    ): Boolean {
        val json = JSONObject().apply {
            put("id", transferId)
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("deviceName", getFormattedDeviceName())
            put("fileType", preview.fileType)
            put("previewMode", preview.previewMode)
            put("count", preview.count)
            // ❌ thumbnail is explicitly removed from Phase 1 onwards
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = RequestBody.create(mediaType, json.toString())

        val request = Request.Builder()
            .url("http://$ipAddress:8000/request-transfer")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            sharedClient.newCall(request).execute().use { response ->
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

    private suspend fun waitForNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val initialNetwork = cm.activeNetwork
        if (initialNetwork != null) {
            val capabilities = cm.getNetworkCapabilities(initialNetwork)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                return // Already have internet
            }
        }

        Log.i(TAG, "📡 Waiting for network connectivity...")
        TransferStatus.setWaitingForNetwork(true)
        
        suspendCancellableCoroutine<Unit> { continuation ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "✅ Network restored.")
                    try { cm.unregisterNetworkCallback(this) } catch (e: Exception) {}
                    TransferStatus.setWaitingForNetwork(false)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            try {
                cm.registerNetworkCallback(request, callback)
            } catch (e: Exception) {
                TransferStatus.setWaitingForNetwork(false)
                if (continuation.isActive) continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) {}
            }
        }
    }

    suspend fun uploadFiles(
        context: Context,
        fileUris: List<Uri>,
        ipAddress: String,
        onProgress: (progress: Float, speed: String, eta: String) -> Unit
    ): TransferResult {

        // 1. Calculate total size for global progress tracking
        var totalBytes = 0L
        for (uri in fileUris) {
            totalBytes += FileHelper.getFileSize(context, uri)
        }

        Log.d(TAG, "📦 Preparing batch upload: ${fileUris.size} files, total size: ${FileHelper.formatBytes(totalBytes)}")

        var retryCount = 0
        val maxRetries = 3
        val backoffDelays = listOf(2000L, 5000L, 10000L)
        var lastError: String? = null

        while (retryCount < maxRetries) {
            // Check for network before even starting/retrying
            waitForNetwork(context)
            
            try {
                // 2. Build Multipart Request (fresh builder for each attempt)
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                var currentOffset = 0L

                for (uri in fileUris) {
                    val fileName = FileHelper.getFileName(context, uri)
                    val fileSize = FileHelper.getFileSize(context, uri)
                    val inputStream = context.contentResolver.openInputStream(uri) ?: continue
                    val mediaType = context.contentResolver.getType(uri)?.toMediaTypeOrNull()

                    val progressRequestBody = ProgressRequestBody(
                        inputStream,
                        mediaType,
                        fileSize,
                        globalTotalBytes = totalBytes,
                        globalOffset = currentOffset,
                        onProgress = onProgress
                    )

                    builder.addFormDataPart("files", fileName, progressRequestBody)
                    currentOffset += fileSize
                }

                val requestBody = builder.build()
                val request = Request.Builder()
                    .url("http://$ipAddress:8000/upload")
                    .addHeader("X-Device-Name", getFormattedDeviceName())
                    .addHeader("X-File-Size", totalBytes.toString())
                    .post(requestBody)
                    .build()

                Log.d(TAG, "🚀 Starting batch upload attempt ${retryCount + 1} to $ipAddress")
                
                return sharedClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val body = response.body?.string()
                            Log.d(TAG, "✅ Batch upload successful: $body")
                            TransferResult.Success
                        }
                        403 -> {
                            Log.e(TAG, "❌ Transfer declined by Mac.")
                            TransferResult.Declined
                        }
                        else -> {
                            val msg = "Upload failed: ${response.code}"
                            Log.e(TAG, "❌ $msg - ${response.message}")
                            TransferResult.Error(msg)
                        }
                    }
                }
            } catch (e: Exception) {
                // 🔥 Check for Cancellation/Interruption
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("Canceled", ignoreCase = true) || errorMsg.contains("Socket closed", ignoreCase = true)) {
                    Log.i(TAG, "🛑 Transfer cancelled by user.")
                    return TransferResult.Cancelled
                }
                
                if (errorMsg.contains("Connection reset", ignoreCase = true) || errorMsg.contains("Broken pipe", ignoreCase = true)) {
                    Log.w(TAG, "⚠️ Connection lost (interrupted by Mac or Network).")
                    return TransferResult.Error("Transfer interrupted")
                }

                retryCount++
                lastError = e.message
                Log.w(TAG, "⚠️ Upload attempt $retryCount failed: ${e.message}")
                
                if (retryCount < maxRetries) {
                    val delayMs = backoffDelays.getOrElse(retryCount - 1) { 10000L }
                    Log.i(TAG, "🔄 Retrying in ${delayMs/1000} seconds...")
                    delay(delayMs)
                } else {
                    Log.e(TAG, "❌ Max retries reached. Upload abandoned.")
                    return TransferResult.Error(lastError ?: "Max retries reached")
                }
            }
        }
        return TransferResult.Error(lastError ?: "Unknown error")
    }
}
