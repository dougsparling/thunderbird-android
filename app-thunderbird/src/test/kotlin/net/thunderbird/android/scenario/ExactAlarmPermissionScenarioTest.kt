package net.thunderbird.android.scenario

import android.provider.Settings
import assertk.all
import assertk.assertThat
import assertk.assertions.any
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.none
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.AppPermission
import net.thunderbird.android.scenario.harness.ClientNotification
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.isIdling
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Push needs the exact alarm permission. Without it, enabling push shows an ongoing notification that asks for the
 * permission, and push doesn't start. Once the user grants it, the notification goes away and push delivers mail.
 */
class ExactAlarmPermissionScenarioTest : ScenarioTest() {

    @Test
    fun `push asks for the exact alarm permission and starts once it is granted`() = scenario {
        // Arrange
        device.setPermission(AppPermission.EXACT_ALARMS, granted = false)
        val user = server.user { inbox() }
        val account = client.account(user)

        // Act
        driver.enablePush(account, FolderPath.INBOX)

        // Assert
        eventually {
            assertThat(device.notifications()).any { notification ->
                notification.all {
                    prop(ClientNotification::tapAction).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    prop(ClientNotification::isOngoing).isTrue()
                }
            }
        }
        assertThat(isIdling(proxy.transcript())).isFalse()

        // Act
        device.setPermission(AppPermission.EXACT_ALARMS, granted = true)
        awaitAppListening()
        server.deliver(user) {
            inbox { message(SUBJECT) }
        }

        // Assert
        eventually {
            assertThat(device.notifications()).none { notification ->
                notification.prop(ClientNotification::tapAction).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        }
        eventually {
            assertThat(driver.subjects(account)).containsExactly(SUBJECT)
        }
    }

    private companion object {
        const val SUBJECT = "Now listening"
    }
}
