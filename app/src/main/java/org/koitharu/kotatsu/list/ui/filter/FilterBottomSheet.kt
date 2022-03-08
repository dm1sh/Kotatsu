package org.koitharu.kotatsu.list.ui.filter

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import org.koin.androidx.viewmodel.ViewModelOwner.Companion.from
import org.koin.androidx.viewmodel.ext.android.sharedViewModel
import org.koin.core.parameter.parametersOf
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.BaseBottomSheet
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.databinding.SheetFilterBinding
import org.koitharu.kotatsu.utils.BottomSheetToolbarController
import org.koitharu.kotatsu.utils.ext.withArgs

class FilterBottomSheet : BaseBottomSheet<SheetFilterBinding>(), MenuItem.OnActionExpandListener,
	SearchView.OnQueryTextListener, DialogInterface.OnKeyListener {

	private val viewModel by sharedViewModel<FilterViewModel>(
		owner = { from(requireParentFragment(), requireParentFragment()) }
	) {
		parametersOf(
			requireArguments().getParcelable<MangaSource>(ARG_SOURCE),
		)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val state = requireArguments().getParcelable<FilterState>(ARG_STATE)
		viewModel.updateState(state)
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		return super.onCreateDialog(savedInstanceState).also {
			it.setOnKeyListener(this)
		}
	}

	override fun onInflateView(inflater: LayoutInflater, container: ViewGroup?): SheetFilterBinding {
		return SheetFilterBinding.inflate(inflater, container, false)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		binding.toolbar.setNavigationOnClickListener { dismiss() }
		behavior?.addBottomSheetCallback(BottomSheetToolbarController(binding.toolbar))
		if (!resources.getBoolean(R.bool.is_tablet)) {
			binding.toolbar.navigationIcon = null
		}
		val adapter = FilterAdapter(viewModel)
		binding.recyclerView.adapter = adapter
		viewModel.filter.observe(viewLifecycleOwner, adapter::setItems)
		viewModel.result.observe(viewLifecycleOwner) {
			parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(ARG_STATE to it))
		}
		initOptionsMenu()
	}

	override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
		setExpanded(isExpanded = true, isLocked = true)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		val searchView = (item.actionView as? SearchView) ?: return false
		searchView.setQuery("", false)
		searchView.post { setExpanded(isExpanded = false, isLocked = false) }
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		viewModel.performSearch(newText?.trim().orEmpty())
		return true
	}

	override fun onKey(dialog: DialogInterface?, keyCode: Int, event: KeyEvent?): Boolean {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			val menuItem = binding.toolbar.menu.findItem(R.id.action_search) ?: return false
			if (menuItem.isActionViewExpanded) {
				if (event?.action == KeyEvent.ACTION_UP) {
					menuItem.collapseActionView()
				}
				return true
			}
		}
		return false
	}

	private fun initOptionsMenu() {
		binding.toolbar.inflateMenu(R.menu.opt_filter)
		val searchMenuItem = binding.toolbar.menu.findItem(R.id.action_search)
		searchMenuItem.setOnActionExpandListener(this)
		val searchView = searchMenuItem.actionView as SearchView
		searchView.setOnQueryTextListener(this)
		searchView.setIconifiedByDefault(false)
		searchView.queryHint = searchMenuItem.title
	}

	companion object {

		const val REQUEST_KEY = "filter"

		const val ARG_STATE = "state"
		private const val TAG = "FilterBottomSheet"
		private const val ARG_SOURCE = "source"

		fun show(
			fm: FragmentManager,
			source: MangaSource,
			state: FilterState,
		) = FilterBottomSheet().withArgs(2) {
			putParcelable(ARG_SOURCE, source)
			putParcelable(ARG_STATE, state)
		}.show(fm, TAG)
	}
}