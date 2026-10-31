package eu.kanade.tachiyomi.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the sync settings screen and the onboarding sync step: server
 * identity, pairing forms, and the engine status. Pairing itself runs on the
 * app scope through the engine, so rotating the screen or leaving the screen
 * never cancels it.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SyncSettingsViewModel(
    private val engine: SyncEngine,
) : ViewModel() {

    data class State(
        val paired: Boolean = false,
        val configured: Boolean = false,
        val serverUrl: String? = null,
        val apiKey: String? = null,
        val deviceName: String = "",
        val syncPrivate: Boolean = false,
        val error: String? = null,
        val syncing: Boolean = false,
        val showPairingDialog: Boolean = false,
        /** Set when the dialog opens from a QR scan, so both fields prefill. */
        val scanPrefill: Pair<String, String>? = null,
        val showDisconnectDialog: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val status: StateFlow<SyncStatus> = engine.status

    init {
        refresh()
        // Pairing happens outside this view model's scope; follow along as
        // its outcome shows up in the engine status.
        viewModelScope.launch {
            engine.status.collect { refresh() }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val identity = engine.identity()
            _state.value = _state.value.copy(
                paired = identity.paired,
                configured = identity.serverUrl != null,
                serverUrl = identity.serverUrl,
                apiKey = identity.apiKey,
                deviceName = identity.deviceName,
                syncPrivate = identity.syncPrivate,
            )
        }
    }

    fun pair(serverUrl: String, key: String) {
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch {
            // The engine decides whether these credentials name a different
            // library than the one this device already knows: re-entering the
            // same server and key is a reconnect, a different pair is a fresh
            // pairing with its merge and review steps.
            engine.pairWithKey(serverUrl, key) { result ->
                val failure = result.exceptionOrNull()
                _state.value = _state.value.copy(
                    // The engine reports the moment the pairing landed, so a
                    // success closes the form and a failure keeps it open with
                    // the reason beside the fields.
                    error = failure?.let { it.message ?: it.javaClass.simpleName },
                    showPairingDialog = failure != null,
                    scanPrefill = if (failure == null) null else _state.value.scanPrefill,
                )
                refresh()
            }
        }
    }

    fun setDeviceName(name: String) {
        viewModelScope.launch { engine.setDeviceName(name) }
    }

    fun setSyncPrivate(enabled: Boolean) {
        viewModelScope.launch {
            engine.setSyncPrivate(enabled)
            _state.value = _state.value.copy(syncPrivate = enabled)
        }
    }

    /** Runs a catch-up session, exposing its progress to the UI. */
    fun syncNow() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(syncing = true)
            try {
                engine.syncOnce()
            } finally {
                _state.value = _state.value.copy(syncing = false)
            }
        }
    }
    fun disconnect() {
        viewModelScope.launch {
            engine.unpair()
            _state.value = State()
        }
    }

    fun resolveMergeConfirmation(merge: Boolean) = engine.resolveMergeConfirmation(merge)

    fun resolveReview(keep: Boolean) {
        engine.resolvePairing(removeEverywhere = !keep)
    }

    fun openPairingDialog() {
        _state.value = _state.value.copy(showPairingDialog = true, error = null, scanPrefill = null)
    }

    /** Opens the pairing dialog with a scanned QR payload prefilled. */
    fun openPairingDialog(payload: SyncQrPayload) {
        _state.value = _state.value.copy(
            showPairingDialog = true,
            error = null,
            scanPrefill = payload.serverUrl to payload.apiKey,
        )
    }

    fun dismissPairingDialog() {
        engine.cancelPairing()
        _state.value = _state.value.copy(showPairingDialog = false, error = null)
    }

    fun requestDisconnect() {
        _state.value = _state.value.copy(showDisconnectDialog = true)
    }

    fun cancelDisconnect() {
        _state.value = _state.value.copy(showDisconnectDialog = false)
    }
}
