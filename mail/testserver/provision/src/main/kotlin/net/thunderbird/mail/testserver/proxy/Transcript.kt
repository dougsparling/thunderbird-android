package net.thunderbird.mail.testserver.proxy

import java.util.concurrent.TimeUnit

/**
 * Thread-safe, size-bounded, append-only transcript.
 *
 * When the transcript grows beyond [maxChars], the first half of the budget keeps the earliest entries (greeting,
 * capabilities, login) and the rest keeps the most recent entries; the dropped middle is replaced by a marker.
 *
 * Entry format: `<ms since start>ms [c<connection>] <marker> <text>`, for example `    12ms [c1] C: a001 NOOP`.
 */
internal class Transcript(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val startNanos = nanoTime()
    private val lock = Any()
    private val head = StringBuilder()
    private val tail = ArrayDeque<String>()
    private var tailChars = 0
    private var omittedEntries = 0
    private var omittedChars = 0L

    private val headBudget = maxChars / 2
    private val tailBudget = maxChars - headBudget

    init {
        require(maxChars > 0 && maxEntryChars > 0)
    }

    /** Records one entry. [connectionId] is null for proxy-wide events. */
    fun record(connectionId: Int?, marker: String, text: String) {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanoTime() - startNanos)
        val source = if (connectionId == null) "[proxy]" else "[c$connectionId]"
        val body = if (text.length > maxEntryChars) {
            text.take(maxEntryChars) + " ... [entry truncated, ${text.length} chars]"
        } else {
            text
        }
        val entry = "${elapsedMs.toString().padStart(TIME_WIDTH)}ms $source $marker $body\n"
        append(entry)
    }

    private fun append(entry: String) = synchronized(lock) {
        if (tail.isEmpty() && head.length + entry.length <= headBudget) {
            head.append(entry)
            return@synchronized
        }
        tail.addLast(entry)
        tailChars += entry.length
        while (tailChars > tailBudget && tail.size > 1) {
            val dropped = tail.removeFirst()
            tailChars -= dropped.length
            omittedEntries++
            omittedChars += dropped.length
        }
    }

    override fun toString(): String = synchronized(lock) {
        buildString {
            append(head)
            if (omittedEntries > 0) {
                append("... [transcript truncated: $omittedEntries entries ($omittedChars chars) omitted] ...\n")
            }
            tail.forEach { append(it) }
        }
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 4 * 1024 * 1024
        const val DEFAULT_MAX_ENTRY_CHARS = 2000
        private const val TIME_WIDTH = 6
    }
}

/** Escapes bytes for display: printable ASCII as is, `\r`, `\n`, `\t`, `\\` and `\xNN` for everything else. */
internal fun escapeBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
    buildString(length) {
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and BYTE_MASK
            when {
                value == '\r'.code -> append("\\r")
                value == '\n'.code -> append("\\n")
                value == '\t'.code -> append("\\t")
                value == '\\'.code -> append("\\\\")
                value in PRINTABLE -> append(value.toChar())
                else -> append("\\x").append(HEX[value shr NIBBLE]).append(HEX[value and NIBBLE_MASK])
            }
        }
    }

private const val BYTE_MASK = 0xFF
private const val NIBBLE = 4
private const val NIBBLE_MASK = 0x0F
private const val HEX = "0123456789ABCDEF"
private const val FIRST_PRINTABLE = 0x20
private const val LAST_PRINTABLE = 0x7E
private val PRINTABLE = FIRST_PRINTABLE..LAST_PRINTABLE
