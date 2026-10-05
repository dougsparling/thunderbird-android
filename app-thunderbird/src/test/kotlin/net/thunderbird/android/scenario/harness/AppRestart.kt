package net.thunderbird.android.scenario.harness

import android.os.Looper
import app.k9mail.legacy.di.DI
import com.fsck.k9.Core
import com.fsck.k9.K9
import com.fsck.k9.mailstore.MessageListCache
import java.lang.reflect.Modifier
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.Shadows.shadowOf

/**
 * Restarts the app inside the running test, as if Android had killed its process and launched it again: everything
 * the app keeps in memory is dropped, everything it stored (databases, settings) stays.
 *
 * Robolectric runs one app per test and can't start a second process, so this rebuilds the app in place: the caller
 * stops the old app's threads and services first, then this stops Koin, resets the process-wide state the app keeps in
 * Kotlin objects and in its `Application` instance, and runs app startup (`attachBaseContext` and `onCreate`) again on
 * the same files. [ScenarioClock] carries over, so time keeps going as before.
 */
internal object AppRestart {

    fun restart(application: ScenarioApplication) {
        val koin = GlobalContext.get()
        val clock = koin.get<ScenarioClock>()

        // Let the old app finish what it posted to the main thread. (Its app-wide coroutine scope is GlobalScope, which
        // can't be cancelled; the caller has already waited for the app's work to finish.)
        shadowOf(Looper.getMainLooper()).idle()
        stopKoin()

        forgetProcessState(application)

        // Same as BaseApplication.attachBaseContext(), except for pointing the legacy Log at the logger: the logger the
        // first start set there keeps working, and it holds no state the restart needs to drop.
        Core.earlyInit()
        DI.start(application, listOf(application.provideAppModule()))

        ScenarioApplication.clockAfterRestart = clock
        application.onCreate()
    }

    /** Resets what a new process would start without. */
    private fun forgetProcessState(application: ScenarioApplication) {
        // Kotlin objects that hold Koin-injected dependencies and settings.
        resetToFreshInstance(K9, K9::class.java)
        resetToFreshInstance(Core, Core::class.java)

        // The Application instance itself: its injected dependencies and the scopes it creates.
        val freshApplication = ScenarioApplication()
        var type: Class<*>? = ScenarioApplication::class.java
        while (type != null && type != android.app.Application::class.java) {
            copyInstanceFields(type, from = freshApplication, to = application)
            type = type.superclass
        }

        // Per-account caches of the message list.
        MessageListCache::class.java.getDeclaredField("instances").apply { isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }

        // DataStore refuses a second store for a file that a store in the same process still has open. The old app's
        // stores are never closed, so forget them like a new process would.
        DATA_STORE_FILE_REGISTRIES.forEach { className ->
            val registry = runCatching { Class.forName(className) }.getOrNull() ?: return@forEach
            registry.getDeclaredField("activeFiles").apply { isAccessible = true }
                .let { (it.get(null) as MutableSet<*>).clear() }
        }
    }

    /** DataStore classes that keep a process-wide set of the files their stores have open. */
    private val DATA_STORE_FILE_REGISTRIES = listOf(
        "androidx.datastore.core.okio.OkioStorage",
        "androidx.datastore.core.FileStorage",
    )

    /** Replaces the fields of the Kotlin object [instance] with those of a newly constructed one. */
    private fun <T : Any> resetToFreshInstance(instance: T, type: Class<T>) {
        val fresh = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        copyInstanceFields(type, from = fresh, to = instance)
    }

    private fun copyInstanceFields(type: Class<*>, from: Any, to: Any) {
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                field.set(to, field.get(from))
            }
    }
}
