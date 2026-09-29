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
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ClientNotification
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.isIdling
import net.thunderbird.mail.testserver.fixture.FolderPath

class ExactAlarmPermissionScenarioTest : ScenarioTest() {

    @Test
    fun `push asks for the exact alarm permission and starts once it is granted`() = scenario {
        device.setPermission(AppPermission.EXACT_ALARMS, granted = false)
        val user = server.user {
            inbox()
        }
        val account = client.account(user)

        driver.enablePush(account, FolderPath.INBOX)

        eventually {
            assertThat(device.notifications()).any { notification ->
                notification.all {
                    prop(ClientNotification::tapAction).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    prop(ClientNotification::isOngoing).isTrue()
                }
            }
        }
        assertThat(isIdling(proxy.transcript())).isFalse()

        device.setPermission(AppPermission.EXACT_ALARMS, granted = true)
        awaitAppListening()

        eventually {
            assertThat(device.notifications()).none { notification ->
                notification.prop(ClientNotification::tapAction).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        }

        server.deliver(user) {
            inbox {
                message {
                    subject(SUBJECT)
                    from(SENDER)
                    text("Pushed after the permission was granted.")
                }
            }
        }
        eventually {
            assertThat(driver.messageList(account, FolderPath.INBOX).map(ClientMessage::subject))
                .containsExactly(SUBJECT)
        }
    }

    private companion object {
        const val SENDER = "erin@example.org"
        const val SUBJECT = "Now listening"
    }
}
