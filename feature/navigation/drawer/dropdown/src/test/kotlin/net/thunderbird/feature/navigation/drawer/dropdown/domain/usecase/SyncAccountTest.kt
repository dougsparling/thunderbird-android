package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.navigation.drawer.dropdown.ui.FakeData

internal class SyncAccountTest {

    @Test
    fun `should sync mail with account`() = runTest {
        val account = FakeData.ACCOUNT
        val mailSynchronizer = FakeMailSynchronizer()
        val testSubject = SyncAccount(
            mailSynchronizer = mailSynchronizer,
        )

        val result = testSubject(account.uuid).first()

        assertThat(result.isSuccess).isEqualTo(true)
        assertThat(mailSynchronizer.recordedCheckMail).containsExactly(
            CheckMailParameters(
                accountId = AccountIdFactory.of(account.uuid),
                ignoreLastCheckedTime = true,
                useManualWakeLock = true,
                notify = true,
            ),
        )
    }
}
