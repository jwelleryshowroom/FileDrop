package com.ankit.filedrop

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import android.content.Intent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class UiState {
    object Idle : UiState()
    object Scanning : UiState()
    data class DevicesFound(val devices: List<QuickDropDevice>) : UiState()
    data class DeviceSelected(val device: QuickDropDevice) : UiState()
    object NoDevicesFound : UiState()
}

data class IncomingRequest(
    val fileName: String,
    val fileSize: Long,
    val deviceName: String,
    val fileType: String,
    val count: Int = 1,
    val thumbnailUri: android.net.Uri? = null,
    val onDecision: (Boolean) -> Unit
)

data class IncomingTransferResult(
    val fileName: String,
    val fileSize: Long,
    val isSuccess: Boolean
)

data class TransferPreview(
    val fileType: String,        // image | video | file
    val previewMode: String,     // from enum
    val count: Int,
    val thumbnail: String?       // base64 compressed
)

class QuickDropViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState = _uiState.asStateFlow()

    var isAppInForeground = false

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    private val _isWaitingForPermission = MutableStateFlow(false)
    val isWaitingForPermission: StateFlow<Boolean> = _isWaitingForPermission

    private val _isAutoScanning = MutableStateFlow(false)
    val isAutoScanning = _isAutoScanning.asStateFlow()

    private val _searchMessage = MutableStateFlow("")
    val searchMessage = _searchMessage.asStateFlow()

    private val _showOnboardingHint = MutableStateFlow(false)
    val showOnboardingHint = _showOnboardingHint.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress

    private val _uploadSpeed = MutableStateFlow("")
    val uploadSpeed: StateFlow<String> = _uploadSpeed

    private val _uploadEta = MutableStateFlow("")
    val uploadEta: StateFlow<String> = _uploadEta

    private val _selectedFileName = MutableStateFlow("")
    val selectedFileName: StateFlow<String> = _selectedFileName

    private val _selectedFileSize = MutableStateFlow("")
    val selectedFileSize: StateFlow<String> = _selectedFileSize

    val queuedCount = TransferStatus.queuedCount

    val isWaitingForNetwork = TransferStatus.isWaitingForNetwork
    val transferThumbnailUri = TransferStatus.thumbnailUri
    val transferFileType = TransferStatus.fileType
    
    private val _lastSummary = MutableStateFlow<TransferSummary?>(null)
    val lastSummary = _lastSummary.asStateFlow()

    // Public MutableStateFlow to allow external updates from MainActivity and Screen
    val incomingRequest = MutableStateFlow<IncomingRequest?>(null)

    private val _incomingTransferResult = MutableStateFlow<IncomingTransferResult?>(null)
    val incomingTransferResult = _incomingTransferResult.asStateFlow()

    private var receiverServer: QuickDropReceiverServer? = null
    private var serviceAdvertiser: QuickDropServiceAdvertiser? = null
    private var discovery: QuickDropDiscovery? = null
    private var discoveryJob: Job? = null
    
    // --- ASYNC THUMBNAIL STATE (Phase 2) ---
    private val transferMap = ConcurrentHashMap<String, Uri>()
    private var thumbnailServer: ThumbnailServer? = null

    private val _discoveredDevices = MutableStateFlow<List<QuickDropDevice>>(emptyList())
    // Explicitly using the backing field type for the devices Flow
    val devices: StateFlow<List<QuickDropDevice>> = _discoveredDevices

    fun getFileType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) || fileName.endsWith(".jpeg", true) -> "image"
            fileName.endsWith(".mp4", true) || fileName.endsWith(".mkv", true) || fileName.endsWith(".mov", true) -> "video"
            fileName.endsWith(".pdf", true) -> "pdf"
            else -> "file"
        }
    }

    // Obsolete `saveThumbnailToCache` removed in Phase 2 Polish. Thumbnail URLs are dynamic HTTP URIs passed to Coil.

    // --- Handshake Intelligence Helpers (Phase 1) ---

    private fun selectHeroUri(uris: List<Uri>, context: Context): Uri {
        val resolver = context.contentResolver
        return uris.firstOrNull {
            val type = resolver.getType(it) ?: ""
            type.startsWith("image/") || type.startsWith("video/")
        } ?: uris.first()
    }

    private fun resolveFileType(mime: String): String {
        return when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime == "application/pdf" -> "pdf"
            else -> "other" // Standardized consistency
        }
    }

    private fun generateCompressedThumbnail(context: Context, uri: Uri): String? {
        // Reuse existing generator then add extra compression for network relay
        var base64 = FileHelper.generateThumbnail(context, uri) ?: return null
        
        try {
            // Safety Check: Cap Base64 size to ensure handshake speed (Step 4.1)
            // Hard cap at 100KB for maximum stability as per plan
            if (base64.length > 100_000) {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return base64
                
                val stream = java.io.ByteArrayOutputStream()
                // Drop quality to 35% to fit within budget if oversized
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 35, stream)
                base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e("QuickDropVM", "❌ Thumbnail hard-compression failed", e)
        }
        return base64
    }

    init {
        android.util.Log.d("QuickDropVM", "🚀 INIT: Forcing advertiser start")
        viewModelScope.launch {
            launch { TransferStatus.isUploading.collect { _isUploading.value = it } }
            launch { TransferStatus.progress.collect { _uploadProgress.value = it } }
            launch { TransferStatus.speed.collect { _uploadSpeed.value = it } }
            launch { TransferStatus.eta.collect { _uploadEta.value = it } }
            launch { TransferStatus.lastSummary.collect { _lastSummary.value = it } }
        }
    }

    fun stopTransfer(context: Context) {
        Log.d("QuickDropVM", "🛑 Stopping transfer service via UI request")
        val intent = Intent(context, TransferService::class.java).apply {
            action = "ACTION_CANCEL"
        }
        context.startService(intent)
        
        // 🔥 Also stop the receiver server to abort any incoming transfer
        Log.d("QuickDropVM", "🛑 Aborting receiver server (if any)")
        receiverServer?.stop()
        receiverServer = null
        startReceiver(context) // Restart it for future transfers
        
        // If we were receiving, mark it as cancelled
        if (TransferStatus.isUploading.value && TransferStatus.lastSummary.value == null) {
            TransferStatus.setSummary(TransferSummary(
                type = "receive",
                count = 1,
                totalSize = "0 B",
                result = "cancelled"
            ))
            TransferStatus.setUploading(false)
        }
    }

    fun checkFirstLaunch(context: Context) {
        val prefs = context.getSharedPreferences("quickdrop_prefs", Context.MODE_PRIVATE)
        val isFirst = prefs.getBoolean("is_first_launch", true)
        if (isFirst) {
            _showOnboardingHint.value = true
            prefs.edit().putBoolean("is_first_launch", false).apply()
        }
    }

    fun dismissOnboardingHint() {
        _showOnboardingHint.value = false
    }

    fun dismissSummary() {
        TransferStatus.clearSummary()
    }

    fun stopSearching() {
        Log.d("QuickDropVM", "⏹️ Stopping search...")
        discovery?.stopDiscovery()
        discoveryJob?.cancel()
        discoveryJob = null
        
        if (_uiState.value is UiState.Scanning) {
            _uiState.value = UiState.Idle
        }
    }

    fun resetToIdle() {
        stopSearching()
        _uiState.value = UiState.Idle
    }

    fun startManualScan(context: Context) {
        internalStartDiscovery(context, isSilent = false)
    }

    fun performSilentScan(context: Context) {
        internalStartDiscovery(context, isSilent = true)
    }

    private fun internalStartDiscovery(context: Context, isSilent: Boolean) {
        stopSearching()
        
        if (!isSilent) {
            _uiState.value = UiState.Scanning
        }
        _searchMessage.value = ""

        val newDiscovery = QuickDropDiscovery(context)
        discovery = newDiscovery
        newDiscovery.startDiscovery()
        
        // Track the discovery collection job to prevent leaks
        discoveryJob = viewModelScope.launch {
            newDiscovery.devices.collect { newList ->
                _discoveredDevices.value = newList
                val current = _uiState.value
                val isRelevant = current is UiState.Scanning || 
                                current is UiState.DevicesFound || 
                                current is UiState.NoDevicesFound || 
                                current is UiState.Idle
                
                if (isRelevant && newList.isNotEmpty()) {
                    _uiState.value = UiState.DevicesFound(newList.sortedBy { it.deviceName })
                }
            }
        }

        // Timeout logic: 8 seconds (only for manual scan)
        if (!isSilent) {
            viewModelScope.launch {
                delay(8000)
                if (_uiState.value == UiState.Scanning) {
                    Log.w("QuickDropVM", "🕒 Manual scan timed out")
                    stopSearching()
                    _uiState.value = UiState.NoDevicesFound
                }
            }
        }
    }

    private var autoScanJob: Job? = null

    fun startAutoScanning(context: Context) {
        if (autoScanJob?.isActive == true) return
        
        autoScanJob = viewModelScope.launch {
            while (true) {
                val currentState = _uiState.value
                val currentlyUploading = _isUploading.value
                
                // Only scan if Idle, showing a list, or showing "No Devices"
                // Do NOT scan if a device is selected or if uploading
                val canScan = (currentState is UiState.Idle || 
                               currentState is UiState.DevicesFound || 
                               currentState is UiState.NoDevicesFound) && !currentlyUploading
                
                if (canScan) {
                    Log.d("QuickDropVM", "📡 Auto-scan pulse...")
                    _isAutoScanning.value = true
                    performSilentScan(context)
                    delay(4000) // Keep discovery active for 4s
                    discovery?.stopDiscovery()
                    _isAutoScanning.value = false
                }
                
                delay(10000) // Wait 10s before next pulse
            }
        }
    }

    fun stopAutoScanning() {
        autoScanJob?.cancel()
        autoScanJob = null
        _isAutoScanning.value = false
    }

    // --- RECEIVER LOGIC ---
    fun startReceiver(context: Context) {
        if (receiverServer == null) {
            try {
                receiverServer = QuickDropReceiverServer(
                    context = context,
                    onRequest = { fileName: String, fileSize: Long, deviceName: String, id: String?, count: Int, senderIp: String? ->
                        val deferred = CompletableDeferred<Boolean>()
                        
                        val thumbUri = if (!id.isNullOrBlank() && !senderIp.isNullOrBlank()) {
                            Uri.parse("http://$senderIp:8081/thumbnail?id=$id")
                        } else null
                        
                        // [NEW] Show Heads-up notification only if app is in background
                        if (!isAppInForeground) {
                            NotificationHelper.showIncomingRequestNotification(context, deviceName, fileName)
                        }
                        
                        // Bridge to UI
                        incomingRequest.value = IncomingRequest(
                            fileName = fileName,
                            fileSize = fileSize,
                            deviceName = deviceName,
                            fileType = getFileType(fileName),
                            count = count,
                            thumbnailUri = thumbUri,
                            onDecision = { accepted ->
                                if (accepted) {
                                    // [v1.9.0] Start Foreground Service for Reception
                                    val intent = Intent(context, TransferService::class.java).apply {
                                        action = "ACTION_RECEIVE_START"
                                        putExtra("fileName", fileName)
                                        putExtra("fileSize", FileHelper.formatBytes(fileSize))
                                    }
                                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                    TransferStatus.updateMetadata(thumbUri?.toString(), getFileType(fileName))
                                }
                                deferred.complete(accepted)
                                incomingRequest.value = null // Clear dialog
                                
                                // [NEW] Clear notification
                                NotificationHelper.cancelIncomingRequestNotification(context)
                            }
                        )
                        
                        deferred.await() // Wait for user decision
                    },
                    onUploadComplete = { count, totalSize ->
                        // [v1.9.0] Stop Foreground Service for Reception
                        val intent = Intent(context, TransferService::class.java).apply {
                            action = "ACTION_RECEIVE_STOP"
                        }
                        context.startService(intent)
                        
                        handleUploadComplete(count, totalSize)
                        
                        // [NEW] Show Notification
                        NotificationHelper.showCompletionNotification(context, count, totalSize)
                    },
                    onUploadFailed = { fileName, error ->
                        // [v1.9.0] Stop Foreground Service for Reception
                        val intent = Intent(context, TransferService::class.java).apply {
                            action = "ACTION_RECEIVE_STOP"
                        }
                        context.startService(intent)
                        
                        val errorMsg = error.message ?: ""
                        val isCancelled = errorMsg.contains("Socket closed", ignoreCase = true) ||
                                          errorMsg.contains("Connection reset", ignoreCase = true) ||
                                          errorMsg.contains("less than two boundary strings", ignoreCase = true)
                                          
                        handleUploadFailed(fileName, isCancelled)
                    }
                )
                receiverServer?.start()
                Log.d("QuickDropVM", "🚀 Receiver Server STARTED on port 8000")
            } catch (e: Exception) {
                Log.e("QuickDropVM", "❌ Failed to start receiver server", e)
            }
        }

        if (serviceAdvertiser == null) {
            try {
                serviceAdvertiser = QuickDropServiceAdvertiser(context)
                serviceAdvertiser?.start(8000)
                Log.d("QuickDropVM", "📡 Advertiser STARTED")
            } catch (e: Exception) {
                Log.e("QuickDropVM", "❌ Failed to start advertiser", e)
                serviceAdvertiser?.stop()
                serviceAdvertiser = null
            }
        }
    }

    fun stopReceiver() {
        Log.d("QuickDropVM", "🛑 Stopping receiver and advertiser...")
        receiverServer?.stop()
        receiverServer = null
        serviceAdvertiser?.stop()
        serviceAdvertiser = null
    }

    // Decisions are now handled via callbacks in the IncomingRequest model

    fun clearIncomingTransferResult() {
        _incomingTransferResult.value = null
    }

    private fun handleUploadComplete(count: Int, totalSize: Long) {
        Log.d("QuickDropVM", "✅ Incoming files saved: $count files")
        TransferStatus.setSummary(TransferSummary(
            type = "receive",
            count = count,
            totalSize = FileHelper.formatBytes(totalSize),
            result = "success"
        ))
    }

    private fun handleUploadFailed(fileName: String, isCancelled: Boolean = false) {
        Log.e("QuickDropVM", "❌ Incoming file failed: $fileName. Cancelled: $isCancelled")
        TransferStatus.setSummary(TransferSummary(
            type = "receive",
            count = 1,
            totalSize = "0 B",
            result = if (isCancelled) "cancelled" else "failed"
        ))
        TransferStatus.setUploading(false)
    }

    fun selectDevice(device: QuickDropDevice) {
        _uiState.value = UiState.DeviceSelected(device)
        Log.d("QuickDropVM", "🎯 Selected device: ${device.deviceName} at ${device.ipAddress}")
    }

    override fun onCleared() {
        super.onCleared()
        discovery?.stopDiscovery()
        stopReceiver()
        thumbnailServer?.stop()
        thumbnailServer = null
        transferMap.clear()
    }


    suspend fun sendFile(context: Context, files: List<Uri>): TransferResult {
        Log.d("QuickDropVM", "📂 Preparing to send ${files.size} files via Service")
        
        return withContext(Dispatchers.IO) {
            try {
                val currentState = _uiState.value
                val targetDevice = if (currentState is UiState.DeviceSelected) currentState.device else null
                val ip = targetDevice?.ipAddress

                if (ip.isNullOrBlank()) {
                    Log.e("QuickDropVM", "❌ No target device selected or IP missing")
                    return@withContext TransferResult.Error("No target device selected")
                }

                if (files.isEmpty()) {
                    Log.e("QuickDropVM", "❌ No files selected")
                    return@withContext TransferResult.Error("No files selected")
                }

                // 1. Metadata Extraction for the Batch
                var totalBytes = 0L
                val fileNames = mutableListOf<String>()
                
                for (uri in files) {
                    totalBytes += FileHelper.getFileSize(context, uri)
                    fileNames.add(FileHelper.getFileName(context, uri))
                }

                // Summarize for handshake UX
                val displayFileName = if (files.size == 1) {
                    fileNames[0]
                } else {
                    "${fileNames[0]} and ${files.size - 1} more files"
                }

                val formattedTotalSize = FileHelper.formatBytes(totalBytes)

                _selectedFileName.value = displayFileName
                _selectedFileSize.value = formattedTotalSize

                // 2. Handshake Phase
                Log.d("QuickDropVM", "🤝 Handshake: Sending request for '$displayFileName'...")
                _isWaitingForPermission.value = true
                
                // 📸 Intelligence Layer: Generate Transfer ID & Map URI (Phase 2)
                val transferId = UUID.randomUUID().toString()
                val heroUri = selectHeroUri(files, context)
                transferMap[transferId] = heroUri
                
                // Ensure Thumbnail Server is running
                if (thumbnailServer == null) {
                    try {
                        thumbnailServer = ThumbnailServer(context, transferMap)
                        thumbnailServer?.start()
                        Log.d("QuickDropVM", "🚀 Thumbnail Server started on port 8081")
                    } catch (e: Exception) {
                        Log.e("QuickDropVM", "❌ Failed to start Thumbnail Server", e)
                    }
                }

                val resolver = context.contentResolver
                val heroMime = resolver.getType(heroUri) ?: "*/*"
                
                val preview = TransferPreview(
                    fileType = resolveFileType(heroMime),
                    previewMode = com.ankit.filedrop.ui.dialogs.resolvePreviewMode(context, files).name,
                    count = files.size,
                    thumbnail = null // ❌ HANDSHAKE IS NOW THUMBNAIL-FREE
                )

                val isAccepted = try {
                    val resp = QuickDropTransfer.requestTransfer(
                        ipAddress = ip, 
                        fileName = displayFileName, 
                        fileSize = totalBytes, 
                        preview = preview,
                        transferId = transferId // ✅ PASS ID FOR ASYNC FETCHING
                    )
                    resp
                } catch (e: Exception) {
                    Log.e("QuickDropVM", "❌ Handshake failure: ${e.message}")
                    transferMap.remove(transferId) // 🧹 Cleanup mapping on failure
                    false
                } finally {
                    _isWaitingForPermission.value = false
                }

                if (!isAccepted) {
                    Log.w("QuickDropVM", "🚫 Transfer declined by peer")
                    transferMap.remove(transferId) // 🧹 Cleanup mapping on decline
                    // Reset state to allow fresh selection if declined
                    _uiState.value = UiState.Idle
                    return@withContext TransferResult.Declined
                }

                // 3. Start Foreground Service for Upload
                Log.d("QuickDropVM", "🚀 Handshake accepted. Starting TransferService via Intent...")
                val intent = Intent(context, TransferService::class.java).apply {
                    action = "ACTION_SEND"
                    putStringArrayListExtra("files", ArrayList(files.map { it.toString() }))
                    putExtra("ip", ip)
                }
                
                // Update Metadata for Progress Card (Native Coil Fetch via embedded ThumbnailServer)
                val heroFile = if (files.size == 1) FileHelper.getFileName(context, files[0]) else "${FileHelper.getFileName(context, files[0])} + ${files.size - 1}"
                val localThumbEndpoint = "http://127.0.0.1:8081/thumbnail?id=$transferId"
                TransferStatus.updateMetadata(localThumbEndpoint, preview.fileType)
                
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                
                // 🧹 Delayed cleanup: ensure Mac has time to finish fetching thumbnail
                viewModelScope.launch {
                    delay(10000)
                    transferMap.remove(transferId)
                }

                // Return success immediately as the service has taken over
                TransferResult.Success

            } catch (e: Exception) {
                Log.e("QuickDropVM", "🔥 Critical failure in sendFile: ${e.message}", e)
                // Auto-reset on critical failure to prevent stuck UI
                _uiState.value = UiState.Idle
                TransferResult.Error(e.message ?: "Critical failure")
            }
        }
    }
}
