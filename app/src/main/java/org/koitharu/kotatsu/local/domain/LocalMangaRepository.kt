package org.koitharu.kotatsu.local.domain

import android.net.Uri
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.local.data.LocalMangaStorage
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.TempFileFilter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.utils.AlphanumComparator
import org.koitharu.kotatsu.utils.CompositeMutex
import org.koitharu.kotatsu.utils.ext.asArrayList
import org.koitharu.kotatsu.utils.ext.deleteAwait
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 20

@Singleton
class LocalMangaRepository @Inject constructor(
	private val storage: LocalMangaStorage,
	private val storageManager: LocalStorageManager,
) : MangaRepository {

	override val source = MangaSource.LOCAL
	private val locks = CompositeMutex<Long>()

	override suspend fun getList(offset: Int, query: String): List<Manga> {
		val list = storage.getList(offset = offset, limit = PAGE_SIZE).asArrayList()
		if (query.isNotEmpty()) {
			list.retainAll { x -> x.isMatchesQuery(query) }
		}
		return list.unwrap()
	}

	override suspend fun getList(offset: Int, tags: Set<MangaTag>?, sortOrder: SortOrder?): List<Manga> {
		val list = storage.getList(offset = offset, limit = PAGE_SIZE).asArrayList()
		if (!tags.isNullOrEmpty()) {
			list.retainAll { x -> x.containsTags(tags) }
		}
		when (sortOrder) {
			SortOrder.ALPHABETICAL -> list.sortWith(compareBy(AlphanumComparator()) { x -> x.manga.title })
			SortOrder.RATING -> list.sortByDescending { it.manga.rating }
			SortOrder.NEWEST,
			SortOrder.UPDATED,
			-> list.sortByDescending { it.createdAt }

			else -> Unit
		}
		return list.unwrap()
	}

	override suspend fun getDetails(manga: Manga) = when {
		manga.source != MangaSource.LOCAL -> requireNotNull(findSavedManga(manga)) {
			"Manga is not local or saved"
		}

		else -> storage.getFromFile(Uri.parse(manga.url).toFile())
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return storage.getPages(chapter)
	}

	suspend fun delete(manga: Manga): Boolean {
		val file = Uri.parse(manga.url).toFile()
		return file.deleteAwait()
	}

	suspend fun deleteChapters(manga: Manga, ids: Set<Long>) {
		lockManga(manga.id)
		try {
			val uri = Uri.parse(manga.url)
			val file = uri.toFile()
			val cbz = CbzMangaOutput(file, manga)
			CbzMangaOutput.filterChapters(cbz, ids)
		} finally {
			unlockManga(manga.id)
		}
	}

	suspend fun getFromFile(file: File): Manga = runInterruptible(Dispatchers.IO) {
		storage.getFromFile(file)
	}

	suspend fun getRemoteManga(localManga: Manga): Manga? {
		val file = runCatching {
			Uri.parse(localManga.url).toFile()
		}.getOrNull() ?: return null
		return storage.getMangaInfo(file)
	}

	suspend fun findSavedManga(remoteManga: Manga): Manga? {
		return storage.find(remoteManga.id)
	}

	suspend fun watchReadableDirs(): Flow<File> {
		val filter = TempFileFilter()
		val dirs = storageManager.getReadableDirs()
		return storageManager.observe(dirs)
			.filterNot { filter.accept(it, it.name) }
	}

	override val sortOrders = setOf(SortOrder.ALPHABETICAL, SortOrder.RATING)

	override suspend fun getPageUrl(page: MangaPage) = page.url

	override suspend fun getTags(): Set<MangaTag> = storage.getAllFiles()
		.mapNotNull { file -> storage.getMangaInfo(file) }
		.flatMapConcat { it.tags.asFlow() }
		.toSet()

	suspend fun getOutputDir(): File? {
		return storageManager.getDefaultWriteableDir()
	}

	suspend fun cleanup() {
		val dirs = storageManager.getWriteableDirs()
		runInterruptible(Dispatchers.IO) {
			dirs.flatMap { dir ->
				dir.listFiles(TempFileFilter())?.toList().orEmpty()
			}.forEach { file ->
				file.delete()
			}
		}
	}

	suspend fun lockManga(id: Long) {
		locks.lock(id)
	}

	fun unlockManga(id: Long) {
		locks.unlock(id)
	}
}
