package com.ankit.filedrop

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun MacDropScreen(viewModel: MacDropViewModel) {
    val context = LocalContext.current
    val ipAddress by viewModel.ipAddress.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isWaitingForPermission by viewModel.isWaitingForPermission.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val discoveryTimeout by viewModel.discoveryTimeout.collectAsState()
    val deviceName by viewModel.discoveredDeviceName.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val uploadSpeed by viewModel.uploadSpeed.collectAsState()
    val uploadEta by viewModel.uploadEta.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.startSearching(context)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = viewModel.sendFile(context, it)
                val message = when (result) {
                    TransferResult.SUCCESS -> "✅ File Sent Successfully!"
                    TransferResult.DECLINED -> "Transfer declined by Mac"
                    TransferResult.ERROR -> "❌ Network Error"
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 64.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "MacDrop",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Fast. Private. Local.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            ConnectionCard(
                isSearching = isSearching,
                discoveryTimeout = discoveryTimeout,
                deviceName = deviceName,
                ipAddress = ipAddress,
                onRetry = { viewModel.startSearching(context) }
            )

            AnimatedVisibility(
                visible = isWaitingForPermission,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(24.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Waiting for permission on Mac...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = ipAddress.isNotBlank() && !isWaitingForPermission,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(32.dp))
                    PrimaryButton(
                        text = if (isUploading) "Sending..." else "Select & Send File",
                        onClick = { filePickerLauncher.launch("*/*") },
                        enabled = !isUploading && !isWaitingForPermission,
                        isLoading = isUploading
                    )
                }
            }

            AnimatedVisibility(
                visible = isUploading,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(32.dp))
                    TransferProgressCard(
                        fileName = viewModel.selectedFileName.collectAsState().value,
                        fileSize = viewModel.selectedFileSize.collectAsState().value,
                        progress = uploadProgress,
                        speed = uploadSpeed,
                        eta = uploadEta
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionCard(
    isSearching: Boolean,
    discoveryTimeout: Boolean,
    deviceName: String?,
    ipAddress: String,
    onRetry: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                isSearching -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Searching for nearby Macs...",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                discoveryTimeout -> {
                    Text(
                        "No Macs found nearby",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onRetry) {
                        Text("Retry Discovery")
                    }
                }
                ipAddress.isNotBlank() -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Green Status Indicator
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = deviceName ?: "MacBook",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "IP: $ipAddress",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun TransferProgressCard(
    fileName: String,
    fileSize: String,
    progress: Float,
    speed: String,
    eta: String
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "TransferProgress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = fileSize,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = speed,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (eta.isNotEmpty()) {
                        Text(
                            text = eta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}
