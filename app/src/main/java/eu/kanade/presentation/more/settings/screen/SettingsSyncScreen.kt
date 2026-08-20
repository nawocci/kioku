package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.cash.sqldelight.async.coroutines.awaitAsList
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.sync.SyncApi
import eu.kanade.tachiyomi.data.sync.SyncChangeSetDto
import eu.kanade.tachiyomi.data.sync.SyncExtensionResolver
import eu.kanade.tachiyomi.data.sync.SyncJob
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.SyncMerger
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tachiyomi.data.Database
import tachiyomi.domain.sync.service.SyncPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsSyncScreen : SearchableSettings {

    private val missingExtensionsState = MutableStateFlow<List<Extension.Available>?>(null)

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_sync

    @Composable
    override fun RowScope.AppBarAction() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val syncPreferences = remember { Injekt.get<SyncPreferences>() }
        val syncApi = remember { Injekt.get<SyncApi>() }

        IconButton(
            onClick = {
                runCatching {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .enableAutoZoom()
                        .build()
                    val scanner = GmsBarcodeScanning.getClient(context, options)

                    scanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val raw = barcode.rawValue?.trim() ?: return@addOnSuccessListener
                            val parts = raw.split("|")
                            if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                                val serverUrl = parts[0].trim().trimEnd('/')
                                val apiKey = parts[1].trim()
                                syncPreferences.syncServerUrl.set(serverUrl)
                                syncPreferences.syncApiKey.set(apiKey)

                                scope.launch {
                                    context.toast(MR.strings.sync_qr_scanned_success)
                                    triggerConnectAndSync(
                                        context = context,
                                        syncApi = syncApi,
                                        syncPreferences = syncPreferences,
                                        onShowMissing = { missing ->
                                            missingExtensionsState.value = missing
                                        },
                                    )
                                }
                            } else {
                                context.toast(MR.strings.sync_qr_invalid)
                            }
                        }
                        .addOnFailureListener { e ->
                            if (e !is ApiException || e.statusCode != CommonStatusCodes.CANCELED) {
                                context.toast(MR.strings.sync_qr_unavailable)
                            }
                        }
                }.onFailure {
                    context.toast(MR.strings.sync_qr_unavailable)
                }
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = stringResource(MR.strings.action_scan_qr),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val syncPreferences = Injekt.get<SyncPreferences>()
        return listOf(
            getServerGroup(syncPreferences),
            getBehaviorGroup(syncPreferences),
            getAdvancedGroup(syncPreferences),
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

        val missingExtensions by missingExtensionsState.collectAsState()
        missingExtensions?.let { extensions ->
            SyncExtensionDialog(
                extensions = extensions,
                onInstallAll = {
                    val resolver = Injekt.get<SyncExtensionResolver>()
                    missingExtensionsState.value = null
                    resolver.installExtensions(extensions) {
                        eu.kanade.tachiyomi.data.library.MetadataUpdateJob.startNow(context)
                        SyncJob.startNow(context)
                    }
                },
                onDismiss = {
                    missingExtensionsState.value = null
                    SyncJob.startNow(context)
                },
            )
        }

        // Show a masked preview of the API key; never the full value.
        val maskedApiKey = if (apiKey.isBlank()) null else apiKey.take(8) + "*".repeat(8)

        // True while a manual/pull sync is enqueued or running.
        val manualWork by context.workManager
            .getWorkInfosForUniqueWorkFlow(SyncJob.TAG_MANUAL)
            .collectAsState(initial = emptyList())
        val syncing = manualWork.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
        // True while a connect/test request is in flight.
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
                    isSensitive = true,
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
                                            triggerConnectAndSync(
                                                context = context,
                                                syncApi = syncApi,
                                                syncPreferences = syncPreferences,
                                                onShowMissing = { missing ->
                                                    missingExtensionsState.value = missing
                                                },
                                            )
                                            testing = false
                                        }
                                    },
                                    enabled = !busy && configured,
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
                                    val buttonLabel = if (lastSyncTimestamp > 0) {
                                        MR.strings.pref_sync_test_connection
                                    } else {
                                        MR.strings.pref_sync_connect_sync
                                    }
                                    Text(stringResource(buttonLabel))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        scope.launch {
                                            triggerConnectAndSync(
                                                context = context,
                                                syncApi = syncApi,
                                                syncPreferences = syncPreferences,
                                                onShowMissing = { missing ->
                                                    missingExtensionsState.value = missing
                                                },
                                            )
                                        }
                                    },
                                    enabled = !busy && configured,
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

    private suspend fun triggerConnectAndSync(
        context: android.content.Context,
        syncApi: SyncApi,
        syncPreferences: SyncPreferences,
        onShowMissing: (List<Extension.Available>) -> Unit,
    ) {
        val ok = runCatching { syncApi.authCheck() }.getOrDefault(false)
        if (!ok) {
            context.toast(MR.strings.sync_test_failed)
            return
        }

        val watermark = syncPreferences.lastSyncRevision.get()
        val pull = if (watermark == 0L) runCatching { syncApi.pull(0L) }.getOrNull() else null
        if (pull != null && pull.changes.extensionStores.isNotEmpty()) {
            val merger = SyncMerger()
            merger.apply(SyncChangeSetDto(extensionStores = pull.changes.extensionStores))
        }

        val remoteSourceIds = pull?.changes?.mangas?.map { it.sourceId }?.toSet() ?: emptySet()
        val localSourceIds = runCatching {
            Injekt.get<tachiyomi.data.Database>().mangasQueries.getFavorites().awaitAsList().map { it.source }.toSet()
        }.getOrDefault(emptySet())

        val allSourceIds = remoteSourceIds + localSourceIds
        if (allSourceIds.isNotEmpty()) {
            val resolver = Injekt.get<SyncExtensionResolver>()
            val missing = resolver.findMissingExtensions(allSourceIds)
            if (missing.isNotEmpty()) {
                onShowMissing(missing)
                return
            }
        }

        if (watermark > 0L) {
            context.toast(MR.strings.sync_test_success)
        }
        SyncJob.startNow(context)
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

    @Composable
    private fun getAdvancedGroup(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_advanced),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_sync_reset),
                    subtitle = stringResource(MR.strings.pref_sync_reset_summary),
                    enabled = syncPreferences.isConfigured(),
                    onClick = {
                        scope.launch {
                            SyncManager(context).resetSync()
                            SyncJob.startNow(context)
                            context.toast(MR.strings.sync_reset_started)
                        }
                    },
                ),
            ),
        )
    }
}
