package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

internal class SyncAllAccountsTest {

    @Test
    fun `should sync mail`() = runTest {
        val mailSynchronizer = FakeMailSynchronizer()
        val testSubject = SyncAllAccounts(
            mailSynchronizer = mailSynchronizer,
        )

        val result = testSubject().first()

        assertThat(result.isSuccess).isEqualTo(true)
        assertThat(mailSynchronizer.recordedCheckMail).containsExactly(
            CheckMailParameters(
                accountId = null,
                ignoreLastCheckedTime = true,
                useManualWakeLock = true,
                notify = true,
            ),
        )
    }
}
