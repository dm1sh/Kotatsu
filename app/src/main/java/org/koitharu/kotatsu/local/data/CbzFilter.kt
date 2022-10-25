package org.koitharu.kotatsu.local.data

import java.io.File
import java.io.FilenameFilter
import java.util.Locale

object CbzFilter : FilenameFilter {

	override fun accept(dir: File, name: String): Boolean {
		return isFileSupported(name)
	}

	fun isFileSupported(name: String): Boolean {
		val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
		return ext == "cbz" || ext == "zip"
	}
}
