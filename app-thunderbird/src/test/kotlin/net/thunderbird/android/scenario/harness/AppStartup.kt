package net.thunderbird.android.scenario.harness

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.featureflag.keys.GeneratedFeatureFlagKey
import org.koin.core.Koin

/**
 * Waits until the app has finished starting, as it has a moment after launch on a device.
 *
 * The app loads its feature flags in a coroutine on the main thread after `onCreate`. Robolectric only runs that
 * when the main looper is idled, so without this wait a scenario could act while every flag is still unavailable,
 * which the app never sees on a device (e.g. authentication errors would then notify nobody).
 */
internal fun awaitAppStarted(koin: Koin, timeout: Duration = STARTUP_TIMEOUT) {
    val featureFlagProvider: FeatureFlagProvider = koin.get()
    MainLooperPump(timeout).use { pump ->
        pump.awaitCondition("the app to load its feature flags") {
            !featureFlagProvider.provide(GeneratedFeatureFlagKey.DISPLAY_IN_APP_NOTIFICATIONS).isUnavailable()
        }
    }
}

private val STARTUP_TIMEOUT = 30.seconds
