package org.koitharu.kotatsu.utils

import java.util.*
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CompositeMutex<T : Any> : Set<T> {

	private val data = HashMap<T, MutableList<CancellableContinuation<Unit>>>()
	private val mutex = Mutex()

	override val size: Int
		get() = data.size

	override fun contains(element: T): Boolean {
		return data.containsKey(element)
	}

	override fun containsAll(elements: Collection<T>): Boolean {
		return elements.all { x -> data.containsKey(x) }
	}

	override fun isEmpty(): Boolean {
		return data.isEmpty()
	}

	override fun iterator(): Iterator<T> {
		return data.keys.iterator()
	}

	suspend fun lock(element: T) {
		while (coroutineContext.isActive) {
			waitForRemoval(element)
			mutex.withLock {
				if (data[element] == null) {
					data[element] = LinkedList<CancellableContinuation<Unit>>()
					return
				}
			}
		}
	}

	fun unlock(element: T) {
		val continuations = checkNotNull(data.remove(element)) {
			"CompositeMutex is not locked for $element"
		}
		continuations.forEach { c ->
			if (c.isActive) {
				c.resume(Unit)
			}
		}
	}

	private suspend fun waitForRemoval(element: T) {
		val list = data[element] ?: return
		suspendCancellableCoroutine<Unit> { continuation ->
			list.add(continuation)
			continuation.invokeOnCancellation {
				list.remove(continuation)
			}
		}
	}
}