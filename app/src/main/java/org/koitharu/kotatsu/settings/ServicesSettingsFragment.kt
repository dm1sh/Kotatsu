package org.koitharu.kotatsu.settings

import android.accounts.AccountManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.config.ScrobblerConfigActivity
import org.koitharu.kotatsu.scrobbling.mal.data.MALRepository
import org.koitharu.kotatsu.scrobbling.shikimori.data.ShikimoriRepository
import org.koitharu.kotatsu.sync.domain.SyncController
import org.koitharu.kotatsu.sync.ui.SyncSettingsIntent
import org.koitharu.kotatsu.utils.ext.getDisplayMessage
import org.koitharu.kotatsu.utils.ext.printStackTraceDebug
import org.koitharu.kotatsu.utils.ext.viewLifecycleScope
import javax.inject.Inject

@AndroidEntryPoint
class ServicesSettingsFragment : BasePreferenceFragment(R.string.services) {

	@Inject
	lateinit var shikimoriRepository: ShikimoriRepository

	@Inject
	lateinit var aniListRepository: AniListRepository

	@Inject
	lateinit var malRepository: MALRepository

	@Inject
	lateinit var syncController: SyncController

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_services)
	}

	override fun onResume() {
		super.onResume()
		bindScrobblerSummary(AppSettings.KEY_SHIKIMORI, shikimoriRepository)
		bindScrobblerSummary(AppSettings.KEY_ANILIST, aniListRepository)
		bindScrobblerSummary(AppSettings.KEY_MAL, malRepository)
		bindSyncSummary()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_SHIKIMORI -> {
				if (!shikimoriRepository.isAuthorized) {
					launchScrobblerAuth(shikimoriRepository)
				} else {
					startActivity(ScrobblerConfigActivity.newIntent(preference.context, ScrobblerService.SHIKIMORI))
				}
				true
			}

			AppSettings.KEY_MAL -> {
				if (!malRepository.isAuthorized) {
					launchScrobblerAuth(malRepository)
				} else {
					startActivity(ScrobblerConfigActivity.newIntent(preference.context, ScrobblerService.MAL))
				}
				true
			}

			AppSettings.KEY_ANILIST -> {
				if (!aniListRepository.isAuthorized) {
					launchScrobblerAuth(aniListRepository)
				} else {
					startActivity(ScrobblerConfigActivity.newIntent(preference.context, ScrobblerService.ANILIST))
				}
				true
			}

			AppSettings.KEY_SYNC -> {
				val am = AccountManager.get(requireContext())
				val accountType = getString(R.string.account_type_sync)
				val account = am.getAccountsByType(accountType).firstOrNull()
				if (account == null) {
					am.addAccount(accountType, accountType, null, null, requireActivity(), null, null)
				} else {
					try {
						startActivity(SyncSettingsIntent(account))
					} catch (_: ActivityNotFoundException) {
						Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
					}
				}
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun bindScrobblerSummary(
		key: String,
		repository: org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
	) {
		val pref = findPreference<Preference>(key) ?: return
		if (!repository.isAuthorized) {
			pref.setSummary(R.string.disabled)
			return
		}
		val username = repository.cachedUser?.nickname
		if (username != null) {
			pref.summary = getString(R.string.logged_in_as, username)
		} else {
			pref.setSummary(R.string.loading_)
			viewLifecycleScope.launch {
				pref.summary = withContext(Dispatchers.Default) {
					runCatching {
						val user = repository.loadUser()
						getString(R.string.logged_in_as, user.nickname)
					}.getOrElse {
						it.printStackTraceDebug()
						it.getDisplayMessage(resources)
					}
				}
			}
		}
	}

	private fun launchScrobblerAuth(repository: org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository) {
		runCatching {
			val intent = Intent(Intent.ACTION_VIEW)
			intent.data = Uri.parse(repository.oauthUrl)
			startActivity(intent)
		}.onFailure {
			Snackbar.make(listView, it.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
		}
	}

	private fun bindSyncSummary() {
		viewLifecycleScope.launch {
			val account = withContext(Dispatchers.Default) {
				val type = getString(R.string.account_type_sync)
				AccountManager.get(requireContext()).getAccountsByType(type).firstOrNull()
			}
			findPreference<Preference>(AppSettings.KEY_SYNC)?.run {
				summary = when {
					account == null -> getString(R.string.sync_title)
					syncController.isEnabled(account) -> account.name
					else -> getString(R.string.disabled)
				}
			}
		}
	}
}
