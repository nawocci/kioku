package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.sync.SyncApi
import eu.kanade.tachiyomi.data.sync.SyncJob
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.launch
import tachiyomi.domain.sync.service.SyncPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsSyncScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_sync

    @Composable
    override fun getPreferences(): List<Preference> {
        val syncPreferences = Injekt.get<SyncPreferences>()
        return listOf(
            getServerGroup(syncPreferences),
            getBehaviorGroup(syncPreferences),
        )
    }

    @Composable
    private fun getServerGroup(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val syncApi = Injekt.get<SyncApi>()

        val serverUrl by syncPreferences.syncServerUrl.collectAsState()
        val apiKey by syncPreferences.syncApiKey.collectAsState()
        val lastSyncTimestamp by syncPreferences.lastSyncTimestamp.collectAsState()
        val lastSyncError by syncPreferences.lastSyncError.collectAsState()
        val configured = serverUrl.isNotBlank() && apiKey.isNotBlank()

        // Show a masked preview of the API key; never the full value.
        val maskedApiKey = if (apiKey.isBlank()) null else apiKey.take(8) + "*".repeat(8)

        // True while a manual/pull sync is enqueued or running.
        val manualWork by context.workManager
            .getWorkInfosForUniqueWorkFlow(SyncJob.TAG_MANUAL)
            .collectAsState(initial = emptyList())
        val syncing = manualWork.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
        // True while a "test connection" request is in flight.
        var testing by remember { mutableStateOf(false) }
        val busy = testing || syncing

        // Toast the outcome when a sync run finishes, mirroring the test connection feedback.
        var wasSyncing by remember { mutableStateOf(false) }
        LaunchedEffect(syncing) {
            if (wasSyncing && !syncing) {
                context.toast(
                    if (syncPreferences.lastSyncError.get().isBlank()) {
                        MR.strings.sync_success
                    } else {
                        MR.strings.sync_failed
                    },
                )
            }
            wasSyncing = syncing
        }

        val statusText = when {
            !configured -> stringResource(MR.strings.sync_status_not_configured)
            lastSyncError.isNotBlank() ->
                "${stringResource(MR.strings.sync_status_error)}: $lastSyncError"
            lastSyncTimestamp > 0 -> stringResource(MR.strings.sync_status_connected)
            else -> stringResource(MR.strings.sync_status_not_configured)
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_sync),
            preferenceItems = listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = syncPreferences.syncServerUrl,
                    title = stringResource(MR.strings.pref_sync_server_url),
                    placeholder = stringResource(MR.strings.pref_sync_server_url_placeholder),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = syncPreferences.syncApiKey,
                    title = stringResource(MR.strings.pref_sync_api_key),
                    subtitle = maskedApiKey,
                    placeholder = stringResource(MR.strings.pref_sync_api_key_placeholder),
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_sync_now),
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
                                    checked = false,
                                    onCheckedChange = {
                                        scope.launch {
                                            testing = true
                                            val ok = runCatching { syncApi.authCheck() }
                                                .getOrDefault(false)
                                            testing = false
                                            context.toast(
                                                if (ok) {
                                                    MR.strings.sync_test_success
                                                } else {
                                                    MR.strings.sync_test_failed
                                                },
                                            )
                                        }
                                    },
                                    enabled = !busy,
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                    icon = {
                                        if (testing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            SegmentedButtonDefaults.Icon(active = false)
                                        }
                                    },
                                ) {
                                    Text(stringResource(MR.strings.pref_sync_test_connection))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = { SyncJob.startNow(context) },
                                    enabled = !busy,
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                    icon = {
                                        if (syncing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            SegmentedButtonDefaults.Icon(active = false)
                                        }
                                    },
                                ) {
                                    Text(stringResource(MR.strings.pref_sync_now))
                                }
                            }
                        },
                    )
                },
                Preference.PreferenceItem.InfoPreference(title = statusText),
            ),
        )
    }

    @Composable
    private fun getBehaviorGroup(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.action_settings),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = syncPreferences.syncOnChangeEnabled,
                    title = stringResource(MR.strings.pref_sync_on_change),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = syncPreferences.syncWifiOnly,
                    title = stringResource(MR.strings.pref_sync_wifi_only),
                ),
            ),
        )
    }
}
