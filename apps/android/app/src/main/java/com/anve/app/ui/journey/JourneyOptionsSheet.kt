package com.anve.app.ui.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.ui.theme.AnVeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyOptionsSheet(automaticArrival: Boolean, staleAfterMinutes: Int, onDismiss: () -> Unit, onApply: (Boolean, Int) -> Unit) {
    var automatic by remember { mutableStateOf(automaticArrival) }
    var stale by remember { mutableStateOf(staleAfterMinutes) }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.86f).padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("journey-options-sheet"),
            contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Cho hành trình này", style = MaterialTheme.typography.headlineSmall)
                Text("Thiết lập riêng cho hành trình này.", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                OptionCard("Tự nhận diện đến nơi", "GPS chỉ cho kết quả ước tính. Khi đủ dữ liệu, hành trình tự kết thúc và báo người xem. Bạn vẫn có thể xác nhận thủ công.", Icons.Default.LocationOn,
                    automatic, { automatic = it }, "automatic-arrival")
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OptionRow("Nhắc khi ngừng cập nhật", "Gửi cho bạn và người xem đã chọn.", Icons.Default.Notifications,
                            stale > 0, { stale = if (it) 10 else 0 }, "stale-reminder")
                        if (stale > 0) {
                            Text("Báo khi chưa có cập nhật trong", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(5, 10, 15, 30).forEach { value ->
                                    FilterChip(stale == value, onClick = { stale = value }, label = { Text("$value phút") },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primaryContainer, selectedLabelColor = colors.onPrimaryContainer))
                                }
                            }
                        }
                    }
                }
            }
        }
        Button(onClick = { onApply(automatic, stale) }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (dark) colors.primaryContainer else colors.primary, contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary)) { Text("Áp dụng") }
        }
    }
}

@Composable
private fun OptionCard(title: String, helper: String, icon: ImageVector, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { OptionRow(title, helper, icon, checked, onChecked, tag) }
    }
}

@Composable
private fun OptionRow(title: String, helper: String, icon: ImageVector, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked, onChecked, Modifier.testTag(tag).semantics { contentDescription = title })
        }
        Text(helper, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview
@Composable
private fun OptionsPreview() { AnVeTheme { JourneyOptionsSheet(false, 10, {}, { _, _ -> }) } }
