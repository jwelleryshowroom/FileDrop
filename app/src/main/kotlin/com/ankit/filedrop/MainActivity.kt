package com.ankit.filedrop

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ankit.filedrop.ui.theme.FileDropTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val viewModel: QuickDropViewModel by viewModels()

    private var server: QuickDropReceiverServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🚀 START RECEIVER SERVER
        server = QuickDropReceiverServer(
            context = this,
            onRequest = { fileName, fileSize, deviceName, thumbnail ->
                Log.d("Receiver", "📩 Incoming request: $fileName from $deviceName")
                
                suspendCancellableCoroutine<Boolean> { cont ->
                    val type = viewModel.getFileType(fileName)
                    val thumbUri = viewModel.saveThumbnailToCache(this@MainActivity, thumbnail)
                    
                    viewModel.incomingRequest.value = IncomingRequest(
                        fileName = fileName,
                        fileSize = fileSize,
                        deviceName = deviceName,
                        fileType = type,
                        thumbnailUri = thumbUri,
                        onDecision = { accepted ->
                            if (cont.isActive) {
                                cont.resume(accepted)
                            }
                        }
                    )
                }
            },
            onUploadComplete = { fileName, size ->
                Log.d("Receiver", "✅ Received: $fileName ($size bytes)")
            },
            onUploadFailed = { fileName ->
                Log.e("Receiver", "❌ Failed: $fileName")
            }
        )

        try {
            server?.start()
            Log.d("Receiver", "🚀 Android server started on port 8000")
        } catch (e: Exception) {
            Log.e("Receiver", "Server failed to start", e)
        }

        setContent {
            FileDropTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuickDropScreen(viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        Log.d("Receiver", "🛑 Server stopped")
    }
}
