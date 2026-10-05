package net.thunderbird.android.scenario.harness

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.thunderbird.android.BuildConfig
import net.thunderbird.android.ThunderbirdApp
import net.thunderbird.android.appModule
import net.thunderbird.app.common.FeatureFlagApplication
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.getKoin

/**
 * The app under test: [ThunderbirdApp]'s Koin graph and startup, with test replacements for what a scenario must
 * control. Only [ScenarioClock] is replaced so far.
 *
 * The replacements are loaded after Koin has started (in `attachBaseContext`) and before the app's `onCreate` creates
 * its long-lived objects, such as the mail sync classes, which take them as constructor arguments.
 *
 * Keep in sync with [ThunderbirdApp]. Telemetry initialization is left out; it doesn't affect mail behaviour.
 */
class ScenarioApplication : FeatureFlagApplication() {
    override fun provideAppModule(): Module = appModule
    override val appName: String = "thunderbird"
    override val appVersion: String = BuildConfig.VERSION_NAME

    override fun onCreate() {
        getKoin().loadModules(listOf(scenarioOverrides), allowOverride = true)
        super.onCreate()
    }

    @OptIn(ExperimentalTime::class)
    private val scenarioOverrides = module {
        single { clockAfterRestart ?: ScenarioClock() }
        single<Clock> { get<ScenarioClock>() }
    }

    internal companion object {
        /** The clock the app keeps when [AppRestart] starts it again, so scenario time carries over. */
        @Volatile
        var clockAfterRestart: ScenarioClock? = null
    }
}
