package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Another client deletes the folder Work and creates it again with a single new message C. The app has already synced
 * Work with messages A and B. When the user refreshes Work, the app must show exactly C: the recreated folder has a new
 * UIDVALIDITY, so the messages the app knew must be thrown away instead of being matched to the new ones, which restart
 * their UIDs at 1.
 */
class FolderRecreatedScenarioTest : ScenarioTest() {

    @Test
    fun `refresh after the folder is recreated elsewhere shows only the new message`() = scenario {
        // Arrange
        val user = server.user {
            folder("Work") {
                message(SUBJECT_A)
                message(SUBJECT_B)
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, WORK)
        assertThat(driver.subjects(account, WORK)).containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B)

        // Act
        server.recreateFolder(user, WORK) {
            message(SUBJECT_C)
        }
        driver.pullToRefresh(account, WORK)

        // Assert
        assertThat(driver.subjects(account, WORK)).containsExactly(SUBJECT_C)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        const val SUBJECT_A = "Report A"
        const val SUBJECT_B = "Report B"
        const val SUBJECT_C = "Report C"
    }
}
