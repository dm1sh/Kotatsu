package org.koitharu.kotatsu.download.ui.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.view.View
import androidx.annotation.MainThread
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.BaseService
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.download.domain.DownloadManager
import org.koitharu.kotatsu.download.domain.DownloadState
import org.koitharu.kotatsu.download.ui.DownloadsActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.utils.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.utils.ext.throttle
import org.koitharu.kotatsu.utils.progress.PausingProgressJob
import org.koitharu.kotatsu.utils.progress.ProgressJob
import org.koitharu.kotatsu.utils.progress.TimeLeftEstimator
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.collections.set

@AndroidEntryPoint
class DownloadService : BaseService() {

	private lateinit var downloadNotification: DownloadNotification
	private lateinit var wakeLock: PowerManager.WakeLock

	@Inject
	lateinit var downloadManager: DownloadManager

	private val jobs = LinkedHashMap<Int, PausingProgressJob<DownloadState>>()
	private val jobCount = MutableStateFlow(0)
	private val controlReceiver = ControlReceiver()

	override fun onCreate() {
		super.onCreate()
		isRunning = true
		downloadNotification = DownloadNotification(this)
		wakeLock = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
			.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kotatsu:downloading")
		wakeLock.acquire(TimeUnit.HOURS.toMillis(8))
		DownloadNotification.createChannel(this)
		startForeground(DownloadNotification.ID_GROUP, downloadNotification.buildGroupNotification())
		val intentFilter = IntentFilter()
		intentFilter.addAction(ACTION_DOWNLOAD_CANCEL)
		intentFilter.addAction(ACTION_DOWNLOAD_RESUME)
		ContextCompat.registerReceiver(this, controlReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		val manga = intent?.getParcelableExtraCompat<ParcelableManga>(EXTRA_MANGA)?.manga
		val chapters = intent?.getLongArrayExtra(EXTRA_CHAPTERS_IDS)
		return if (manga != null) {
			jobs[startId] = downloadManga(startId, manga, chapters)
			jobCount.value = jobs.size
			START_REDELIVER_INTENT
		} else {
			stopSelfIfIdle()
			START_NOT_STICKY
		}
	}

	override fun onBind(intent: Intent): IBinder {
		super.onBind(intent)
		return DownloadBinder(this)
	}

	override fun onDestroy() {
		unregisterReceiver(controlReceiver)
		if (wakeLock.isHeld) {
			wakeLock.release()
		}
		isRunning = false
		super.onDestroy()
	}

	private fun downloadManga(
		startId: Int,
		manga: Manga,
		chaptersIds: LongArray?,
	): PausingProgressJob<DownloadState> {
		val job = downloadManager.downloadManga(manga, chaptersIds, startId)
		listenJob(job)
		return job
	}

	private fun listenJob(job: ProgressJob<DownloadState>) {
		lifecycleScope.launch {
			val startId = job.progressValue.startId
			val notificationItem = downloadNotification.newItem(startId)
			try {
				val timeLeftEstimator = TimeLeftEstimator()
				notificationItem.notify(job.progressValue, -1L)
				job.progressAsFlow()
					.onEach { state ->
						if (state is DownloadState.Progress) {
							timeLeftEstimator.tick(value = state.progress, total = state.max)
						} else {
							timeLeftEstimator.emptyTick()
						}
					}
					.throttle { state -> if (state is DownloadState.Progress) 400L else 0L }
					.whileActive()
					.collect { state ->
						val timeLeft = timeLeftEstimator.getEstimatedTimeLeft()
						notificationItem.notify(state, timeLeft)
					}
				job.join()
			} finally {
				(job.progressValue as? DownloadState.Done)?.let {
					sendBroadcast(
						Intent(ACTION_DOWNLOAD_COMPLETE)
							.putExtra(EXTRA_MANGA, ParcelableManga(it.localManga, withChapters = false)),
					)
				}
				if (job.isCancelled) {
					notificationItem.dismiss()
					if (jobs.remove(startId) != null) {
						jobCount.value = jobs.size
					}
				} else {
					notificationItem.notify(job.progressValue, -1L)
				}
			}
		}.invokeOnCompletion {
			stopSelfIfIdle()
		}
	}

	private fun Flow<DownloadState>.whileActive(): Flow<DownloadState> = transformWhile { state ->
		emit(state)
		!state.isTerminal
	}

	@MainThread
	private fun stopSelfIfIdle() {
		if (jobs.any { (_, job) -> job.isActive }) {
			return
		}
		downloadNotification.detach()
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	inner class ControlReceiver : BroadcastReceiver() {

		override fun onReceive(context: Context, intent: Intent?) {
			when (intent?.action) {
				ACTION_DOWNLOAD_CANCEL -> {
					val cancelId = intent.getIntExtra(EXTRA_CANCEL_ID, 0)
					jobs[cancelId]?.cancel()
				}

				ACTION_DOWNLOAD_RESUME -> {
					val cancelId = intent.getIntExtra(EXTRA_CANCEL_ID, 0)
					jobs[cancelId]?.resume()
				}
			}
		}
	}

	class DownloadBinder(service: DownloadService) : Binder(), DefaultLifecycleObserver {

		private var downloadsStateFlow = MutableStateFlow<List<PausingProgressJob<DownloadState>>>(emptyList())

		init {
			service.lifecycle.addObserver(this)
			service.jobCount.onEach {
				downloadsStateFlow.value = service.jobs.values.toList()
			}.launchIn(service.lifecycleScope)
		}

		override fun onDestroy(owner: LifecycleOwner) {
			owner.lifecycle.removeObserver(this)
			downloadsStateFlow.value = emptyList()
			super.onDestroy(owner)
		}

		val downloads
			get() = downloadsStateFlow.asStateFlow()
	}

	companion object {

		var isRunning: Boolean = false
			private set

		@Deprecated("Use LocalMangaRepository.watchReadableDirs instead")
		const val ACTION_DOWNLOAD_COMPLETE = "${BuildConfig.APPLICATION_ID}.action.ACTION_DOWNLOAD_COMPLETE"

		private const val ACTION_DOWNLOAD_CANCEL = "${BuildConfig.APPLICATION_ID}.action.ACTION_DOWNLOAD_CANCEL"
		private const val ACTION_DOWNLOAD_RESUME = "${BuildConfig.APPLICATION_ID}.action.ACTION_DOWNLOAD_RESUME"

		const val EXTRA_MANGA = "manga"
		private const val EXTRA_CHAPTERS_IDS = "chapters_ids"
		private const val EXTRA_CANCEL_ID = "cancel_id"

		fun start(view: View, manga: Manga, chaptersIds: Collection<Long>? = null) {
			if (chaptersIds?.isEmpty() == true) {
				return
			}
			val intent = Intent(view.context, DownloadService::class.java)
			intent.putExtra(EXTRA_MANGA, ParcelableManga(manga, withChapters = false))
			if (chaptersIds != null) {
				intent.putExtra(EXTRA_CHAPTERS_IDS, chaptersIds.toLongArray())
			}
			ContextCompat.startForegroundService(view.context, intent)
			showStartedSnackbar(view)
		}

		fun start(view: View, manga: Collection<Manga>) {
			if (manga.isEmpty()) {
				return
			}
			for (item in manga) {
				val intent = Intent(view.context, DownloadService::class.java)
				intent.putExtra(EXTRA_MANGA, ParcelableManga(item, withChapters = false))
				ContextCompat.startForegroundService(view.context, intent)
			}
			showStartedSnackbar(view)
		}

		fun confirmAndStart(view: View, items: Set<Manga>) {
			MaterialAlertDialogBuilder(view.context)
				.setTitle(R.string.save_manga)
				.setMessage(R.string.batch_manga_save_confirm)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.save) { _, _ ->
					start(view, items)
				}.show()
		}

		fun getCancelIntent(startId: Int) = Intent(ACTION_DOWNLOAD_CANCEL)
			.putExtra(EXTRA_CANCEL_ID, startId)

		fun getResumeIntent(startId: Int) = Intent(ACTION_DOWNLOAD_RESUME)
			.putExtra(EXTRA_CANCEL_ID, startId)

		fun getDownloadedManga(intent: Intent?): Manga? {
			if (intent?.action == ACTION_DOWNLOAD_COMPLETE) {
				return intent.getParcelableExtraCompat<ParcelableManga>(EXTRA_MANGA)?.manga
			}
			return null
		}

		private fun showStartedSnackbar(view: View) {
			Snackbar.make(view, R.string.download_started, Snackbar.LENGTH_LONG)
				.setAction(R.string.details) {
					it.context.startActivity(DownloadsActivity.newIntent(it.context))
				}.show()
		}
	}
}
