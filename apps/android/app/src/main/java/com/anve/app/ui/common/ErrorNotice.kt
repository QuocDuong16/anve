package com.anve.app.ui.common

import androidx.compose.foundation.layout.Column
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
 */
@Composable
fun AnveErrorNotice(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AnveErrorNoticePreview() {
    AnVeTheme {
        AnveErrorNotice("Không thể tải dữ liệu. Vui lòng thử lại.", onRetry = {})
    }
}
