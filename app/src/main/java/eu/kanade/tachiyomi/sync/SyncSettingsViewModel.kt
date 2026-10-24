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

    /**
     * A pairing started from the form is in flight. The engine marks itself
     * paired before it sends this device's library up, so `paired` alone does
     * not mean the attempt landed: only a healthy status after this flag is set
     * closes the form, and any failure reopens it with the reason.
     */
    @Volatile
    private var pairingAttempt = false

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
            val paired = engine.isPaired()
            // The engine marks itself paired before it sends this device's
            // library up, so `paired` alone does not mean the attempt landed.
            // A failure after that point must not shut the form and hide the
            // reason with it.
            val completed = pairingAttempt && paired && status.value !is SyncStatus.Error
            if (completed) pairingAttempt = false
            _state.value = _state.value.copy(
                paired = paired,
                configured = engine.serverUrl() != null,
                serverUrl = engine.serverUrl(),
                apiKey = engine.apiKey(),
                deviceName = engine.deviceName(),
                syncPrivate = engine.syncPrivate(),
                // A completed pairing closes the form; a failed one keeps it
                // open with the error attached.
                showPairingDialog = if (completed) false else _state.value.showPairingDialog,
            )
        }
    }

    fun pair(serverUrl: String, key: String) {
        pairingAttempt = true
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch {
            // Pairing over an existing pairing means the server changed: go
            // through a fresh pairing, so the snapshot merge and review
            // confirmations apply to the new server rather than being
            // skipped by the already-paired path.
            if (engine.isPaired()) engine.unpair()
            engine.pairWithKey(serverUrl, key) { result ->
                val failure = result.exceptionOrNull()
                pairingAttempt = false
                _state.value = _state.value.copy(
                    // A failure reopens the form with the reason attached: it
                    // can arrive after the engine already marked the pairing
                    // complete, so a form closed on `paired` alone would hide
                    // the error. A pairing that landed is closed by refresh().
                    error = failure?.let { it.message ?: it.javaClass.simpleName },
                    showPairingDialog = failure != null || _state.value.showPairingDialog,
                    scanPrefill = if (failure == null) null else _state.value.scanPrefill,
                )
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
        pairingAttempt = false
        _state.value = _state.value.copy(showPairingDialog = true, error = null, scanPrefill = null)
    }

    /** Opens the pairing dialog with a scanned QR payload prefilled. */
    fun openPairingDialog(payload: SyncQrPayload) {
        pairingAttempt = false
        _state.value = _state.value.copy(
            showPairingDialog = true,
            error = null,
            scanPrefill = payload.serverUrl to payload.apiKey,
        )
    }

    fun dismissPairingDialog() {
        pairingAttempt = false
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
