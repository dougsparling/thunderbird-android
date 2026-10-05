package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SpecialUse

/** Archiving a conversation from the threaded list moves every message of it to Archive. */
class ThreadedArchiveScenarioTest : ScenarioTest() {

    @Test
    fun `archiving a thread moves all its messages to the archive`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(UNRELATED)
                conversation()
            }
            folder(ARCHIVE.segments.single(), specialUse = SpecialUse.ARCHIVE)
        }
        val account = client.account(user)

        // Act
        driver.archiveThread(account, FolderPath.INBOX, THREAD_NEWEST)

        // Assert
        val serverState = server.stateOf(user)
        assertThat(serverState.folder(FolderPath.INBOX).subjects).containsExactly(UNRELATED)
        assertThat(serverState.folder(ARCHIVE).subjects).containsExactlyInAnyOrder(*THREAD_SUBJECTS.toTypedArray())
        assertThat(driver.subjects(account, FolderPath.INBOX)).containsExactly(UNRELATED)
    }

    private companion object {
        val ARCHIVE = FolderPath.of("Archive")
    }
}
