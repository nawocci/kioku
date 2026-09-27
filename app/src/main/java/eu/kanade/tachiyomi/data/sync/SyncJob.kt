package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
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
 *
 * Failures are retried with backoff (bounded by [MAX_RETRIES]) instead of being
 * dropped, so a transient network error doesn't strand the outbox until the next
 * local change.
 */
class SyncJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // Use the shared singleton so its mutex serialises concurrent workers.
        val manager = Injekt.get<SyncManager>()
        val isPull = inputData.getBoolean(KEY_PULL, false)
        val outcome = if (isPull) manager.pullNow() else manager.syncNow()
        return when (outcome) {
            SyncManager.Outcome.SUCCESS,
            SyncManager.Outcome.BUSY,
            SyncManager.Outcome.NOT_CONFIGURED,
            -> Result.success()

            SyncManager.Outcome.FAILED ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val DEBOUNCE_SECONDS = 15L
        private const val MAX_RETRIES = 5

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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
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
