package app.k9mail.feature.widget.unread

import app.k9mail.legacy.mailstore.MessageListChangedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import net.thunderbird.core.logging.Logger

private const val TAG = "UnreadWidgetUpdateListener"

/**
 * Updates the unread widgets when messages change.
 *
 * Changes are reported by whoever writes to the message store, often many in a row; the widgets are updated once for
 * any number of changes made while the previous update ran.
 */
class UnreadWidgetUpdateListener(
    private val unreadWidgetUpdater: UnreadWidgetUpdater,
    private val logger: Logger,
    coroutineScope: CoroutineScope,
) : MessageListChangedListener {
    private val changes = Channel<Unit>(Channel.CONFLATED)

    init {
        coroutineScope.launch {
            for (change in changes) {
                updateUnreadWidget()
            }
        }
    }

    override fun onMessageListChanged() {
        changes.trySend(Unit)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun updateUnreadWidget() {
        try {
            unreadWidgetUpdater.updateAll()
        } catch (e: Exception) {
            logger.error(TAG, e) { "Error while updating unread widget(s)" }
        }
    }
}
