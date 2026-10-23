package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.sync.SyncPairingDialog
import eu.kanade.presentation.more.sync.SyncQrScannerScreen
import eu.kanade.presentation.more.sync.SyncStatusDialogs
import eu.kanade.presentation.more.sync.isPairingBusy
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.sync.SyncSettingsViewModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

internal class GuidesStep(
    private val onRestoreBackup: () -> Unit,
) : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val handler = LocalUriHandler.current

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(stringResource(MR.strings.onboarding_guides_new_user, stringResource(MR.strings.app_name)))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { handler.openUri(GETTING_STARTED_URL) },
            ) {
                Text(stringResource(MR.strings.getting_started_guide))
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Text(stringResource(MR.strings.onboarding_guides_returning_user, stringResource(MR.strings.app_name)))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRestoreBackup,
            ) {
                Text(stringResource(MR.strings.pref_restore_backup))
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            SyncSetupSection()
        }
    }
}

/**
 * Optional sync pairing inside the guides step. Reuses the settings screen's
 * view model and pairing dialog, so onboarding and Settings pair identically.
 * Skipping is the default: nothing here blocks finishing onboarding, and an
 * unpaired device simply stays unpaired until configured later in Settings.
 */
@Composable
private fun SyncSetupSection() {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<SyncSettingsViewModel>()
    val state by viewModel.state.collectAsState()
    val status by viewModel.status.collectAsState()

    // Pairing's confirmations must stay answerable while it runs here.
    SyncStatusDialogs(
        status = status,
        onResolveMerge = viewModel::resolveMergeConfirmation,
        onResolveReview = viewModel::resolveReview,
    )

    // Local to the step, so navigating away never leaves the settings screen
    // opening with a stale pairing dialog.
    var showPairingDialog by rememberSaveable { mutableStateOf(false) }

    if (showPairingDialog) {
        val prefill = state.scanPrefill
        SyncPairingDialog(
            initialServerUrl = prefill?.first ?: state.serverUrl.orEmpty(),
            initialApiKey = prefill?.second ?: state.apiKey.orEmpty(),
            error = state.error,
            busy = status.isPairingBusy(),
            onPair = viewModel::pair,
            onDismiss = {
                viewModel.dismissPairingDialog()
                showPairingDialog = false
            },
            onScanQr = {
                viewModel.dismissPairingDialog()
                showPairingDialog = false
                navigator.push(SyncQrScannerScreen(viewModel::openPairingDialog))
            },
        )
    }
    LaunchedEffect(state.paired) {
        if (state.paired) showPairingDialog = false
    }

    Text(stringResource(MR.strings.onboarding_sync_question))

    if (state.paired) {
        Text(
            stringResource(MR.strings.sync_paired_with, state.serverUrl.orEmpty()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(MR.strings.onboarding_sync_paired))
    } else {
        Text(
            stringResource(MR.strings.onboarding_sync_optional),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !status.isPairingBusy(),
            onClick = { showPairingDialog = true },
        ) {
            Text(stringResource(MR.strings.onboarding_sync_action_setup))
        }
    }
}

const val GETTING_STARTED_URL = "https://mihon.app/docs/guides/getting-started"

@PreviewLightDark
@Composable
private fun GuidesStepPreview() {
    TachiyomiPreviewTheme {
        GuidesStep(
            onRestoreBackup = {},
        ).Content()
    }
}
