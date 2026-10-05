package net.thunderbird.mail.testserver.provision

import java.io.IOException

/** The server answered a command with a tagged `NO` or `BAD`. The message names the command, never its arguments. */
class ImapCommandException(
    val command: String,
    val status: String,
    val responseText: String,
    context: String? = null,
) : IOException(
    buildString {
        append("IMAP ").append(command)
        if (context != null) append(" (").append(context).append(')')
        append(" failed: ").append(status).append(' ').append(responseText)
    },
)

/** The server sent something the client couldn't make sense of, or closed the connection with `BYE`. */
class ImapProtocolException(message: String) : IOException(message)
