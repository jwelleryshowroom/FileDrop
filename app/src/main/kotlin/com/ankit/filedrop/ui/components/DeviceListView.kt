package com.ankit.filedrop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ankit.filedrop.QuickDropDevice
import com.ankit.filedrop.ui.widgets.AutoScanIndicator

@Composable
fun DeviceListView(
    devices: List<QuickDropDevice>,
    isAutoScanning: Boolean,
    onDeviceSelected: (QuickDropDevice) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutoScanIndicator(isVisible = isAutoScanning && devices.isEmpty())
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(devices) { device ->
                DeviceItem(
                    device = device,
                    isSelected = false, 
                    onClick = { onDeviceSelected(device) }
                )
            }
        }
    }
}
