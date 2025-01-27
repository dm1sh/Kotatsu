package org.koitharu.kotatsu.local.ui

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.local.domain.LocalMangaRepository
import java.util.concurrent.TimeUnit

@HiltWorker
class LocalStorageCleanupWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val localMangaRepository: LocalMangaRepository,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result {
		return if (localMangaRepository.cleanup()) {
			Result.success()
		} else {
			Result.retry()
		}
	}

	companion object {

		private const val TAG = "cleanup"

		fun enqueue(context: Context) {
			val constraints = Constraints.Builder()
				.setRequiresBatteryNotLow(true)
				.build()
			val request = OneTimeWorkRequestBuilder<ImportWorker>()
				.setConstraints(constraints)
				.addTag(TAG)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
		}
	}
}
