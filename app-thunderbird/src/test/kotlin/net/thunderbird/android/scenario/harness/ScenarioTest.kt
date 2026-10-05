package net.thunderbird.android.scenario.harness

import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for scenario tests: the real app (its complete Koin graph and startup, under Robolectric, see
 * [ScenarioApplication]) against a real IMAP server.
 *
 * A scenario describes behaviour from the outside, so it survives rewrites of the sync code and a change of test
 * server: server state goes in through the fixture DSL, the user's actions go through [ScenarioDriver], and the
 * scenario checks what the user sees plus the server state, read back independently of the app.
 *
 * ```
 * class SomethingScenarioTest : ScenarioTest() {
 *     @Test
 *     fun `what the user experiences`() = scenario {
 *         // Arrange
 *         val user = server.user { inbox() }
 *         val account = client.account(user)
 *         server.deliver(user) { inbox { message("Hi") } }
 *
 *         // Act
 *         driver.pullToRefresh(account, FolderPath.INBOX)
 *
 *         // Assert
 *         assertThat(driver.subjects(account)).containsExactly("Hi")
 *     }
 * }
 * ```
 *
 * Rules:
 * - One test method per class. Each class runs in its own JVM, see [ScenarioRule].
 * - Arrange, Act, Assert, as in the project's other tests. A scenario whose behaviour unfolds in steps (e.g. time
 *   passing twice) repeats the Act/Assert pair; checks that the arranged state is what the scenario needs, such as
 *   the app showing a message before the user acts on it, end the Arrange part.
 * - Adding an account runs its first mail check (INBOX and the folder list) unless it has a check interval, so a
 *   scenario doesn't need to refresh INBOX or the folder list before acting.
 * - Each scenario gets fresh server users, so scenarios can run in parallel against one server.
 * - The app connects through a per-test fault proxy; use `network { }` to inject faults. When a scenario fails, the
 *   proxy transcript is printed.
 *
 * Run with `./gradlew :app-thunderbird:testFossDebugUnitTest -PscenarioTests`, or run a single scenario from Android
 * Studio. Gradle starts the test mail server as needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScenarioApplication::class, sdk = [ScenarioTest.ROBOLECTRIC_SDK])
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
