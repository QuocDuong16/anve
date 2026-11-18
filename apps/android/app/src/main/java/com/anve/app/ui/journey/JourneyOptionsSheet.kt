package com.anve.app.ui.journey

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.theme.isDarkSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyOptionsSheet(automaticArrival: Boolean, staleAfterMinutes: Int, onDismiss: () -> Unit, onApply: (Boolean, Int) -> Unit) {
    var automatic by remember { mutableStateOf(automaticArrival) }
    var stale by remember { mutableStateOf(staleAfterMinutes) }
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val dark = isDarkSurface()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = stitch.cardTactile,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sheetMaxHeight = maxHeight * 0.86f
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
                .padding(horizontal = 16.dp),
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("journey-options-sheet"),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    AnVeSectionHeader(
                        title = stringResource(R.string.journey_options_title),
                    )
                }
                item {
                    OptionRow(
                        title = stringResource(R.string.journey_options_auto_title),
                        helper = stringResource(R.string.journey_options_auto_helper),
                        checked = automatic,
                        onChecked = { automatic = it },
                        tag = "automatic-arrival",
                    )
                }
                item {
                    HorizontalDivider(color = stitch.hairline)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OptionRow(
                            title = stringResource(R.string.journey_options_stale_title),
                            helper = stringResource(R.string.journey_options_stale_helper),
                            checked = stale > 0,
                            onChecked = { stale = if (it) 10 else 0 },
                            tag = "stale-reminder",
                        )
                        if (stale > 0) {
                            Text(stringResource(R.string.journey_options_stale_threshold), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(5, 10, 15, 30).forEach { value ->
                                    FilterChip(
                                        selected = stale == value,
                                        onClick = { stale = value },
                                        label = { Text(stringResource(R.string.journey_chip_minutes, value)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = stitch.liveContainer,
                                            selectedLabelColor = stitch.onLiveContainer,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { onApply(automatic, stale) },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (dark) colors.primaryContainer else colors.primary,
                    contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                ),
            ) { Text(stringResource(R.string.journey_options_apply)) }
        }
        }
    }
}

@Composable
private fun OptionRow(title: String, helper: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The whole row is the switch target with a 48dp minimum, like the
        // toggleable rows in SosScreen and the journey create form; the child
        // Switch stays indicative (onCheckedChange = null) so TalkBack reads
        // the merged row — title text plus toggle state — instead of a bare
        // switch, and the test tag lives on the merged root the automation
        // tests click.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(helper, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

@Preview(name = "1. Standard Light", showBackground = true)
@Composable
private fun OptionsPreview() { AnVeTheme { JourneyOptionsSheet(false, 10, {}, { _, _ -> }) } }

@Preview(name = "2. Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun OptionsDarkPreview() { AnVeTheme(darkTheme = true) { JourneyOptionsSheet(true, 15, {}, { _, _ -> }) } }

@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun OptionsElderlyFontPreview() { AnVeTheme { JourneyOptionsSheet(true, 10, {}, { _, _ -> }) } }
