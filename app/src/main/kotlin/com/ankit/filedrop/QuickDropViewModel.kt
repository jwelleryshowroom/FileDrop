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

    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount

    private val _isWaitingForNetwork = MutableStateFlow(false)
    val isWaitingForNetwork: StateFlow<Boolean> = _isWaitingForNetwork

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

    fun saveThumbnailToCache(context: Context, base64: String?): android.net.Uri? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val file = java.io.File(context.cacheDir, "incoming_thumb_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(file).use { it.write(bytes) }
            android.net.Uri.fromFile(file)
        } catch (e: Exception) {
            android.util.Log.e("QuickDropVM", "❌ Failed to save thumbnail", e)
            null
        }
    }

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
        viewModelScope.launch {
            launch { TransferStatus.isUploading.collect { _isUploading.value = it } }
            launch { TransferStatus.progress.collect { _uploadProgress.value = it } }
            launch { TransferStatus.speed.collect { _uploadSpeed.value = it } }
            launch { TransferStatus.eta.collect { _uploadEta.value = it } }
            launch { TransferStatus.queuedCount.collect { _queuedCount.value = it } }
            launch { TransferStatus.isWaitingForNetwork.collect { _isWaitingForNetwork.value = it } }
        }
    }

    fun stopTransfer(context: Context) {
        Log.d("QuickDropVM", "🛑 Stopping transfer service via UI request")
        val intent = Intent(context, TransferService::class.java)
        context.stopService(intent)
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
        if (serviceAdvertiser != null) return
        
        Log.d("QuickDropVM", "📡 Starting Service Advertiser (mDNS)...")
        try {
            serviceAdvertiser = QuickDropServiceAdvertiser(context).apply {
                start(8000)
            }
        } catch (e: Exception) {
            Log.e("QuickDropVM", "❌ Failed to start advertiser", e)
            serviceAdvertiser?.stop()
            serviceAdvertiser = null
        }
    }

    fun stopReceiver() {
        serviceAdvertiser?.stop()
        serviceAdvertiser = null
    }

    // Decisions are now handled via callbacks in the IncomingRequest model

    fun clearIncomingTransferResult() {
        _incomingTransferResult.value = null
    }

    private fun handleUploadComplete(fileName: String, fileSize: Long) {
        Log.d("QuickDropVM", "✅ Incoming file saved: $fileName")
        _incomingTransferResult.value = IncomingTransferResult(fileName, fileSize, isSuccess = true)
    }

    private fun handleUploadFailed(fileName: String) {
        Log.e("QuickDropVM", "❌ Incoming file failed: $fileName")
        _incomingTransferResult.value = IncomingTransferResult(fileName, 0L, isSuccess = false)
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
                    putStringArrayListExtra("files", ArrayList(files.map { it.toString() }))
                    putExtra("ip", ip)
                }
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
