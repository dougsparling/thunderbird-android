package net.thunderbird.android.scenario.harness

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import org.koin.core.Koin
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * The Android device the app runs on: permissions, network, time, background work, services and notifications.
 *
 * Unlike [ScenarioDriver], this doesn't depend on how the app is built, only on the Android APIs it uses, so it stays
 * the same when the sync core is rewritten. It's simulated with Robolectric, whose defaults differ from a device the
 * user has set the app up on; the device starts online, with notifications and exact alarms allowed and contacts and
 * camera not (yet) granted.
 *
 * Background work runs as Android would run it: WorkManager judges what is due by the scenario's clock, and due work
 * runs whenever the device settles, e.g. after [advanceTime].
 */
class ScenarioDevice internal constructor(
    koin: Koin,
    private val awaitAppIdle: () -> Unit,
    timeout: Duration = DEFAULT_TIMEOUT,
) : AutoCloseable {
    private val application: Application = koin.get()
    private val clock: ScenarioClock = koin.get()
    private val pump = MainLooperPump(timeout)

    private val workManager: WorkManager = installTestWorkManager(koin)
    private val workTestDriver: TestDriver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(application))

    private val permissions = mutableMapOf<AppPermission, Boolean>()
    private val runningServices = mutableMapOf<String, ServiceController<out Service>>()
    private var nextStartId = 1

    /** Set once the scenario has started using the app; from then on permissions can only be granted. */
    private var appInUse = false

    init {
        setOnline()
        AppPermission.entries.forEach { permission -> applyPermission(permission, permission.grantedByDefault) }
    }

    /**
     * The user grants or denies [permission]. Granting works at any time; denying only before the scenario starts
     * using the app, because on a device revoking a permission restarts the app.
     */
    fun setPermission(permission: AppPermission, granted: Boolean) {
        if (permissions[permission] == granted) return
        check(granted || !appInUse) {
            "Can't revoke $permission while the app is in use: on a device that restarts the app. Deny it before the " +
                "scenario's first action instead."
        }
        applyPermission(permission, granted)
        settle()
    }

    /** The notifications the user currently sees. */
    fun notifications(): List<ClientNotification> {
        settle()
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        return notificationManager.activeNotifications.map { statusBarNotification ->
            val notification = statusBarNotification.notification
            ClientNotification(
                title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                tapAction = notification.contentIntent?.let { shadowOf(it).savedIntent.action },
                isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            )
        }
    }

    /** Lets [duration] pass. Background work that becomes due runs, as it would on a device. */
    fun advanceTime(duration: Duration) {
        clock.advanceBy(duration)
        settle()
    }

    /**
     * Lets the device and the app run until there's nothing left to do: the app finishes its work, services the app
     * started run, and background work that is due runs.
     */
    fun settle() {
        repeat(MAX_SETTLE_ROUNDS) {
            awaitAppIdle()
            val startedServices = runRequestedServices()
            val ranWork = runDueWork()
            if (!startedServices && !ranWork) return
        }
        error("The device didn't settle after $MAX_SETTLE_ROUNDS rounds; is background work rescheduling itself?")
    }

    internal fun markAppInUse() {
        appInUse = true
    }

    override fun close() {
        try {
            runningServices.values.forEach { it.destroy() }
            runningServices.clear()
        } finally {
            pump.close()
        }
    }

    /**
     * Runs services the app asked to start and destroys the ones it asked to stop, as the system would. Robolectric
     * only records these requests. Returns true if a service was started.
     */
    private fun runRequestedServices(): Boolean {
        val shadowApplication = shadowOf(application)
        var started = false
        while (true) {
            val intent = shadowApplication.nextStartedService ?: break
            startService(intent)
            started = true
        }
        while (true) {
            val intent = shadowApplication.nextStoppedService ?: break
            runningServices.remove(intent.component?.className)?.destroy()
        }
        return started
    }

    private fun startService(intent: Intent) {
        val className = checkNotNull(intent.component?.className) { "Service intent without component: $intent" }
        val controller = runningServices.getOrPut(className) {
            @Suppress("UNCHECKED_CAST")
            val serviceClass = Class.forName(className) as Class<out Service>
            Robolectric.buildService(serviceClass, intent).create()
        }
        controller.get().onStartCommand(intent, 0, nextStartId++)
    }

    /**
     * Runs enqueued WorkManager work whose scheduled time has come, by the scenario's clock. Returns true if any ran.
     *
     * The test scheduler runs work once its delay and constraints are marked as met; constraints are marked last
     * because it resets them after every run, so each call runs a job at most once.
     */
    @OptIn(ExperimentalTime::class)
    private fun runDueWork(): Boolean {
        val now = clock.now().toEpochMilliseconds()
        val due = workManager.getWorkInfos(ALL_WORK).get()
            .filter { it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis <= now }
        if (due.isEmpty()) return false

        // The work runs synchronously inside these calls (SynchronousExecutor), so run them off the main thread while
        // the main looper keeps being serviced.
        pump.runInBackground("running due background work") {
            due.forEach { work ->
                workTestDriver.setInitialDelayMet(work.id)
                if (work.periodicityInfo != null) workTestDriver.setPeriodDelayMet(work.id)
                workTestDriver.setAllConstraintsMet(work.id)
            }
        }
        return true
    }

    /**
     * Replaces WorkManager with its test implementation, running on the scenario's clock. Must happen before the app
     * first asks Koin for its WorkManager, which caches the instance.
     */
    @OptIn(ExperimentalTime::class)
    private fun installTestWorkManager(koin: Koin): WorkManager {
        val configuration = Configuration.Builder()
            .setWorkerFactory(koin.get<WorkerFactory>())
            .setExecutor(SynchronousExecutor())
            .setClock { clock.now().toEpochMilliseconds() }
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(application, configuration)

        val testWorkManager = WorkManager.getInstance(application)
        check(koin.get<WorkManager>() === testWorkManager) {
            "The app obtained WorkManager before the scenario could replace it with the test implementation"
        }
        return testWorkManager
    }

    private fun applyPermission(permission: AppPermission, granted: Boolean) {
        permissions[permission] = granted
        when (permission) {
            AppPermission.EXACT_ALARMS -> {
                ShadowAlarmManager.setCanScheduleExactAlarms(granted)
                if (granted) {
                    // Android tells the app when the user grants this special access.
                    application.sendBroadcast(
                        Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
                            .setPackage(application.packageName),
                    )
                }
            }

            else -> {
                val name = checkNotNull(permission.runtimePermission)
                if (granted) {
                    shadowOf(
                        application,
                    ).grantPermissions(name)
                } else {
                    shadowOf(application).denyPermissions(name)
                }
            }
        }
    }

    private fun setOnline() {
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance().also { networkCapabilities ->
            shadowOf(networkCapabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        shadowOf(connectivityManager).setNetworkCapabilities(connectivityManager.activeNetwork, capabilities)
    }

    private companion object {
        val DEFAULT_TIMEOUT = 2.minutes
        const val MAX_SETTLE_ROUNDS = 20
        val ALL_WORK = androidx.work.WorkQuery.fromStates(WorkInfo.State.entries)
    }
}

/**
 * Permissions the user controls. Scenarios start as on a device after onboarding: [NOTIFICATIONS] and [EXACT_ALARMS]
 * granted, [CONTACTS] and [CAMERA] not.
 */
enum class AppPermission {
    NOTIFICATIONS,
    CONTACTS,
    CAMERA,

    /** Special access ("Alarms & reminders"), needed to keep push connections alive. */
    EXACT_ALARMS,
}

/** A notification as the user sees it. [tapAction] is the intent action a tap starts, if any. */
data class ClientNotification(
    val title: String?,
    val text: String?,
    val tapAction: String?,
    val isOngoing: Boolean,
)

private val AppPermission.runtimePermission: String?
    get() = when (this) {
        AppPermission.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
        AppPermission.CONTACTS -> Manifest.permission.READ_CONTACTS
        AppPermission.CAMERA -> Manifest.permission.CAMERA
        AppPermission.EXACT_ALARMS -> null
    }

private val AppPermission.grantedByDefault: Boolean
    get() = when (this) {
        // Asked for during onboarding.
        AppPermission.NOTIFICATIONS, AppPermission.EXACT_ALARMS -> true

        // Only asked for when a feature needs them.
        AppPermission.CONTACTS, AppPermission.CAMERA -> false
    }
