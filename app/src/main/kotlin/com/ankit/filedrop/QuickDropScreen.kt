package com.ankit.filedrop

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import coil.compose.SubcomposeAsyncImage
import coil.compose.AsyncImagePainter
import androidx.compose.ui.layout.ContentScale
import com.ankit.filedrop.ui.components.DeviceListView
import com.ankit.filedrop.ui.components.SelectedDeviceCard
import com.ankit.filedrop.ui.dialogs.FilePreviewDialog
import com.ankit.filedrop.ui.views.IdleView
import com.ankit.filedrop.ui.views.NoDevicesView
import com.ankit.filedrop.ui.views.ScanningView
import com.ankit.filedrop.ui.views.SplashScreen
import com.ankit.filedrop.ui.widgets.DynamicProgressCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QuickDropScreen(viewModel: QuickDropViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isWaitingForPermission by viewModel.isWaitingForPermission.collectAsState()
    val searchMessage by viewModel.searchMessage.collectAsState()
    val incomingRequest by viewModel.incomingRequest.collectAsState()
    val incomingTransferResult by viewModel.incomingTransferResult.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val uploadSpeed by viewModel.uploadSpeed.collectAsState()
    val uploadEta by viewModel.uploadEta.collectAsState()
    val queuedCount by viewModel.queuedCount.collectAsState()
    val isWaitingForNetwork by viewModel.isWaitingForNetwork.collectAsState()
    val isAutoScanning by viewModel.isAutoScanning.collectAsState()
    val showOnboardingHint by viewModel.showOnboardingHint.collectAsState()
    val transferThumbnailUri by viewModel.transferThumbnailUri.collectAsState()
    val transferFileType by viewModel.transferFileType.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val selectedFileSize by viewModel.selectedFileSize.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedUrisForPreview by remember { mutableStateOf<List<Uri>?>(null) }
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(2000)
        showSplash = false
    }

    // Start Background Auto-Scan
    LaunchedEffect(Unit) {
        viewModel.startAutoScanning(context)
        viewModel.checkFirstLaunch(context)
    }

    // Auto-dismiss hint after 5 seconds
    LaunchedEffect(showOnboardingHint) {
        if (showOnboardingHint) {
            delay(5000)
            viewModel.dismissOnboardingHint()
        }
    }

    // Start Receiver Server automatically for incoming transfers
    LaunchedEffect(Unit) {
        android.util.Log.d("QuickDropUI", "📡 Ensuring advertiser is running...")
        viewModel.startReceiver(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopReceiver()
        }
    }

    LaunchedEffect(incomingTransferResult) {
        incomingTransferResult?.let { result ->
            val previewType = FileHelper.getPreviewType(result.fileName)
            val message = if (result.isSuccess) {
                "${previewType.label} received"
            } else {
                "${previewType.label} could not be saved"
            }
            snackbarHostState.showSnackbar(message)
            viewModel.clearIncomingTransferResult()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUrisForPreview = uris
        }
    }

    if (showSplash) {
        SplashScreen()
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { 
                SnackbarHost(snackbarHostState) { data ->
                    val isError = data.visuals.message.contains("Error", ignoreCase = true) ||
                                 data.visuals.message.contains("declined", ignoreCase = true) ||
                                 data.visuals.message.contains("could not", ignoreCase = true)
                    
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = data.visuals.message,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = isUploading,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    DynamicProgressCard(
                        fileName = selectedFileName,
                        fileSize = selectedFileSize,
                        progress = uploadProgress,
                        speed = uploadSpeed,
                        eta = uploadEta,
                        queuedCount = queuedCount,
                        isWaitingForNetwork = isWaitingForNetwork,
                        thumbnailUri = transferThumbnailUri,
                        fileType = transferFileType,
                        onStop = { viewModel.stopTransfer(context) }
                    )
                }
            },
            floatingActionButton = {
                if (uiState !is UiState.Scanning) {
                    FloatingActionButton(
                        onClick = { viewModel.startManualScan(context) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }
            }
        ) { padding ->
            // --- INCOMING REQUEST DIALOG ---
            incomingRequest?.let { request ->
                AlertDialog(
                    onDismissRequest = {}, 
                    title = { 
                        Text(
                            text = "Receive this file?", 
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.headlineSmall
                        ) 
                    },
                    text = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Spacer(modifier = Modifier.height(16.dp))
                            ElevatedCard(
                                 modifier = Modifier.padding(8.dp),
                                 shape = RoundedCornerShape(24.dp),
                                 colors = CardDefaults.elevatedCardColors(
                                     containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                 ),
                                 elevation = CardDefaults.elevatedCardElevation(defaultElevation = 20.dp)
                             ) {
                                 Box(
                                     modifier = Modifier.padding(12.dp),
                                     contentAlignment = Alignment.Center
                                 ) {
                                     // [v1.8.1] Multi-file Stack Engine
                                     if (request.count > 1) {
                                         // Rear Card (Inner Shadow/Depth)
                                         Box(
                                             modifier = Modifier
                                                 .size(232.dp)
                                                 .offset(y = (-8).dp)
                                                 .scale(0.92f)
                                                 .alpha(0.4f)
                                                 .clip(RoundedCornerShape(24.dp))
                                                 .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                         )
                                         // Middle Card
                                         Box(
                                             modifier = Modifier
                                                 .size(232.dp)
                                                 .offset(y = (-4).dp)
                                                 .scale(0.96f)
                                                 .alpha(0.7f)
                                                 .clip(RoundedCornerShape(24.dp))
                                                 .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                         )
                                     }

                                     // Front Card (Main Content)
                                     Box(
                                         modifier = Modifier
                                             .size(232.dp)
                                             .clip(RoundedCornerShape(24.dp))
                                             .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                                         contentAlignment = Alignment.Center
                                     ) {
                                         if (request.thumbnailUri != null) {
                                             Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                 var loaded by remember { mutableStateOf(false) }
            
                                                 LaunchedEffect(Unit) {
                                                     delay(1500)
                                                     if (!loaded) loaded = true
                                                 }
                                         
                                                 val alphaVal by animateFloatAsState(
                                                     targetValue = if (loaded) 1f else 0f,
                                                     animationSpec = tween(durationMillis = 400),
                                                     label = "receiverFade"
                                                 )
    
                                                 SubcomposeAsyncImage(
                                                     model = request.thumbnailUri,
                                                     contentDescription = null,
                                                     modifier = Modifier.fillMaxSize().alpha(alphaVal),
                                                     contentScale = ContentScale.Crop
                                                 ) {
                                                     val state = painter.state
                                                     if (state is AsyncImagePainter.State.Success) {
                                                         loaded = true
                                                         Image(
                                                             painter = painter,
                                                             contentDescription = null,
                                                             contentScale = ContentScale.Crop,
                                                             modifier = Modifier.fillMaxSize()
                                                         )
                                                     } else {
                                                         Box(
                                                             modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                                                             contentAlignment = Alignment.Center
                                                         ) {
                                                             when (request.fileType) {
                                                                 "image" -> Icon(Icons.Default.InsertPhoto, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                                                 "video" -> Icon(Icons.Default.PlayCircle, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                                                                 "pdf" -> Icon(Icons.Default.Description, null, modifier = Modifier.size(64.dp), tint = Color(0xFFE91E63).copy(alpha = 0.5f))
                                                                 else -> Icon(Icons.Default.InsertDriveFile, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                                             }
                                                         }
                                                     }
                                                 }
                                                 Box(
                                                     modifier = Modifier.fillMaxSize()
                                                         .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent)))
                                                 )
                                                 if (request.fileType == "video") {
                                                     Icon(
                                                         Icons.Default.PlayArrow,
                                                         null,
                                                         modifier = Modifier.size(48.dp),
                                                         tint = Color.White
                                                     )
                                                 }
    
                                                 // [v1.8.1] Stack Count Badge
                                                 if (request.count > 1) {
                                                     Surface(
                                                         modifier = Modifier
                                                             .align(Alignment.BottomEnd)
                                                             .padding(12.dp),
                                                         color = Color.Black.copy(alpha = 0.7f),
                                                         shape = CircleShape
                                                     ) {
                                                         Text(
                                                             text = "+${request.count - 1}",
                                                             modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                             color = Color.White,
                                                             style = MaterialTheme.typography.labelMedium,
                                                             fontWeight = FontWeight.Bold
                                                         )
                                                     }
                                                 }
                                             }
                                         } else {
                                             when (request.fileType) {
                                                 "image" -> Icon(Icons.Default.InsertPhoto, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
                                                 "video" -> Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.secondary)
                                                 "pdf" -> Icon(Icons.Default.Description, null, modifier = Modifier.size(100.dp), tint = Color(0xFFE91E63))
                                                 else -> Icon(Icons.Default.InsertDriveFile, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.outline)
                                             }
                                         }
                                     }
                                 }
                             }
                             
                             Spacer(modifier = Modifier.height(24.dp))
                             
                             // Metadata Section (Dynamic Contrast Guard)
                             Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                 Text(
                                     text = FileHelper.formatBytes(request.fileSize),
                                     style = MaterialTheme.typography.titleMedium,
                                     fontWeight = FontWeight.Bold,
                                     fontFamily = FontFamily.Monospace,
                                     color = MaterialTheme.colorScheme.onSurface // Dynamic Contrast Fix
                                 )
                                 
                                 Text(
                                     text = request.fileType.replaceFirstChar { it.uppercase() },
                                     style = MaterialTheme.typography.labelLarge,
                                     color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                     fontWeight = FontWeight.SemiBold,
                                     textAlign = TextAlign.Center
                                 )
                             }
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            // --- HORIZONTAL DECISION GROUP ---
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        request.onDecision(false)
                                        viewModel.incomingRequest.value = null
                                    },
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
                                ) {
                                    Text("Decline", fontWeight = FontWeight.SemiBold)
                                }

                                Button(
                                    onClick = {
                                        request.onDecision(true)
                                        viewModel.incomingRequest.value = null
                                    },
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                                ) {
                                    Text("Accept", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {}
                )
            }

            // --- FILE PREVIEW POPUP ---
            selectedUrisForPreview?.let { uris ->
                FilePreviewDialog(
                    uris = uris,
                    viewModel = viewModel,
                    queuedCount = queuedCount,
                    isWaitingForNetwork = isWaitingForNetwork,
                    onCancel = { selectedUrisForPreview = null },
                    onSend = {
                        selectedUrisForPreview = null
                        scope.launch {
                            val result = viewModel.sendFile(context, uris)
                            val message = when (result) {
                                is TransferResult.Success -> "✅ Files Sent Successfully!"
                                is TransferResult.Declined -> "Transfer declined by Mac"
                                is TransferResult.Error -> "❌ Error: ${result.message}"
                            }
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // --- TOP BRANDING ---
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "QuickDrop",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Drop files. Instantly.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Nearby Devices",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // --- DYNAMIC CONTENT BASED ON STATE ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (val state = uiState) {
                        is UiState.Idle -> {
                            IdleView(
                                isAutoScanning = isAutoScanning,
                                showHint = showOnboardingHint,
                                onScan = { 
                                    viewModel.dismissOnboardingHint()
                                    viewModel.startManualScan(context) 
                                }
                            )
                        }
                        is UiState.Scanning -> {
                            ScanningView()
                        }
                        is UiState.DevicesFound -> {
                            DeviceListView(
                                devices = state.devices,
                                isAutoScanning = isAutoScanning,
                                onDeviceSelected = { viewModel.selectDevice(it) }
                            )
                        }
                        is UiState.DeviceSelected -> {
                            SelectedDeviceCard(
                                device = state.device,
                                onSendFile = { filePickerLauncher.launch("*/*") },
                                onChange = { viewModel.resetToIdle() }
                            )
                        }
                        is UiState.NoDevicesFound -> {
                            NoDevicesView(onRetry = { viewModel.startManualScan(context) })
                        }
                    }
                }
            }
        }
    }
}
