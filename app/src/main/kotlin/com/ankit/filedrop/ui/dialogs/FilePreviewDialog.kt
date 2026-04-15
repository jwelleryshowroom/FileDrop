package com.ankit.filedrop.ui.dialogs

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import android.content.Context
import com.ankit.filedrop.FileHelper
import com.ankit.filedrop.QuickDropViewModel
import com.ankit.filedrop.ui.models.PreviewMode
import com.ankit.filedrop.ui.widgets.MixedPreview
import com.ankit.filedrop.ui.widgets.MultiPreviewStack
import com.ankit.filedrop.ui.widgets.PreviewBox
import kotlinx.coroutines.delay

@Composable
fun FilePreviewDialog(
    uris: List<Uri>,
    viewModel: QuickDropViewModel,
    queuedCount: Int,
    isWaitingForNetwork: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val isUploading by viewModel.isUploading.collectAsState()
    val progress by viewModel.uploadProgress.collectAsState()
    val speed by viewModel.uploadSpeed.collectAsState()
    val eta by viewModel.uploadEta.collectAsState()
    
    val isMultiple = uris.size > 1
    val firstUri = uris.first()
    
    var hasStartedTransfer by remember { mutableStateOf(false) }
    var showCompletion by remember { mutableStateOf(false) }
    var isGridExpanded by remember { mutableStateOf(false) }
    
    // Logic to detect completion
    LaunchedEffect(isUploading) {
        if (isUploading) {
            hasStartedTransfer = true
        } else if (hasStartedTransfer) {
            showCompletion = true
            delay(2500)
            onCancel() // Close dialog
        }
    }
    
    // Resolve mode (Production System v2)
    val previewMode = resolvePreviewMode(context, uris)
    val totalBytes = uris.sumOf { FileHelper.getFileSize(context, it) }
    val formattedSize = FileHelper.formatBytes(totalBytes)

    Dialog(onDismissRequest = { if (!isUploading && !showCompletion) onCancel() }) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .animateContentSize(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showCompletion) {
                    // --- SUCCESS STATE ---
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .padding(top = 16.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Transfer Complete",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Done")
                    }
                } else if (isUploading) {
                    // --- PROGRESS STATE ---
                    Text(
                        text = if (isWaitingForNetwork) "Waiting for Network..." else "Sending...",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = if (isWaitingForNetwork) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    // Keep the preview thumbnail visible during transfer (v2)
                    Box(modifier = Modifier.padding(8.dp)) {
                        RenderPreview(previewMode, uris, context, isGridExpanded = false)
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                        color = if (isWaitingForNetwork) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = speed, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        if (queuedCount > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "+$queuedCount queued",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(text = eta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    // --- PREVIEW STATE ---
                    Text(
                        text = "Send this file?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Step 1: Fix Root Container (Lock Height to prevent collapse)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .animateContentSize()
                            .clickable(enabled = uris.size > 1) { 
                                isGridExpanded = !isGridExpanded 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        RenderPreview(previewMode, uris, context, isGridExpanded)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Metadata Section (Smart Visibility - Final Consistency Rule)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = formattedSize,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        
                        val labelText = when (previewMode) {
                            PreviewMode.SINGLE_IMAGE -> "Image"
                            PreviewMode.SINGLE_VIDEO -> "Video"
                            PreviewMode.SINGLE_FILE -> FileHelper.getFileName(context, firstUri)
                            PreviewMode.MULTIPLE_SAME -> "${uris.size} Files"
                            PreviewMode.MULTIPLE_MIXED -> "${uris.size} Files"
                        }

                        Text(
                            text = labelText,
                            style = if (previewMode == PreviewMode.SINGLE_FILE) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Action Buttons (Horizontal Balance - Step 4)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
                        ) {
                            Text("Decline", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = { 
                                hasStartedTransfer = true
                                onSend() 
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                        ) {
                            Text("Send", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderPreview(mode: PreviewMode, uris: List<Uri>, context: Context, isGridExpanded: Boolean) {
    val firstUri = uris.first()
    val mimeType = context.contentResolver.getType(firstUri) ?: "*/*"
    
    if (isGridExpanded) {
        com.ankit.filedrop.ui.widgets.FileGridExplorer(uris, context)
    } else {
        when (mode) {
            PreviewMode.SINGLE_IMAGE -> PreviewBox("image/", firstUri)
            PreviewMode.SINGLE_VIDEO -> PreviewBox("video/", firstUri)
            PreviewMode.SINGLE_FILE -> PreviewBox(mimeType, firstUri)
            PreviewMode.MULTIPLE_SAME,
            PreviewMode.MULTIPLE_MIXED -> {
                com.ankit.filedrop.ui.widgets.MultiFilePreviewHero(uris, context)
            }
        }
    }
}

fun resolvePreviewMode(context: Context, uris: List<Uri>): PreviewMode {
    if (uris.size == 1) {
        val type = context.contentResolver.getType(uris.first()) ?: ""
        return when {
            type.startsWith("image/") -> PreviewMode.SINGLE_IMAGE
            type.startsWith("video/") -> PreviewMode.SINGLE_VIDEO
            else -> PreviewMode.SINGLE_FILE
        }
    }

    val types = uris.map {
        context.contentResolver.getType(it)?.substringBefore("/") ?: "unknown"
    }.distinct()

    return if (types.size == 1) PreviewMode.MULTIPLE_SAME
    else PreviewMode.MULTIPLE_MIXED
}
