package eu.kanade.tachiyomi.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.system.workManager
import mihon.app.di.appGraph
import java.util.concurrent.TimeUnit

/**
 * Background catch-up: connects, exchanges whatever piled up, and hangs up.
 * The foreground loop covers most of the day; this keeps a device that has
 * not been opened in a while from falling far behind. It never runs before
 * pairing completed — the initial snapshot belongs to the interactive flow
 * with its confirmations, not to a background window.
 */
@Inject
class SyncWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val engine get() = context.appGraph.syncEngine

    override suspend fun doWork(): Result = try {
        engine.syncOnce()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val WORK_NAME = "kioku-sync-catchup"

        /** Schedules the periodic catch-up, replacing any previous schedule. */
        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            context.workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
