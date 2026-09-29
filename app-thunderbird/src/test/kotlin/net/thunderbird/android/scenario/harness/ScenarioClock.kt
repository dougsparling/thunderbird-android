package net.thunderbird.android.scenario.harness

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The app's clock during a scenario: real time plus an offset that only ever grows.
 *
 * Scenarios make time pass with [advanceBy], e.g. to make a periodic sync due. Real time keeps running underneath
 * because some app code still reads the system clock directly (the IMAP and POP3 sync record a folder's last check
 * time that way). With an offset on top of real time, this clock never falls behind those timestamps.
 */
@OptIn(ExperimentalTime::class)
class ScenarioClock : Clock {
    private val offsetMillis = AtomicLong(0)

    override fun now(): Instant = Clock.System.now() + offsetMillis.get().milliseconds

    fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "Time only moves forward in a scenario" }
        offsetMillis.addAndGet(duration.inWholeMilliseconds)
    }
}
