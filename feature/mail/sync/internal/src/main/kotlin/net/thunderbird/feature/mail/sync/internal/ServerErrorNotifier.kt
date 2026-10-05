package net.thunderbird.feature.mail.sync.internal

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.CertificateValidationException
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.notification.NotificationController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.featureflag.keys.GeneratedFeatureFlagKey
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.notification.api.NotificationManager
import net.thunderbird.feature.notification.api.content.AuthenticationErrorNotification

private const val TAG = "ServerErrorNotifier"

/** Tells the user about problems with an account's servers that they have to fix: authentication and certificates. */
internal class ServerErrorNotifier(
    private val accounts: AccountStores,
    private val notificationController: NotificationController,
    private val notificationManager: NotificationManager,
    private val featureFlagProvider: FeatureFlagProvider,
    private val logger: Logger,
    mainImmediateDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val notificationScope = CoroutineScope(SupervisorJob() + mainImmediateDispatcher)

    fun handleAuthenticationFailure(account: LegacyAccountDto, incoming: Boolean) {
        if (account.shouldMigrateToOAuth) {
            migrateAccountToOAuth(account)
        }

        if (featureFlagProvider.provide(GeneratedFeatureFlagKey.DISPLAY_IN_APP_NOTIFICATIONS).isEnabled()) {
            logger.debug(TAG) { "handleAuthenticationFailure: sending in-app notification" }
            val notification = createAuthenticationErrorNotification(account, incoming)
            notificationManager.send(notification)
                .onEach { outcome -> logger.verbose(TAG) { "notificationSender outcome = $outcome" } }
                .launchIn(notificationScope)
        }

        if (featureFlagProvider
                .provide(GeneratedFeatureFlagKey.USE_NOTIFICATION_SENDER_FOR_SYSTEM_NOTIFICATIONS)
                .isDisabled()
        ) {
            logger.debug(TAG) {
                "handleAuthenticationFailure: sending system notification via old notification controller"
            }
            notificationController.showAuthenticationErrorNotification(account, incoming)
        }
    }

    fun handleException(account: LegacyAccountDto, exception: Exception) {
        if (exception is AuthenticationFailedException) {
            handleAuthenticationFailure(account, incoming = true)
        } else {
            notifyUserIfCertificateProblem(account, exception, incoming = true)
        }
    }

    fun notifyUserIfCertificateProblem(account: LegacyAccountDto, exception: Exception, incoming: Boolean) {
        if (exception is CertificateValidationException) {
            notificationController.showCertificateErrorNotification(account, incoming)
        }
    }

    fun checkAuthenticationProblem(account: LegacyAccountDto) {
        // checking incoming server configuration
        if (isAuthenticationProblem(account, incoming = true)) {
            handleAuthenticationFailure(account, incoming = true)
            return
        } else {
            clearAuthenticationErrorNotification(account, incoming = true, clearOnlyForOAuthAccounts = true)
        }

        // checking outgoing server configuration
        if (isAuthenticationProblem(account, incoming = false)) {
            handleAuthenticationFailure(account, incoming = false)
        } else {
            clearAuthenticationErrorNotification(account, incoming = false, clearOnlyForOAuthAccounts = true)
        }
    }

    /** Whether signing in to the server will fail anyway: the password is missing, or OAuth needs a sign-in. */
    fun isAuthenticationProblem(account: LegacyAccountDto, incoming: Boolean): Boolean {
        val serverSettings = account.serverSettings(incoming)
        return serverSettings.isMissingCredentials ||
            (serverSettings.authenticationType == AuthType.XOAUTH2 && account.oAuthState == null)
    }

    fun clearAuthenticationErrorNotification(
        account: LegacyAccountDto,
        incoming: Boolean,
        clearOnlyForOAuthAccounts: Boolean,
    ) {
        if (featureFlagProvider.provide(GeneratedFeatureFlagKey.DISPLAY_IN_APP_NOTIFICATIONS).isEnabled()) {
            val serverSettings = account.serverSettings(incoming)
            val shouldClear = !clearOnlyForOAuthAccounts || serverSettings.authenticationType == AuthType.XOAUTH2

            if (shouldClear) {
                val notification = createAuthenticationErrorNotification(account, incoming)
                notificationManager.dismiss(notification)
                    .onEach { outcome -> logger.verbose(TAG) { "notificationDismisser outcome = $outcome" } }
                    .launchIn(notificationScope)
            }
        }
    }

    private fun createAuthenticationErrorNotification(
        account: LegacyAccountDto,
        incoming: Boolean,
    ): AuthenticationErrorNotification = runBlocking {
        AuthenticationErrorNotification(
            accountUuid = account.uuid,
            accountDisplayName = account.displayName,
            accountNumber = account.accountNumber,
            isIncomingServerError = incoming,
        )
    }

    private fun migrateAccountToOAuth(account: LegacyAccountDto) {
        account.incomingServerSettings = account.incomingServerSettings.newAuthenticationType(AuthType.XOAUTH2)
        account.outgoingServerSettings = account.outgoingServerSettings.newAuthenticationType(AuthType.XOAUTH2)
        account.shouldMigrateToOAuth = false

        accounts.save(account)
    }

    private fun LegacyAccountDto.serverSettings(incoming: Boolean): ServerSettings {
        return if (incoming) incomingServerSettings else outgoingServerSettings
    }
}
