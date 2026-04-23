package com.ankit.filedrop

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log
import kotlinx.coroutines.*

data class TransferRequest(val files: List<String>, val ipAddress: String)

class TransferService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = mutableListOf<TransferRequest>()
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_SEND" -> {
                val files = intent.getStringArrayListExtra("files") ?: return START_NOT_STICKY
                val ipAddress = intent.getStringExtra("ip") ?: return START_NOT_STICKY
                synchronized(queue) {
                    queue.add(TransferRequest(files, ipAddress))
                }
                updateTransferStatus()
                processQueue()
            }
            "ACTION_RECEIVE_START" -> {
                val fileName = intent.getStringExtra("fileName") ?: "Receiving..."
                val totalSize = intent.getStringExtra("fileSize") ?: ""
                updateNotification("Receiving $fileName...")
                TransferStatus.setUploading(true)
            }
            "ACTION_RECEIVE_STOP" -> {
                TransferStatus.setUploading(false)
                stopSelf()
            }
            "ACTION_CANCEL" -> {
                Log.i("TransferService", "🛑 ACTION_CANCEL received")
                QuickDropTransfer.cancelAll()
                TransferStatus.setResult(TransferResult.Cancelled)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        if (isProcessing) return
        
        scope.launch {
            try {
                isProcessing = true
                TransferStatus.setUploading(true)
                
                while (true) {
                    val nextRequest = synchronized(queue) {
                        if (queue.isNotEmpty()) queue.removeAt(0) else null
                    }
                    
                    if (nextRequest == null) break
                    
                    updateTransferStatus()
                    try {
                        uploadFiles(nextRequest.files, nextRequest.ipAddress)
                    } catch (e: Exception) {
                        Log.e("TransferService", "Queue processor error", e)
                    }
                }
            } finally {
                isProcessing = false
                TransferStatus.setUploading(false)
                Log.d("TransferService", "🏁 Queue empty, stopping service")
                stopSelf()
            }
        }
    }

    private fun updateTransferStatus() {
        val currentlyQueued = synchronized(queue) { queue.size }
        TransferStatus.updateQueuedCount(currentlyQueued)
    }

    private suspend fun uploadFiles(paths: List<String>, ipAddress: String) {
        val uris = paths.map { Uri.parse(it) }
        
        // Calculate total size for summary
        var totalBytes = 0L
        for (uri in uris) {
            totalBytes += FileHelper.getFileSize(this, uri)
        }
        val sizeText = FileHelper.formatBytes(totalBytes)

        val result = QuickDropTransfer.uploadFiles(this, uris, ipAddress) { progress, speed, eta ->
            // [NEW] Log progress exactly like Mac for the developer logs
            Log.i("QuickDropTransfer", "PROGRESS:${String.format("%.4f", progress)}")
            Log.i("QuickDropTransfer", "SPEED:$speed")
            Log.i("QuickDropTransfer", "ETA:$eta")
            
            TransferStatus.updateProgress(progress, speed, eta)
            val queuedCount = synchronized(queue) { queue.size }
            val queueText = if (queuedCount > 0) " (+$queuedCount queued)" else ""
            updateNotification("Sending files... ${(progress * 100).toInt()}%$queueText")
        }
        
        TransferStatus.setResult(result)
        
        // 🔥 Generate summary on success or explicit failure
        if (result is TransferResult.Success || result is TransferResult.Cancelled || result is TransferResult.Error) {
            val resultStr = when(result) {
                is TransferResult.Success -> "success"
                is TransferResult.Cancelled -> "cancelled"
                else -> "failed"
            }
            TransferStatus.setSummary(TransferSummary(
                type = "send",
                count = uris.size,
                totalSize = sizeText,
                result = resultStr
            ))
        }

        updateTransferStatus()
    }

    private fun createNotification(): Notification {
        val channelId = "transfer_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "File Transfer",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sending files...")
            .setContentText("Transfer in progress")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = NotificationCompat.Builder(this, "transfer_channel")
            .setContentTitle("Sending files...")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
            
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        TransferStatus.setUploading(false)
        Log.d("TransferService", "👋 Service destroyed")
    }
}
