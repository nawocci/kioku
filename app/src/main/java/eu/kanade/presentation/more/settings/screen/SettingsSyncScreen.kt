package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.sync.SyncApi
import eu.kanade.tachiyomi.data.sync.SyncJob
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.sync.service.SyncPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
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
                    subtitle = null,
                    placeholder = stringResource(MR.strings.pref_sync_api_key_placeholder),
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_sync_now),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PrefsHorizontalPadding),
                            ) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = runCatching { syncApi.authCheck() }.getOrDefault(false)
                                            context.toast(
                                                if (ok) {
                                                    MR.strings.sync_test_success
                                                } else {
                                                    MR.strings.sync_test_failed
                                                },
                                            )
                                        }
                                    },
                                ) {
                                    Text(stringResource(MR.strings.pref_sync_test_connection))
                                }
                                TextButton(
                                    onClick = {
                                        context.toast(MR.strings.syncing_library)
                                        SyncJob.startNow(context)
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
