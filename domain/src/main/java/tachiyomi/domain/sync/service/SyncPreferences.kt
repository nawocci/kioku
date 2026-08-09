package tachiyomi.domain.sync.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SyncPreferences(
    preferenceStore: PreferenceStore,
) {

    val syncServerUrl: Preference<String> = preferenceStore.getString("sync_server_url", "")

    val syncApiKey: Preference<String> = preferenceStore.getString(Preference.privateKey("sync_api_key"), "")

    val syncDeviceId: Preference<String> = preferenceStore.getString(Preference.privateKey("sync_device_id"), "")

    val syncWifiOnly: Preference<Boolean> = preferenceStore.getBoolean("sync_wifi_only", true)

    val syncOnChangeEnabled: Preference<Boolean> = preferenceStore.getBoolean("sync_on_change", true)

    val lastSyncRevision: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("sync_last_revision"),
        0L,
    )

    val lastSyncTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("sync_last_timestamp"),
        0L,
    )

    val lastSyncError: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("sync_last_error"),
        "",
    )

    fun isConfigured(): Boolean {
        return syncServerUrl.get().isNotBlank() && syncApiKey.get().isNotBlank()
    }
}
