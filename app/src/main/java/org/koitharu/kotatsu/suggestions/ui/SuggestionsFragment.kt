package org.koitharu.kotatsu.suggestions.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.list.ListSelectionController
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.utils.ext.addMenuProvider

class SuggestionsFragment : MangaListFragment() {

	override val viewModel by viewModels<SuggestionsViewModel>()
	override val isSwipeRefreshEnabled = false

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		addMenuProvider(SuggestionMenuProvider())
	}

	override fun onScrolledToEnd() = Unit

	override fun onCreateActionMode(controller: ListSelectionController, mode: ActionMode, menu: Menu): Boolean {
		mode.menuInflater.inflate(R.menu.mode_remote, menu)
		return super.onCreateActionMode(controller, mode, menu)
	}

	private inner class SuggestionMenuProvider : MenuProvider {

		override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
			menuInflater.inflate(R.menu.opt_suggestions, menu)
		}

		override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
			R.id.action_update -> {
				SuggestionsWorker.startNow(requireContext())
				Snackbar.make(
					binding.recyclerView,
					R.string.feed_will_update_soon,
					Snackbar.LENGTH_LONG,
				).show()
				true
			}
			R.id.action_settings -> {
				startActivity(SettingsActivity.newSuggestionsSettingsIntent(requireContext()))
				true
			}
			else -> false
		}
	}

	companion object {

		fun newInstance() = SuggestionsFragment()
	}
}
