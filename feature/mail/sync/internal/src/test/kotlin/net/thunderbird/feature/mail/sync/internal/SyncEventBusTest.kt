package net.thunderbird.feature.mail.sync.internal

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.sync.api.SyncEvent

class SyncEventBusTest {
    private val accountId = AccountIdFactory.create()
    private val otherAccountId = AccountIdFactory.create()
    private val testSubject = SyncEventBus()

    @Test
    fun `observer gets live events in order`() = runTest {
        testSubject.observe().test {
            // Act
            testSubject.emit(SyncEvent.CheckMailStarted(accountId))
            testSubject.emit(SyncEvent.FolderSyncStarted(accountId, folderId = 1))
            testSubject.emit(SyncEvent.CheckMailFinished(accountId))

            // Assert
            assertThat(awaitItem()).isEqualTo(SyncEvent.CheckMailStarted(accountId))
            assertThat(awaitItem()).isEqualTo(SyncEvent.FolderSyncStarted(accountId, folderId = 1))
            assertThat(awaitItem()).isEqualTo(SyncEvent.CheckMailFinished(accountId))
        }
    }

    @Test
    fun `new observer first gets outcomes of earlier folder syncs, then the running sync with its progress`() =
        runTest {
            // Arrange
            testSubject.emit(SyncEvent.FolderSyncStarted(accountId, folderId = 1))
            testSubject.emit(SyncEvent.FolderSyncFinished(accountId, folderId = 1))
            testSubject.emit(SyncEvent.FolderSyncStarted(accountId, folderId = 2))
            testSubject.emit(SyncEvent.FolderSyncFailed(accountId, folderId = 2, message = "offline"))
            testSubject.emit(SyncEvent.FolderSyncStarted(accountId, folderId = 3))
            testSubject.emit(SyncEvent.FolderSyncProgress(accountId, folderId = 3, completed = 2, total = 5))

            // Act
            testSubject.observe().test {
                // Assert
                assertThat(awaitItem()).isEqualTo(SyncEvent.FolderSyncFinished(accountId, folderId = 1))
                assertThat(awaitItem()).isEqualTo(SyncEvent.FolderSyncFailed(accountId, folderId = 2, "offline"))
                assertThat(awaitItem()).isEqualTo(SyncEvent.FolderSyncStarted(accountId, folderId = 3))
                assertThat(awaitItem()).isEqualTo(
                    SyncEvent.FolderSyncProgress(accountId, folderId = 3, completed = 2, total = 5),
                )
                expectNoEvents()
            }
        }

    @Test
    fun `running sync without progress is replayed without progress`() = runTest {
        // Arrange
        testSubject.emit(SyncEvent.FolderSyncStarted(accountId, folderId = 1))

        // Act
        testSubject.observe().test {
            // Assert
            assertThat(awaitItem()).isEqualTo(SyncEvent.FolderSyncStarted(accountId, folderId = 1))
            expectNoEvents()
        }
    }

    @Test
    fun `restarting a folder sync resets its progress`() = runTest {
        // Arrange
        testSubject.emit(SyncEvent.FolderSyncStarted(accountId, folderId = 1))
        testSubject.emit(SyncEvent.FolderSyncProgress(accountId, folderId = 1, completed = 3, total = 3))
        testSubject.emit(SyncEvent.FolderSyncFinished(accountId, folderId = 1))
        testSubject.emit(SyncEvent.FolderSyncStarted(accountId, folderId = 1))

        // Act
        testSubject.observe().test {
            // Assert
            assertThat(awaitItem()).isEqualTo(SyncEvent.FolderSyncStarted(accountId, folderId = 1))
            expectNoEvents()
        }
    }

    @Test
    fun `events that aren't about a folder's sync state aren't replayed`() = runTest {
        // Arrange
        testSubject.emit(SyncEvent.CheckMailStarted(accountId))
        testSubject.emit(SyncEvent.FolderHeadersProgress(accountId, "INBOX", completed = 1, total = 2))
        testSubject.emit(SyncEvent.MessageUidChanged(accountId, folderId = 1, oldUid = "local", newUid = "1"))

        // Act
        testSubject.observe().test {
            // Assert
            expectNoEvents()
        }
    }

    @Test
    fun `forgetting an account drops only its folder syncs`() = runTest {
        // Arrange
        testSubject.emit(SyncEvent.FolderSyncStarted(accountId, folderId = 1))
        testSubject.emit(SyncEvent.FolderSyncFinished(accountId, folderId = 1))
        testSubject.emit(SyncEvent.FolderSyncStarted(otherAccountId, folderId = 1))
        testSubject.emit(SyncEvent.FolderSyncFinished(otherAccountId, folderId = 1))

        // Act
        testSubject.forgetAccount(accountId)

        // Assert
        testSubject.observe().test {
            assertThat(awaitItem()).isEqualTo(SyncEvent.FolderSyncFinished(otherAccountId, folderId = 1))
            expectNoEvents()
        }
    }
}
