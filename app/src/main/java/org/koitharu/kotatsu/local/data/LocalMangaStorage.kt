package org.koitharu.kotatsu.local.data

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.annotation.WorkerThread
import androidx.collection.ArraySet
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.local.domain.CbzMangaOutput
import org.koitharu.kotatsu.local.domain.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.toCamelCase
import org.koitharu.kotatsu.utils.AlphanumComparator
import org.koitharu.kotatsu.utils.ext.longHashCode
import org.koitharu.kotatsu.utils.ext.readText
import org.koitharu.kotatsu.utils.ext.runCatchingCancellable
import java.io.File
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

private const val MAX_PARALLELISM = 4

@Singleton
class LocalMangaStorage @Inject constructor(
	private val storageManager: LocalStorageManager,
) {

	suspend fun getList(offset: Int, limit: Int): List<LocalManga> {
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
		val files = getAllFiles().drop(offset).take(limit).toList()
		return coroutineScope {
			files.map { file ->
				getFromFileAsync(file, dispatcher)
			}.awaitAll()
		}.filterNotNullTo(ArrayList(files.size))
	}

	suspend fun find(mangaId: Long): Manga? {
		val (info, file) = getAllFiles()
			.mapNotNull { file ->
				getMangaInfo(file)?.to(file)
			}.firstOrNull { (info, _) ->
				info.id == mangaId
			} ?: return null
		val fileUri = file.toUri().toString()
		return info.copy2(
			source = MangaSource.LOCAL,
			url = fileUri,
			chapters = info.chapters?.map { c -> c.copy(url = fileUri) },
		)
	}

	suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return runInterruptible(Dispatchers.IO) {
			val uri = Uri.parse(chapter.url)
			val file = uri.toFile()
			val zip = ZipFile(file)
			val index = zip.readMangaIndex()
			var entries = zip.entries().asSequence()
			entries = if (index != null) {
				val pattern = index.getChapterNamesPattern(chapter)
				entries.filter { x -> !x.isDirectory && x.name.substringBefore('.').matches(pattern) }
			} else {
				val parent = uri.fragment.orEmpty()
				entries.filter { x ->
					!x.isDirectory && x.name.substringBeforeLast(
						File.separatorChar,
						"",
					) == parent
				}
			}
			entries
				.toList()
				.sortedWith(compareBy(AlphanumComparator()) { x -> x.name })
				.map { x ->
					val entryUri = zipUri(file, x.name)
					MangaPage(
						id = entryUri.longHashCode(),
						url = entryUri,
						preview = null,
						referer = chapter.url,
						source = MangaSource.LOCAL,
					)
				}
		}
	}

	fun getAllFiles(): Flow<File> = flow {
		val dirs = storageManager.getReadableDirs().sorted()
		for (dir in dirs) {
			val files = dir.listFiles(CbzFilter) ?: continue
			files.sort()
			for (file in files) {
				emit(file)
			}
		}
	}.flowOn(Dispatchers.IO)

	private fun CoroutineScope.getFromFileAsync(
		file: File,
		context: CoroutineContext,
	): Deferred<LocalManga?> = async(context) {
		runInterruptible {
			runCatchingCancellable { LocalManga(getFromFile(file), file) }.getOrNull()
		}
	}

	@WorkerThread
	fun getFromFile(file: File): Manga = ZipFile(file).use { zip ->
		val fileUri = file.toUri().toString()
		val index = zip.readMangaIndex()
		val info = index?.getMangaInfo()
		if (index != null && info != null) {
			info.copy2(
				source = MangaSource.LOCAL,
				url = fileUri,
				coverUrl = zipUri(
					file,
					entryName = index.getCoverEntry() ?: findFirstImageEntry(zip.entries())?.name.orEmpty(),
				),
				chapters = info.chapters?.map { c ->
					c.copy(url = fileUri, source = MangaSource.LOCAL)
				},
			)
		} else {
			getFromFileFallback(file, fileUri, zip)
		}
	}

	suspend fun getMangaInfo(file: File): Manga? = runInterruptible(Dispatchers.IO) {
		val index = ZipFile(file).use { zip -> zip.readMangaIndex() }
		index?.getMangaInfo()
	}

	private fun ZipFile.readMangaIndex() = getEntry(CbzMangaOutput.ENTRY_NAME_INDEX)
		?.let(this::readText)
		?.let(::MangaIndex)

	@WorkerThread
	private fun getFromFileFallback(file: File, fileUri: String, zip: ZipFile): Manga {
		val title = file.nameWithoutExtension.replace("_", " ").toCamelCase()
		val chapters = ArraySet<String>()
		for (x in zip.entries()) {
			if (!x.isDirectory) {
				chapters += x.name.substringBeforeLast(File.separatorChar, "")
			}
		}
		val uriBuilder = file.toUri().buildUpon()
		return Manga(
			id = file.absolutePath.longHashCode(),
			title = title,
			url = fileUri,
			publicUrl = fileUri,
			source = MangaSource.LOCAL,
			coverUrl = zipUri(file, findFirstImageEntry(zip.entries())?.name.orEmpty()),
			chapters = chapters.sortedWith(AlphanumComparator()).mapIndexed { i, s ->
				MangaChapter(
					id = "$i$s".longHashCode(),
					name = s.ifEmpty { title },
					number = i + 1,
					source = MangaSource.LOCAL,
					uploadDate = 0L,
					url = uriBuilder.fragment(s).build().toString(),
					scanlator = null,
					branch = null,
				)
			},
			altTitle = null,
			rating = -1f,
			isNsfw = false,
			tags = setOf(),
			state = null,
			author = null,
			largeCoverUrl = null,
			description = null,
		)
	}
}

private fun zipUri(file: File, entryName: String) = "cbz://${file.path}#$entryName"

private fun findFirstImageEntry(entries: Enumeration<out ZipEntry>): ZipEntry? {
	val list = entries.toList()
		.filterNot { it.isDirectory }
		.sortedWith(compareBy(AlphanumComparator()) { x -> x.name })
	val map = MimeTypeMap.getSingleton()
	return list.firstOrNull {
		map.getMimeTypeFromExtension(it.name.substringAfterLast('.'))
			?.startsWith("image/") == true
	}
}

private fun Manga.copy2(
	url: String = this.url,
	coverUrl: String = this.coverUrl,
	chapters: List<MangaChapter>? = this.chapters,
	source: MangaSource = this.source,
) = Manga(
	id = id,
	title = title,
	altTitle = altTitle,
	url = url,
	publicUrl = publicUrl,
	rating = rating,
	isNsfw = isNsfw,
	coverUrl = coverUrl,
	tags = tags,
	state = state,
	author = author,
	largeCoverUrl = largeCoverUrl,
	description = description,
	chapters = chapters,
	source = source,
)

private fun MangaChapter.copy(
	url: String = this.url,
	source: MangaSource = this.source,
) = MangaChapter(
	id = id,
	name = name,
	number = number,
	url = url,
	scanlator = scanlator,
	uploadDate = uploadDate,
	branch = branch,
	source = source,
)
