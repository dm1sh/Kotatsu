package org.koitharu.kotatsu.utils

class PopularitySet<T> : MutableSet<T> {

	private val map = HashMap<T, Int>()
	private var data: List<T>? = emptyList()

	override fun add(element: T): Boolean {
		map[element] = map[element]?.plus(1) ?: 1
		invalidate()
		return true
	}

	override fun addAll(elements: Collection<T>): Boolean {
		if (elements.isEmpty()) {
			return false
		}
		for (element in elements) {
			map[element] = map[element]?.plus(1) ?: 1
		}
		invalidate()
		return true
	}

	override fun clear() {
		map.clear()
		data = emptyList()
	}

	override fun iterator(): MutableIterator<T> = IteratorImpl()

	override fun remove(element: T): Boolean {
		val counter = map[element] ?: return false
		if (counter > 1) {
			map[element] = counter - 1
		} else {
			map.remove(element)
		}
		return true
	}

	override fun removeAll(elements: Collection<T>): Boolean {
		return elements.any { remove(it) }
	}

	override fun retainAll(elements: Collection<T>): Boolean {
		throw UnsupportedOperationException("\"retainAll\" is not supported by PopularitySet")
	}

	override val size: Int
		get() = getData().size

	override fun contains(element: T): Boolean {
		return map.containsKey(element)
	}

	override fun containsAll(elements: Collection<T>): Boolean {
		return elements.all { map.containsKey(it) }
	}

	override fun isEmpty(): Boolean = getData().isEmpty()

	private fun invalidate() {
		data = null
	}

	@Synchronized
	private fun getData(): List<T> {
		data?.let { return it }
		return map.entries
			.sortedByDescending { it.value }
			.map { it.key }
			.also { data = it }
	}

	private inner class IteratorImpl : MutableIterator<T> {

		private var i = 0

		override fun hasNext(): Boolean {
			return i < getData().lastIndex
		}

		override fun next(): T {
			i++
			return getData()[i]
		}

		override fun remove() {
			remove(getData()[i])
		}
	}
}
