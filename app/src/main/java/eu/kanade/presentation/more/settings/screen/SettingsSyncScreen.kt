package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.more.sync.SyncPairingDialog
import eu.kanade.presentation.more.sync.SyncQrScannerScreen
import eu.kanade.presentation.more.sync.SyncStatusDialogs
import eu.kanade.presentation.more.sync.isPairingBusy
import eu.kanade.presentation.more.sync.syncStatusText
import eu.kanade.tachiyomi.sync.SyncSettingsViewModel
import eu.kanade.tachiyomi.sync.SyncStatus
import mihon.icons.custommaterialsymbols.CustomMaterialSymbols
import mihon.icons.custommaterialsymbols.rounded.CloudSync
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CloudOff
import mihon.icons.materialsymbols.rounded.Error
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsSyncScreen : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.sync_settings_title

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<SyncSettingsViewModel>()
        val state by viewModel.state.collectAsState()
        val status by viewModel.status.collectAsState()

        SyncStatusDialogs(
            status = status,
            onResolveMerge = viewModel::resolveMergeConfirmation,
            onResolveReview = viewModel::resolveReview,
        )

        if (state.showPairingDialog) {
            // A scan prefill overrides the stored values until pairing.
            val prefill = state.scanPrefill
            SyncPairingDialog(
                initialServerUrl = prefill?.first ?: state.serverUrl.orEmpty(),
                initialApiKey = prefill?.second ?: state.apiKey.orEmpty(),
                error = state.error,
                busy = status.isPairingBusy(),
                onPair = viewModel::pair,
                onDismiss = viewModel::dismissPairingDialog,
                onScanQr = {
                    viewModel.dismissPairingDialog()
                    navigator.push(SyncQrScannerScreen(viewModel::openPairingDialog))
                },
            )
        }

        if (state.showDisconnectDialog) {
            AlertDialog(
                onDismissRequest = viewModel::cancelDisconnect,
                title = { Text(stringResource(MR.strings.sync_disconnect)) },
                text = { Text(stringResource(MR.strings.sync_disconnect_confirm)) },
                confirmButton = {
                    TextButton(onClick = viewModel::disconnect) {
                        Text(stringResource(MR.strings.sync_disconnect))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelDisconnect) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        super<SearchableSettings>.Content()
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val viewModel = metroViewModel<SyncSettingsViewModel>()
        val state by viewModel.state.collectAsState()
        val status by viewModel.status.collectAsState()

        val statusText = syncStatusText(status)
        // Without a pairing there is nothing to report beyond an in-flight or
        // failed attempt; the pairing help text covers the idle case.
        val showStatus = statusText != null && (state.paired || status !is SyncStatus.Idle)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.sync_group_server),
                preferenceItems = buildList {
                    if (showStatus) {
                        add(statusPreference(status, statusText!!, state))
                    }

                    if (state.paired) {
                        // Manual actions, as the pill pair on Data and storage.
                        add(
                            Preference.PreferenceItem.CustomPreference(
                                title = stringResource(MR.strings.sync_now),
                            ) {
                                BasePreferenceWidget(
                                    subcomponent = {
                                        MultiChoiceSegmentedButtonRow(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(intrinsicSize = IntrinsicSize.Min)
                                                .padding(horizontal = PrefsHorizontalPadding),
                                        ) {
                                            SegmentedButton(
                                                modifier = Modifier.fillMaxHeight(),
                                                enabled = !state.syncing,
                                                checked = false,
                                                onCheckedChange = { viewModel.syncNow() },
                                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                            ) {
                                                if (state.syncing) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(18.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                } else {
                                                    Text(stringResource(MR.strings.sync_now))
                                                }
                                            }
                                            SegmentedButton(
                                                modifier = Modifier.fillMaxHeight(),
                                                checked = false,
                                                onCheckedChange = { viewModel.requestDisconnect() },
                                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                            ) {
                                                Text(stringResource(MR.strings.sync_disconnect))
                                            }
                                        }
                                    },
                                )
                            },
                        )
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(MR.strings.sync_server_url),
                                subtitle = state.serverUrl,
                                onClick = viewModel::openPairingDialog,
                            ),
                        )
                    } else {
                        add(
                            Preference.PreferenceItem.InfoPreference(
                                stringResource(MR.strings.sync_not_paired),
                            ),
                        )
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(MR.strings.sync_pair_with_server),
                                subtitle = state.serverUrl,
                                onClick = viewModel::openPairingDialog,
                            ),
                        )
                    }
                },
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.sync_group_device),
                preferenceItems = listOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.sync_device_name),
                    ) {
                        EditTextPreferenceWidget(
                            title = stringResource(MR.strings.sync_device_name),
                            subtitle = "%s",
                            icon = null,
                            value = state.deviceName,
                            onConfirm = { name ->
                                viewModel.setDeviceName(name)
                                true
                            },
                        )
                    },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.sync_group_data),
                preferenceItems = listOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.sync_private_settings),
                    ) {
                        SwitchPreferenceWidget(
                            title = stringResource(MR.strings.sync_private_settings),
                            subtitle = stringResource(MR.strings.sync_private_settings_summary),
                            checked = state.syncPrivate,
                            onCheckedChanged = viewModel::setSyncPrivate,
                        )
                    },
                ),
            ),
        )
    }

    /**
     * The status row: the state the engine is in right now, with the server
     * identity and the diagnostic detail for failures alongside.
     */
    @Composable
    private fun statusPreference(
        status: SyncStatus,
        statusText: Pair<String, String?>,
        state: SyncSettingsViewModel.State,
    ): Preference.PreferenceItem.CustomPreference {
        val (title, message) = statusText
        // Only the diagnostic detail (last activity, error message); the
        // server address row below already shows who we're paired with.
        val subtitle = message

        val icon: ImageVector = when (status) {
            is SyncStatus.Error -> MaterialSymbols.Rounded.Error
            SyncStatus.Idle -> MaterialSymbols.Rounded.CloudOff
            else -> CustomMaterialSymbols.Rounded.CloudSync
        }
        val iconTint: Color = when (status) {
            is SyncStatus.Error -> MaterialTheme.colorScheme.error
            is SyncStatus.Online -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        return Preference.PreferenceItem.CustomPreference(
            // A dynamic status line has no place in settings search.
            title = "",
        ) {
            TextPreferenceWidget(
                title = title,
                subtitle = subtitle,
                icon = icon,
                iconTint = iconTint,
            )
        }
    }
}
