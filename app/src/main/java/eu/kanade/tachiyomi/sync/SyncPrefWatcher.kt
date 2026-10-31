package eu.kanade.tachiyomi.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import xyz.altaf.kioku.sync.RecordTypes

/**
 * Watches the preference files and turns changes into outbox rows. Database
 * writes are captured by triggers; preferences live outside the database, so
 * they get this listener instead.
 *
 * Echoes of remote applies are dropped later at drain time by comparing the
 * value with the one last pushed or applied, because SharedPreferences
 * listeners fire asynchronously and cannot be scoped to the write.
 */
@Inject
class SyncPrefWatcher(
    context: Context,
    private val database: Database,
    @AppCoroutineScope private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val registered = mutableMapOf<SharedPreferences, String?>()

    /**
     * Registers the app preferences and the preferences of the given sources,
     * replacing any earlier registration.
     */
    fun start(sourceKeys: List<String>) {
        stop()
        register(PreferenceManager.getDefaultSharedPreferences(appContext), null)
        sourceKeys.forEach { key ->
            register(appContext.getSharedPreferences(key, Context.MODE_PRIVATE), key)
        }
    }

    fun stop() {
        registered.keys.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        registered.clear()
    }

    private fun register(prefs: SharedPreferences, source: String?) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        registered[prefs] = source
    }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        // The app store is registered with a null source, so membership is
        // checked before the lookup; a null value is not "not registered".
        if (prefs !in registered) return@OnSharedPreferenceChangeListener
        // App state (last version seen, migration flags, …) is device-local by
        // definition and never syncs; dropping it here keeps it out of the
        // outbox instead of enqueueing a row the drain would only retire.
        if (Preference.isAppState(key)) return@OnSharedPreferenceChangeListener
        val source = registered.getValue(prefs)
        scope.launch {
            if (source == null) {
                database.syncQueries.enqueueOutbox(
                    RecordTypes.PREFERENCE.toLong(),
                    null,
                    null,
                    key,
                    null,
                )
            } else {
                database.syncQueries.enqueueOutbox(
                    RecordTypes.SOURCE_PREFERENCE.toLong(),
                    null,
                    null,
                    key,
                    source,
                )
            }
        }
    }
}
