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
import android.util.Log

class MacDropViewModel : ViewModel() {

    private val _ipAddress = MutableStateFlow("")
    val ipAddress: StateFlow<String> = _ipAddress

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    private val _isWaitingForPermission = MutableStateFlow(false)
    val isWaitingForPermission: StateFlow<Boolean> = _isWaitingForPermission

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _discoveryTimeout = MutableStateFlow(false)
    val discoveryTimeout: StateFlow<Boolean> = _discoveryTimeout

    private val _discoveredDeviceName = MutableStateFlow<String?>(null)
    val discoveredDeviceName: StateFlow<String?> = _discoveredDeviceName

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

    private var discovery: MacDropDiscovery? = null

    fun startSearching(context: Context) {
        _isSearching.value = true
        _discoveryTimeout.value = false
        _ipAddress.value = ""
        _discoveredDeviceName.value = null

        discovery = MacDropDiscovery(context).apply {
            startDiscovery(
                onMacFound = { ip, name ->
                    _ipAddress.value = ip
                    _discoveredDeviceName.value = name
                    _isSearching.value = false
                    stopDiscovery()
                },
                onTimeout = {
                    _discoveryTimeout.value = true
                    _isSearching.value = false
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        discovery?.stopDiscovery()
    }

    fun updateIp(newIp: String) {
        _ipAddress.value = newIp
    }

    suspend fun sendFile(context: Context, uri: Uri): TransferResult {
        Log.d("MacDropVM", "📂 Preparing to send file: $uri")
        
        return withContext(Dispatchers.IO) {
            try {
                if (_ipAddress.value.isBlank()) {
                    Log.e("MacDropVM", "❌ IP Address is blank")
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
                Log.d("MacDropVM", "🤝 Handshake: Sending request for '$fileName'...")
                _isWaitingForPermission.value = true
                val isAccepted = try {
                    val resp = MacDropTransfer.requestTransfer(_ipAddress.value, fileName, fileSize)
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
                    MacDropTransfer.uploadFile(context, uri, _ipAddress.value) { prog, speed, eta ->
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
