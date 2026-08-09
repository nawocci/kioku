package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.domain.sync.service.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Runs a sync cycle on demand. Two unique tags:
 * - [TAG_DEBOUNCED]: scheduled a short delay after a local change; a
 *   KEEP policy means a busy reading session syncs at most once per window.
 * - [TAG_MANUAL]: immediate, for the "Sync now" button and app-start pull.
 */
class SyncJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val manager = SyncManager(applicationContext)
        val isPull = inputData.getBoolean(KEY_PULL, false)
        val ok = if (isPull) manager.pullNow() else manager.syncNow()
        return if (ok) Result.success() else Result.failure()
    }

    companion object {
        const val DEBOUNCE_SECONDS = 15L

        /** Unique work name for manual/pull syncs; observed by the settings UI for progress. */
        internal const val TAG_MANUAL = "SyncJob:manual"

        private const val TAG_DEBOUNCED = "SyncJob"
        private const val KEY_PULL = "pull"

        fun startDebounced(context: Context) {
            val preferences = Injekt.get<SyncPreferences>()
            if (!preferences.isConfigured() || !preferences.syncOnChangeEnabled.get()) return

            val request = OneTimeWorkRequestBuilder<SyncJob>()
                .addTag(TAG_DEBOUNCED)
                .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .setConstraints(buildConstraints(preferences.syncWifiOnly.get()))
                .build()
            context.workManager.enqueueUniqueWork(TAG_DEBOUNCED, ExistingWorkPolicy.KEEP, request)
        }

        fun startNow(context: Context) {
            val preferences = Injekt.get<SyncPreferences>()
            if (!preferences.isConfigured()) return

            val request = OneTimeWorkRequestBuilder<SyncJob>()
                .addTag(TAG_MANUAL)
                .setConstraints(buildConstraints(preferences.syncWifiOnly.get()))
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        /** Pull-only variant used on app start. */
        fun startPull(context: Context) {
            val preferences = Injekt.get<SyncPreferences>()
            if (!preferences.isConfigured()) return

            val request = OneTimeWorkRequestBuilder<SyncJob>()
                .addTag(TAG_MANUAL)
                .setInputData(workDataOf(KEY_PULL to true))
                .setConstraints(buildConstraints(preferences.syncWifiOnly.get()))
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.REPLACE, request)
        }

        private fun buildConstraints(wifiOnly: Boolean): Constraints {
            return Constraints(
                requiredNetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
        }
    }
}
