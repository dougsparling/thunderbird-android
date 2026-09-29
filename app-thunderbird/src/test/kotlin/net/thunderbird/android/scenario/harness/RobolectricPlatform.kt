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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * The Android platform as a scenario sees it, simulated with Robolectric: permissions, network, services the app
 * starts and the notifications it posts.
 *
 * Robolectric's defaults differ from a device the user has set up the app on, so [init] establishes that state:
 * online, notifications and exact alarms allowed, contacts and camera not (yet) granted.
 */
internal class RobolectricPlatform(private val application: Application) : AutoCloseable {
    private val permissions = mutableMapOf<AppPermission, Boolean>()
    private val runningServices = mutableMapOf<String, ServiceController<out Service>>()
    private var nextStartId = 1

    /** Set once the scenario has started using the app; from then on permissions can only be granted. */
    private var appInUse = false

    init {
        setOnline()
        AppPermission.entries.forEach { permission ->
            applyPermission(permission, granted = permission.grantedByDefault)
        }
    }

    fun setPermission(permission: AppPermission, granted: Boolean) {
        if (permissions[permission] == granted) return
        check(granted || !appInUse) {
            "Can't revoke $permission while the app is in use: on a device that restarts the app. Deny it before the " +
                "scenario's first action instead."
        }
        applyPermission(permission, granted)
    }

    /** Call when the scenario starts using the app. */
    fun markAppInUse() {
        appInUse = true
    }

    /**
     * Runs services the app asked to start and destroys the ones it asked to stop, as the system would. Robolectric
     * only records these requests.
     */
    fun runRequestedServices() {
        val shadowApplication = shadowOf(application)
        while (true) {
            val intent = shadowApplication.nextStartedService ?: break
            startService(intent)
        }
        while (true) {
            val intent = shadowApplication.nextStoppedService ?: break
            runningServices.remove(intent.component?.className)?.destroy()
        }
    }

    /** The notifications currently shown to the user. */
    fun notifications(): List<ClientNotification> {
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

    override fun close() {
        runningServices.values.forEach { it.destroy() }
        runningServices.clear()
    }

    private fun startService(intent: Intent) {
        val className = checkNotNull(intent.component?.className) { "Service intent without component: $intent" }
        val controller = runningServices.getOrPut(className) {
            @Suppress("UNCHECKED_CAST")
            val serviceClass = Class.forName(className) as Class<out Service>
            Robolectric.buildService(serviceClass, intent).create()
        }
        controller.withIntent(intent).startCommand(0, nextStartId++)
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
}

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
