package com.anve.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.ui.theme.AnVeTheme

/**
 * The app-wide failure presentation: an errorContainer card rather than bare
 * error-colored text, so every failed state carries the same visual weight.
 * The sign-in and home screens established the pattern; these screens reuse
 * it instead of re-deciding it. Retry stays optional because some failures
 * already have their recovery elsewhere.
 *
 * The message text marks itself as a polite live region inside the component,
 * so a failure that appears on any surface using it is announced by screen
 * readers without each call site re-declaring the semantics. Dismiss stays
 * optional next to retry for surfaces where acknowledging the failure is the
 * recovery, not repeating an action.
 */
@Composable
fun AnveErrorNotice(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            when {
                onRetry != null && onDismiss != null -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NoticeAction(stringResource(R.string.action_retry), onRetry)
                    NoticeAction(stringResource(R.string.action_dismiss), onDismiss)
                }
                onRetry != null -> NoticeAction(stringResource(R.string.action_retry), onRetry)
                onDismiss != null -> NoticeAction(stringResource(R.string.action_dismiss), onDismiss)
            }
        }
    }
}

/** Single notice action; 48dp floor keeps the touch target in one place. */
@Composable
private fun NoticeAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label)
    }
}

@Preview(showBackground = true)
@Composable
private fun AnveErrorNoticePreview() {
    AnVeTheme {
        AnveErrorNotice("Không thể tải dữ liệu. Vui lòng thử lại.", onRetry = {})
    }
}
