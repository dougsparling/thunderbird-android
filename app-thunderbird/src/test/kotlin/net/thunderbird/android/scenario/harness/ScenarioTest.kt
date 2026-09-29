package net.thunderbird.android.scenario.harness

import net.thunderbird.android.ThunderbirdApp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for scenario tests: the real app (its [ThunderbirdApp] and complete Koin graph, under Robolectric)
 * against a real IMAP server.
 *
 * A scenario describes behaviour from the outside, so it survives rewrites of the sync code and a change of test
 * server: server state goes in through the fixture DSL, the user's actions go through [ScenarioDriver], and the
 * scenario checks what the user sees plus the server state, read back independently of the app.
 *
 * ```
 * class SomethingScenarioTest : ScenarioTest() {
 *     @Test
 *     fun `what the user experiences`() = scenario {
 *         val user = server.user { inbox { message { subject("Hi") } } }
 *         val account = client.account(user)
 *         driver.sync(account, FolderPath.INBOX)
 *         assertThat(driver.messageList(account, FolderPath.INBOX)).hasSize(1)
 *     }
 * }
 * ```
 *
 * Rules:
 * - One test method per class. Each class runs in its own JVM, see [ScenarioRule].
 * - Each scenario gets fresh server users, so scenarios can run in parallel against one server.
 * - The app connects through a per-test fault proxy; use `network { }` to inject faults. When a scenario fails, the
 *   proxy transcript is printed.
 *
 * Run with `./gradlew :app-thunderbird:testFossDebugUnitTest -PscenarioTests`, or run a single scenario from Android
 * Studio. Gradle starts the test mail server as needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ThunderbirdApp::class, sdk = [ScenarioTest.ROBOLECTRIC_SDK])
abstract class ScenarioTest {
    @get:Rule
    val scenarioRule = ScenarioRule()

    protected fun scenario(block: ScenarioScope.() -> Unit) = scenarioRule.run(block)

    companion object {
        /**
         * The Android SDK Robolectric simulates. Pinned so every scenario uses the same, already downloaded, Android
         * jar rather than one derived from the app's target SDK.
         */
        const val ROBOLECTRIC_SDK = 37
    }
}
