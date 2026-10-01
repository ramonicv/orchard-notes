package dev.rortega.orchardnotes.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rortega.orchardnotes.OrchardApplication
import java.util.concurrent.TimeUnit

/** Pushes edits made offline once the device is back online, even if the app was closed. */
class PushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as OrchardApplication).container
        if (container.sessionManager.account == null) {
            container.sessionManager.restore()
        }
        val repository = container.notesRepository
        repository.pushPending()
        return if (repository.syncStatus.value.offline) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "push-pending-edits"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
