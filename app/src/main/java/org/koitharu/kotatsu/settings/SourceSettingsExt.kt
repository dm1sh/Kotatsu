package org.koitharu.kotatsu.settings

import android.view.inputmethod.EditorInfo
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.RemoteMangaRepository
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.settings.utils.AutoCompleteTextViewPreference
import org.koitharu.kotatsu.settings.utils.EditTextBindListener
import org.koitharu.kotatsu.settings.utils.EditTextDefaultSummaryProvider

fun PreferenceFragmentCompat.addPreferencesFromRepository(repository: RemoteMangaRepository) {
	val configKeys = repository.getConfigKeys()
	val screen = preferenceScreen
	for (key in configKeys) {
		val preference: Preference = when (key) {
			is ConfigKey.Domain -> {
				val presetValues = key.presetValues
				if (presetValues.isNullOrEmpty()) {
					EditTextPreference(requireContext())
				} else {
					AutoCompleteTextViewPreference(requireContext()).apply { entries = presetValues }
				}.apply {
					summaryProvider = EditTextDefaultSummaryProvider(key.defaultValue)
					setOnBindEditTextListener(
						EditTextBindListener(
							inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI,
							hint = key.defaultValue,
							validator = DomainValidator(),
						),
					)
					setTitle(R.string.domain)
					setDialogTitle(R.string.domain)
				}
			}

			is ConfigKey.UserAgent -> {
				EditTextPreference(requireContext()).apply {
					summaryProvider = EditTextDefaultSummaryProvider(key.defaultValue)
					setOnBindEditTextListener(
						EditTextBindListener(
							inputType = EditorInfo.TYPE_CLASS_TEXT,
							hint = key.defaultValue,
							validator = null,
						),
					)
					setTitle(R.string.user_agent)
					setDialogTitle(R.string.user_agent)
				}
			}

			is ConfigKey.ShowSuspiciousContent -> {
				SwitchPreferenceCompat(requireContext()).apply {
					setDefaultValue(key.defaultValue)
					setTitle(R.string.show_suspicious_content)
				}
			}
		}
		preference.isIconSpaceReserved = false
		preference.key = key.key
		screen.addPreference(preference)
	}
}
