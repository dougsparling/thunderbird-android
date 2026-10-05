package com.fsck.k9

import app.k9mail.legacy.mailstore.MessageListChangedListener

/** Listeners that are told about every change to the messages of any account, for as long as the app runs. */
interface MessageListChangedListenerProvider {
    val listeners: List<MessageListChangedListener>
}

class DefaultMessageListChangedListenerProvider(
    override val listeners: List<MessageListChangedListener>,
) : MessageListChangedListenerProvider
