package com.ankit.filedrop.ui.dialogs

import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Error
import com.ankit.filedrop.TransferSummary

@Composable
fun TransferCompleteDialog(
    summary: TransferSummary,
    onDismiss: () -> Unit,
    context: Context
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val icon = when(summary.result) {
                    "success" -> Icons.Default.CheckCircle
                    "cancelled" -> Icons.Default.Cancel
                    else -> Icons.Default.Error
                }
                
                val iconColor = when(summary.result) {
                    "success" -> Color(0xFF4CAF50)
                    "cancelled" -> Color(0xFFFF9800)
                    else -> Color(0xFFF44336)
                }

                val title = when(summary.result) {
                    "success" -> "Transfer Complete!"
                    "cancelled" -> "Transfer Cancelled"
                    else -> "Transfer Failed"
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = iconColor
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                val actionText = if (summary.type == "send") "Sent" else "Received"
                Text(
                    text = "$actionText ${summary.count} files (${summary.totalSize})",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (summary.result == "success" && summary.type == "receive") {
                        Button(
                            onClick = {
                                try {
                                    val uri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FQuickDrop")
                                    val intent = Intent(Intent.ACTION_VIEW)
                                    intent.setDataAndType(uri, "vnd.android.document/directory")
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        context.startActivity(intent)
                                    } catch (e2: Exception) {
                                        // Fallback for some devices
                                    }
                                }
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("View Files")
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
