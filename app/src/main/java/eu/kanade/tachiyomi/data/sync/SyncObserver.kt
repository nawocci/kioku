package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOne
import tachiyomi.domain.sync.service.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges local changes to the sync outbox/scheduler:
 * - the outbox count (fed by SQL triggers) is observed and a debounced sync
 *   is scheduled whenever it grows;
 * - preference changes (which never touch SQLite) are enqueued via a
 *   SharedPreferences listener;
 * - foreign changes are pulled on app start.
 *
 * Started once from [eu.kanade.tachiyomi.App] via an idle handler, so it never
 * touches the database during the startup window (extension loading).
 */
class SyncObserver(
    private val context: Context,
    private val database: Database = Injekt.get(),
    private val preferences: SyncPreferences = Injekt.get(),
) {

    /**
     * Held as a field because [SharedPreferences] keeps listeners in a
     * [java.util.WeakHashMap]; an inline lambda would be collected and stop firing.
     */
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun init(scope: CoroutineScope) {
        scope.launch {
            // Recover from a crash mid-apply: clear the applying flag (which would
            // otherwise mute the change-tracking triggers forever) and any rows left
            // marked is_syncing (which would suppress their version bumps).
            database.sync_changesQueries.setApplying(0L)
            database.transaction {
                database.mangasQueries.resetIsSyncing()
                database.chaptersQueries.resetIsSyncing()
            }
        }

        database.sync_changesQueries.count()
            .subscribeToOne()
            .onEach { count ->
                if (count > 0L && preferences.isConfigured()) {
                    SyncJob.startDebounced(context)
                }
            }
            .launchIn(scope)

        registerPreferenceListener(scope)

        if (preferences.isConfigured()) {
            SyncJob.startPull(context)
        }
    }

    private fun registerPreferenceListener(scope: CoroutineScope) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key: String? ->
            if (key == null) return@OnSharedPreferenceChangeListener
            // Ignore preferences written while applying remote changes.
            if (SyncApplyState.applying.get()) return@OnSharedPreferenceChangeListener
            if (!SyncMerger.isSyncablePreference(key)) return@OnSharedPreferenceChangeListener
            // Preference removals surface as a change event whose value is gone.
            val exists = sharedPreferences.contains(key)
            val changeType = if (exists) "upsert" else "delete"
            scope.launch {
                database.sync_changesQueries.enqueuePreference(changeType, key)
            }
        }
        preferenceListener = listener
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private val started = AtomicBoolean(false)

        fun start(context: Context, scope: CoroutineScope) {
            if (started.compareAndSet(false, true)) {
                SyncObserver(context).init(scope)
            }
        }
    }
}
