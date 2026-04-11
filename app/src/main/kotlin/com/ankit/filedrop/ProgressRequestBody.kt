package com.ankit.filedrop

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream

class ProgressRequestBody(
    private val inputStream: InputStream,
    private val contentType: MediaType?,
    private val totalByteLength: Long,
    private val onProgress: (progress: Float, speed: String, eta: String) -> Unit
) : RequestBody() {

    private var lastProgressEmission = 0f

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = totalByteLength

    override fun writeTo(sink: BufferedSink) {
        val startTime = System.currentTimeMillis()
        inputStream.use { input ->
            val buffer = ByteArray(4096)
            var bytesWritten: Long = 0
            var read: Int
            
            while (input.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
                bytesWritten += read
                
                val currentTime = System.currentTimeMillis()
                val elapsedTimeS = (currentTime - startTime) / 1000.0
                
                val progress = if (totalByteLength > 0) bytesWritten.toFloat() / totalByteLength else 0f
                
                // Throttle updates: only emit if progress increased by at least 1% (0.01)
                // or if it's the final chunk (1.0)
                if (progress - lastProgressEmission >= 0.01f || progress >= 1f) {
                    val speedBps = if (elapsedTimeS > 0) bytesWritten / elapsedTimeS else 0.0
                    val speedFormatted = FileHelper.formatBytes(speedBps.toLong()) + "/s"
                    
                    val remainingBytes = totalByteLength - bytesWritten
                    val etaS = if (speedBps > 0) remainingBytes / speedBps else 0.0
                    val etaFormatted = formatEta(etaS.toLong())
                    
                    onProgress(progress, speedFormatted, etaFormatted)
                    lastProgressEmission = progress
                }
            }
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return "0s left"
        if (seconds < 60) return "${seconds}s left"
        val mins = seconds / 60
        val secs = seconds % 60
        return if (secs > 0) "${mins}m ${secs}s left" else "${mins}m left"
    }
}
