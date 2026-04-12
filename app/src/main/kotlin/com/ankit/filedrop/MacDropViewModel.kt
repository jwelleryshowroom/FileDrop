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

enum class DiscoveryState {
    IDLE, SEARCHING, DEVICES_FOUND, TIMEOUT
}

data class IncomingRequest(
    val fileName: String,
    val fileSize: Long,
    val deviceName: String
)

data class IncomingTransferResult(
    val fileName: String,
    val fileSize: Long,
    val isSuccess: Boolean
)

class MacDropViewModel : ViewModel() {

    private val _discoveredDevices = MutableStateFlow<List<MacDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<MacDevice>> = _discoveredDevices

    private val _selectedDevice = MutableStateFlow<MacDevice?>(null)
    val selectedDevice: StateFlow<MacDevice?> = _selectedDevice

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    private val _isWaitingForPermission = MutableStateFlow(false)
    val isWaitingForPermission: StateFlow<Boolean> = _isWaitingForPermission

    private val _discoveryState = MutableStateFlow(DiscoveryState.IDLE)
    val discoveryState = _discoveryState.asStateFlow()

    private val _searchMessage = MutableStateFlow("")
    val searchMessage = _searchMessage.asStateFlow()

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

    private val _incomingRequest = MutableStateFlow<IncomingRequest?>(null)
    val incomingRequest = _incomingRequest.asStateFlow()

    private val _incomingTransferResult = MutableStateFlow<IncomingTransferResult?>(null)
    val incomingTransferResult = _incomingTransferResult.asStateFlow()

    private var decision = CompletableDeferred<Boolean>()
    private var receiverServer: MacDropReceiverServer? = null
    private var serviceAdvertiser: MacDropServiceAdvertiser? = null
    private var discovery: MacDropDiscovery? = null

    // Alias for compatibility if needed, but we'll use discoveredDevices
    val devices: StateFlow<List<MacDevice>> = _discoveredDevices

    fun stopSearching() {
        discovery?.stopDiscovery()
        _discoveryState.value = DiscoveryState.IDLE
    }

    fun startSearching(context: Context) {
        stopSearching()
        
        _discoveryState.value = DiscoveryState.SEARCHING
        _searchMessage.value = ""
        _discoveredDevices.value = emptyList()
        _selectedDevice.value = null

        discovery = MacDropDiscovery(context).apply {
            startDiscovery()
            
            // Observe the discovery list
            viewModelScope.launch {
                devices.collect { newList ->
                    _discoveredDevices.value = newList
                    if (newList.isNotEmpty()) {
                        _discoveryState.value = DiscoveryState.DEVICES_FOUND
                    }
                }
            }
        }

        // Timeout logic
        viewModelScope.launch {
            delay(15000)
            if (_discoveryState.value == DiscoveryState.SEARCHING) {
                discovery?.stopDiscovery()
                _discoveryState.value = DiscoveryState.TIMEOUT
                _searchMessage.value = "No devices found"
            }
        }
    }

    // --- RECEIVER LOGIC ---

    fun startReceiver(context: Context) {
        if (receiverServer != null) return
        
        Log.d("MacDropVM", "📡 Starting Receiver Server on port 8000...")
        receiverServer = MacDropReceiverServer(
            context = context,
            onRequest = { name: String, size: Long, device: String ->
                handleIncomingRequest(name, size, device)
            },
            onUploadComplete = { name: String, size: Long ->
                handleUploadComplete(name, size)
            },
            onUploadFailed = { name: String ->
                handleUploadFailed(name)
            }
        )
        try {
            receiverServer?.start()
            serviceAdvertiser = MacDropServiceAdvertiser(context).apply {
                start(8000)
            }
        } catch (e: Exception) {
            Log.e("MacDropVM", "❌ Failed to start receiver", e)
            receiverServer?.stop()
            receiverServer = null
            serviceAdvertiser?.stop()
            serviceAdvertiser = null
        }
    }

    fun stopReceiver() {
        serviceAdvertiser?.stop()
        serviceAdvertiser = null
        receiverServer?.stop()
        receiverServer = null
    }

    private suspend fun handleIncomingRequest(
        fileName: String,
        fileSize: Long,
        deviceName: String
    ): Boolean {
        Log.d("MacDropVM", "🔔 Incoming request from $deviceName for $fileName")
        _incomingRequest.value = IncomingRequest(fileName, fileSize, deviceName)
        decision = CompletableDeferred()
        return try {
            decision.await()
        } catch (e: Exception) {
            false
        }
    }

    fun acceptTransfer() {
        decision.complete(true)
        _incomingRequest.value = null
    }

    fun declineTransfer() {
        decision.complete(false)
        _incomingRequest.value = null
    }

    fun clearIncomingTransferResult() {
        _incomingTransferResult.value = null
    }

    private fun handleUploadComplete(fileName: String, fileSize: Long) {
        Log.d("MacDropVM", "✅ Incoming file saved: $fileName")
        _incomingTransferResult.value = IncomingTransferResult(fileName, fileSize, isSuccess = true)
    }

    private fun handleUploadFailed(fileName: String) {
        Log.e("MacDropVM", "❌ Incoming file failed: $fileName")
        _incomingTransferResult.value = IncomingTransferResult(fileName, 0L, isSuccess = false)
    }

    fun selectDevice(device: MacDevice) {
        _selectedDevice.value = device
        Log.d("MacDropVM", "🎯 Selected device: ${device.deviceName} at ${device.ipAddress}")
    }

    override fun onCleared() {
        super.onCleared()
        discovery?.stopDiscovery()
        stopReceiver()
    }


    suspend fun sendFile(context: Context, uri: Uri): TransferResult {
        Log.d("MacDropVM", "📂 Preparing to send file: $uri")
        
        return withContext(Dispatchers.IO) {
            try {
                val targetDevice = _selectedDevice.value
                val ip = targetDevice?.ipAddress

                if (ip.isNullOrBlank()) {
                    Log.e("MacDropVM", "❌ No target device selected or IP missing")
                    return@withContext TransferResult.ERROR
                }

                // 1. Metadata Extraction
                val fileName = FileHelper.getFileName(context, uri)
                val fileSize = FileHelper.getFileSize(context, uri)

                if (fileName.isBlank() || fileSize <= 0L) {
                    Log.e("MacDropVM", "❌ Invalid file metadata: $fileName ($fileSize bytes)")
                    return@withContext TransferResult.ERROR
                }

                _selectedFileName.value = fileName
                _selectedFileSize.value = FileHelper.formatBytes(fileSize)
                _uploadProgress.value = 0f
                _uploadSpeed.value = ""
                _uploadEta.value = ""

                // 2. Handshake Phase
                Log.d("MacDropVM", "🤝 Handshake: Sending request to '${targetDevice.deviceName}' at $ip...")
                _isWaitingForPermission.value = true
                val isAccepted = try {
                    val resp = MacDropTransfer.requestTransfer(ip, fileName, fileSize)
                    Log.d("MacDropVM", "🤝 Handshake: Received response accepted=$resp")
                    resp
                } catch (e: Exception) {
                    Log.e("MacDropVM", "❌ Handshake failure: ${e.message}", e)
                    false
                } finally {
                    _isWaitingForPermission.value = false
                }

                if (!isAccepted) {
                    Log.w("MacDropVM", "🚫 Transfer declined by peer")
                    return@withContext TransferResult.DECLINED
                }

                // 3. Upload Phase
                Log.d("MacDropVM", "🚀 Handshake accepted. Starting upload...")
                _isUploading.value = true
                val result = try {
                    MacDropTransfer.uploadFile(context, uri, ip) { prog, speed, eta ->
                        _uploadProgress.value = prog
                        _uploadSpeed.value = speed
                        _uploadEta.value = eta
                    }
                } catch (e: Exception) {
                    Log.e("MacDropVM", "❌ Upload exception: ${e.message}")
                    TransferResult.ERROR
                } finally {
                    _isUploading.value = false
                }

                Log.d("MacDropVM", "🏁 Transfer finished with result: $result")
                result

            } catch (e: Exception) {
                Log.e("MacDropVM", "🔥 Critical failure in sendFile: ${e.message}", e)
                TransferResult.ERROR
            }
        }
    }
}
