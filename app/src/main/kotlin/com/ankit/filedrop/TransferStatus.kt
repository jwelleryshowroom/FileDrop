package com.ankit.filedrop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton object to communicate transfer status between the Foreground Service
 * and the ViewModel/UI.
 */
object TransferStatus {
    private val _isUploading = MutableStateFlow(false)
    val isUploading = _isUploading.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _speed = MutableStateFlow("")
    val speed = _speed.asStateFlow()

    private val _eta = MutableStateFlow("")
    val eta = _eta.asStateFlow()

    private val _result = MutableStateFlow<TransferResult?>(null)
    val result = _result.asStateFlow()

    private val _queuedCount = MutableStateFlow(0)
    val queuedCount = _queuedCount.asStateFlow()

    private val _isWaitingForNetwork = MutableStateFlow(false)
    val isWaitingForNetwork = _isWaitingForNetwork.asStateFlow()

    private val _thumbnailUri = MutableStateFlow<String?>(null)
    val thumbnailUri = _thumbnailUri.asStateFlow()

    private val _fileType = MutableStateFlow<String?>("file")
    val fileType = _fileType.asStateFlow()

    fun updateMetadata(uri: String?, type: String?) {
        _thumbnailUri.value = uri
        _fileType.value = type
    }

    fun updateQueuedCount(count: Int) {
        _queuedCount.value = count
    }

    fun setWaitingForNetwork(waiting: Boolean) {
        _isWaitingForNetwork.value = waiting
    }

    fun updateProgress(prog: Float, spd: String, remaining: String) {
        _progress.value = prog
        _speed.value = spd
        _eta.value = remaining
    }

    fun setUploading(uploading: Boolean) {
        _isUploading.value = uploading
        if (uploading) {
            _result.value = null
            _progress.value = 0f
        } else {
            // Reset state when not uploading to prevent stale values in UI
            _progress.value = 0f
            _speed.value = ""
            _eta.value = ""
            _queuedCount.value = 0
            _isWaitingForNetwork.value = false
        }
    }

    fun setResult(res: TransferResult) {
        _result.value = res
        // We don't set isUploading=false here anymore, 
        // the Service will call setUploading(false) when the queue is finished.
    }
}
