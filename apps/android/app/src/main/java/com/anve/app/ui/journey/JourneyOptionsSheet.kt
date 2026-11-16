package com.anve.app.ui.journey

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
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
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.86f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("journey-options-sheet"),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    AnVeSectionHeader(
                        icon = Icons.Outlined.Tune,
                        title = stringResource(R.string.journey_options_title),
                        subtitle = stringResource(R.string.journey_options_subtitle),
                        iconContainerColor = stitch.liveContainer,
                        iconContentColor = stitch.onLiveContainer,
                    )
                }
                item {
                    OptionCard(
                        title = stringResource(R.string.journey_options_auto_title),
                        helper = stringResource(R.string.journey_options_auto_helper),
                        icon = Icons.Outlined.LocationOn,
                        checked = automatic,
                        onChecked = { automatic = it },
                        tag = "automatic-arrival",
                    )
                }
                item {
                    OutlinedCard(
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, stitch.hairline),
                        colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OptionRow(
                                title = stringResource(R.string.journey_options_stale_title),
                                helper = stringResource(R.string.journey_options_stale_helper),
                                icon = Icons.Outlined.Notifications,
                                checked = stale > 0,
                                onChecked = { stale = if (it) 10 else 0 },
                                tag = "stale-reminder",
                            )
                            if (stale > 0) {
                                Text(stringResource(R.string.journey_options_stale_threshold), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
            Button(
                onClick = { onApply(automatic, stale) },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (dark) colors.primaryContainer else colors.primary,
                    contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary,
                ),
            ) { Text(stringResource(R.string.journey_options_apply)) }
        }
    }
}

@Composable
private fun OptionCard(title: String, helper: String, icon: ImageVector, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    val stitch = LocalStitchColors.current
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, stitch.hairline),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { OptionRow(title, helper, icon, checked, onChecked, tag) }
    }
}

@Composable
private fun OptionRow(title: String, helper: String, icon: ImageVector, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(helper, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

@Preview
@Composable
private fun OptionsPreview() { AnVeTheme { JourneyOptionsSheet(false, 10, {}, { _, _ -> }) } }

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OptionsDarkPreview() { AnVeTheme(darkTheme = true) { JourneyOptionsSheet(true, 15, {}, { _, _ -> }) } }
