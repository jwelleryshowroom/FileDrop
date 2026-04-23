package com.ankit.filedrop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    val clipboardManager = LocalClipboardManager.current
    
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                // Remove PID filter and use tags to get cleaner logs
                val process = Runtime.getRuntime().exec("logcat -d -v time -s QuickDropVM QuickDropTransfer QuickDropServer TransferService TransferStatus QuickDropUI")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val logList = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logList.add(line!!)
                }
                logs = logList.reversed()
            } catch (e: Exception) {
                logs = listOf("Failed to load logs: ${e.message}")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Developer Logs",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Row {
                        IconButton(onClick = {
                            val allLogs = logs.joinToString("\n")
                            clipboardManager.setText(AnnotatedString(allLogs))
                        }) {
                            Text("Copy", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        }
                        IconButton(onClick = onDismiss) {
                            Text("Close", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    color = Color.Black,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                color = getLogColor(line),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getLogColor(line: String): Color {
    return when {
        line.contains(" E ") -> Color(0xFFFF5252)
        line.contains(" W ") -> Color(0xFFFFD740)
        line.contains(" I ") -> Color(0xFF40C4FF)
        line.contains(" D ") -> Color(0xFFB0BEC5)
        else -> Color.LightGray
    }
}
