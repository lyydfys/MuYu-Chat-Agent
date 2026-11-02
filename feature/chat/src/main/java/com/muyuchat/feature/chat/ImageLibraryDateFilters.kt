package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDateRangePickerState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageLibraryDateFilterDialog(
    selectedStartDateMillis: Long?,
    selectedEndDateMillis: Long?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (Long, Long) -> Unit
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = selectedStartDateMillis,
        initialSelectedEndDateMillis = selectedEndDateMillis
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            McaButton(style = McaButtonStyle.Text,
                onClick = { start?.let { onConfirm(it, state.selectedEndDateMillis ?: it) } },
                enabled = start != null
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            if (selectedStartDateMillis != null) {
                McaButton(style = McaButtonStyle.Text,onClick = onClear) { Text("清除") }
            }
            McaButton(style = McaButtonStyle.Text,onClick = onDismiss) { Text("取消") }
        }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            showModeToggle = false
        )
    }
}

internal fun formatImageLibraryDateRange(range: ImageLibraryDateRange): String {
    val zoneId = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("MM/dd")
    val start = Instant.ofEpochMilli(range.startInclusiveMillis).atZone(zoneId).toLocalDate()
    val end = Instant.ofEpochMilli(range.endExclusiveMillis - 1L).atZone(zoneId).toLocalDate()
    return if (start == end) formatter.format(start) else "${formatter.format(start)}–${formatter.format(end)}"
}
