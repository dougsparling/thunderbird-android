package net.thunderbird.android.scenario

import net.thunderbird.mail.testserver.fixture.FolderBuilder

/**
 * Subjects of the conversation that [conversation] declares, oldest first. The threaded list shows it under
 * [THREAD_NEWEST].
 */
internal const val THREAD_ROOT = "Trip plans"
internal const val THREAD_REPLY = "Re: Trip plans"
internal const val THREAD_NEWEST = "Re: Re: Trip plans"
internal val THREAD_SUBJECTS = listOf(THREAD_ROOT, THREAD_REPLY, THREAD_NEWEST)

/** A message that isn't part of the conversation, to check that thread actions leave it alone. */
internal const val UNRELATED = "Unrelated"

/** Declares a three-message conversation (each message replies to the one before) in this folder. */
internal fun FolderBuilder.conversation() {
    message(THREAD_ROOT) { messageId("<trip-1@example.org>") }
    message(THREAD_REPLY) {
        messageId("<trip-2@example.org>")
        header("In-Reply-To", "<trip-1@example.org>")
        header("References", "<trip-1@example.org>")
    }
    message(THREAD_NEWEST) {
        messageId("<trip-3@example.org>")
        header("In-Reply-To", "<trip-2@example.org>")
        header("References", "<trip-1@example.org> <trip-2@example.org>")
    }
}
