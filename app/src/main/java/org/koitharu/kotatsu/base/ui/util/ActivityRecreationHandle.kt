package org.koitharu.kotatsu.base.ui.util

import android.app.Activity
import android.os.Bundle
import org.koitharu.kotatsu.base.ui.DefaultActivityLifecycleCallbacks
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityRecreationHandle @Inject constructor() : DefaultActivityLifecycleCallbacks {

	private val activities = WeakHashMap<Activity, Unit>()

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		activities[activity] = Unit
	}

	override fun onActivityDestroyed(activity: Activity) {
		activities.remove(activity)
	}

	fun recreateAll() {
		val snapshot = activities.keys.toList()
		snapshot.forEach { it.recreate() }
	}
}
