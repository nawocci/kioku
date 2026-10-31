package eu.kanade.presentation.more.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.sync.SyncSettingsViewModel
import eu.kanade.tachiyomi.sync.SyncStatus
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.QrCodeScanner
import mihon.icons.materialsymbols.roundedfilled.Visibility
import mihon.icons.materialsymbols.roundedfilled.VisibilityOff
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

// Shared pieces of the sync UI, used by both the Settings screen and the
// onboarding step so the two flows pair, confirm, and report status the same
// way.

/** A pairing session is running or waiting on a user answer. */
fun SyncStatus.isPairingBusy(): Boolean = pairingBusy

/** The plain status line, shown wherever the current sync state matters. */
@Composable
fun syncStatusText(status: SyncStatus): Pair<String, String?>? = when (status) {
    SyncStatus.Idle -> stringResource(MR.strings.sync_status_idle) to null
    SyncStatus.NotPaired -> null
    SyncStatus.Connecting -> stringResource(MR.strings.sync_status_connecting) to null
    is SyncStatus.Online -> stringResource(MR.strings.sync_status_online) to
        stringResource(MR.strings.sync_last_activity, relativeTime(status.since))
    is SyncStatus.ApplyingSnapshot -> stringResource(MR.strings.sync_status_applying, status.records) to null
    SyncStatus.UploadingLibrary -> stringResource(MR.strings.sync_status_uploading) to null
    is SyncStatus.MergeConfirmation -> stringResource(MR.strings.sync_status_connecting) to null
    is SyncStatus.AwaitingReview -> stringResource(MR.strings.sync_status_connecting) to null
    is SyncStatus.Error -> stringResource(MR.strings.sync_status_error) to status.message
}

/**
 * The merge and review confirmations a pairing may raise. Rendered wherever
 * pairing can run so the safeguards are always answerable, never skipped.
 */
@Composable
fun SyncStatusDialogs(
    status: SyncStatus,
    onResolveMerge: (Boolean) -> Unit,
    onResolveReview: (Boolean) -> Unit,
) {
    when (status) {
        is SyncStatus.MergeConfirmation -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(MR.strings.sync_merge_title)) },
                text = {
                    Text(
                        stringResource(
                            MR.strings.sync_merge_message,
                            status.serverEntries,
                            status.localEntries,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { onResolveMerge(true) }) {
                        Text(stringResource(MR.strings.sync_merge_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onResolveMerge(false) }) {
                        Text(stringResource(MR.strings.sync_merge_cancel))
                    }
                },
            )
        }
        is SyncStatus.AwaitingReview -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(MR.strings.sync_review_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(MR.strings.sync_review_message) +
                                " (" + status.entries.size.toString() + ")",
                        )
                        LazyColumn(
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .fillMaxWidth(),
                        ) {
                            items(status.entries) { entry ->
                                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onResolveReview(true) }) {
                        Text(stringResource(MR.strings.sync_review_keep))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onResolveReview(false) }) {
                        Text(stringResource(MR.strings.sync_review_remove))
                    }
                },
            )
        }
        else -> {}
    }
}

/**
 * The pairing form, shown wherever pairing can start. State lives in the view
 * model, so the Settings screen and the onboarding step show the same form with
 * the same scan prefill and the same errors.
 */
@Composable
fun SyncPairingDialogHost(
    viewModel: SyncSettingsViewModel,
    onScanQr: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val status by viewModel.status.collectAsState()
    if (!state.showPairingDialog) return

    // Keyed on the prefill: state restoration does not validate rememberSaveable
    // inputs against what was saved, so without this a scan would restore the
    // fields saved before the camera opened.
    val prefill = state.scanPrefill
    key(prefill) {
        SyncPairingDialog(
            initialServerUrl = prefill?.first ?: state.serverUrl.orEmpty(),
            initialApiKey = prefill?.second ?: state.apiKey.orEmpty(),
            error = state.error,
            busy = status.isPairingBusy(),
            onPair = viewModel::pair,
            onDismiss = viewModel::dismissPairingDialog,
            onScanQr = onScanQr,
        )
    }
}

/**
 * The server address and API key form, as a dialog. The key prefills from the
 * stored one, so this is also where a paired device can view or copy its key;
 * an eye toggle reveals it. Stays open while pairing runs so failures surface
 * beside the fields; closes itself through the view model state once pairing
 * completes.
 */
@Composable
fun SyncPairingDialog(
    initialServerUrl: String,
    initialApiKey: String,
    error: String?,
    busy: Boolean,
    onPair: (serverUrl: String, key: String) -> Unit,
    onDismiss: () -> Unit,
    onScanQr: (() -> Unit)? = null,
) {
    var serverUrl by rememberSaveable(initialServerUrl) { mutableStateOf(initialServerUrl) }
    var apiKey by rememberSaveable(initialApiKey) { mutableStateOf(initialApiKey) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    val canPair = serverUrl.isNotBlank() && apiKey.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.sync_pair_with_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(MR.strings.sync_server_url)) },
                    trailingIcon = if (onScanQr != null && !busy) {
                        {
                            IconButton(onClick = onScanQr) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.QrCodeScanner,
                                    contentDescription = stringResource(MR.strings.sync_scan_qr_title),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(MR.strings.sync_api_key)) },
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) {
                                    MaterialSymbols.RoundedFilled.VisibilityOff
                                } else {
                                    MaterialSymbols.RoundedFilled.Visibility
                                },
                                contentDescription = stringResource(MR.strings.sync_show_key),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canPair && !busy) onPair(serverUrl, apiKey) },
                    ),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isInsecureHttp(serverUrl)) {
                    Text(
                        stringResource(MR.strings.sync_insecure_http),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (error != null) {
                    Text(
                        stringResource(MR.strings.sync_pairing_failed, error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (busy) {
                // Pairing runs on the app scope; the spinner is the only
                // in-dialog progress affordance while it does. It rides in a
                // disabled TextButton so it aligns with the Cancel button.
                TextButton(enabled = false, onClick = {}) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                TextButton(
                    enabled = canPair,
                    onClick = { onPair(serverUrl, apiKey) },
                ) {
                    Text(stringResource(MR.strings.sync_pair_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/** Warned about in the pairing form: plain HTTP to a non-private host. */
private fun isInsecureHttp(url: String): Boolean {
    if (!url.startsWith("http://")) return false
    val host = url.substringAfter("//").substringBefore(':')
    return !(
        host == "localhost" || host.startsWith("127.") || host.startsWith("192.168.") ||
            host.startsWith("10.") || host.startsWith("172.")
        )
}

/** A short, coarse "how long ago" label, recomputed on recomposition. */
@Composable
internal fun relativeTime(instant: Instant): String {
    val seconds = (Clock.System.now() - instant).inWholeSeconds
    return when {
        seconds < 60 -> stringResource(MR.strings.sync_time_just_now)
        seconds < 3600 -> stringResource(MR.strings.sync_time_minutes_ago, seconds / 60)
        seconds < 86400 -> stringResource(MR.strings.sync_time_hours_ago, seconds / 3600)
        else -> stringResource(MR.strings.sync_time_days_ago, seconds / 86400)
    }
}
