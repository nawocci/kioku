package eu.kanade.tachiyomi.data.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the preference sync policy: secrets, internal state, device-specific and
 * security-sensitive keys must never leave the device.
 */
class SyncPreferencePolicyTest {

    @Test
    fun `ordinary preferences are syncable`() {
        SyncMerger.isSyncablePreference("pref_theme_mode_key") shouldBe true
        SyncMerger.isSyncablePreference("reader_navigation_mode_pager") shouldBe true
        SyncMerger.isSyncablePreference("display_mode_library") shouldBe true
    }

    @Test
    fun `private and app-state preferences are excluded`() {
        // e.g. sync_api_key, sync_device_id, storage_dir, last_version_code
        SyncMerger.isSyncablePreference("__PRIVATE_sync_api_key") shouldBe false
        SyncMerger.isSyncablePreference("__APP_STATE_sync_last_revision") shouldBe false
    }

    @Test
    fun `sync-own preferences are excluded`() {
        SyncMerger.isSyncablePreference("sync_server_url") shouldBe false
        SyncMerger.isSyncablePreference("sync_wifi_only") shouldBe false
    }

    @Test
    fun `category-id preferences are excluded`() {
        SyncMerger.isSyncablePreference("default_category") shouldBe false
        SyncMerger.isSyncablePreference("library_update_categories") shouldBe false
        SyncMerger.isSyncablePreference("download_new_categories") shouldBe false
    }

    @Test
    fun `security preferences are excluded`() {
        SyncMerger.isSyncablePreference("use_biometric_lock") shouldBe false
        SyncMerger.isSyncablePreference("lock_app_after") shouldBe false
        SyncMerger.isSyncablePreference("secure_screen_v2") shouldBe false
    }

    @Test
    fun `device-form-factor preferences are excluded`() {
        SyncMerger.isSyncablePreference("tablet_ui_mode") shouldBe false
        SyncMerger.isSyncablePreference("pref_library_columns_portrait_key") shouldBe false
        SyncMerger.isSyncablePreference("pref_library_columns_landscape_key") shouldBe false
    }

    @Test
    fun `local network preferences are excluded`() {
        SyncMerger.isSyncablePreference("doh_provider") shouldBe false
        SyncMerger.isSyncablePreference("default_user_agent") shouldBe false
    }

    @Test
    fun `denylist entries are not themselves syncable`() {
        SyncMerger.PREFERENCE_DENYLIST.forEach { key ->
            SyncMerger.isSyncablePreference(key) shouldBe false
        }
    }
}
