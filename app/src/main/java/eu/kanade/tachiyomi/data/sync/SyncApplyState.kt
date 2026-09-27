package eu.kanade.tachiyomi.data.sync

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-component state shared by the sync engine.
 *
 * [applying] marks that a remote change set is currently being applied to the
 * local database. SQL-level echo suppression is handled by the
 * `sync_state.applying` row read by the change-tracking triggers; preferences,
 * however, live in SharedPreferences and are observed by [SyncObserver] via a
 * listener, so the observer needs this in-process signal to avoid re-enqueueing
 * preferences written by [SyncMerger].
 *
 * [applyMutex] serialises [SyncMerger.apply] across all callers so that two
 * concurrent applies can't clear the suppression flag while the other is still
 * writing (which would let triggers echo changes back to the server).
 */
internal object SyncApplyState {
    val applying = AtomicBoolean(false)

    val applyMutex = Mutex()
}
