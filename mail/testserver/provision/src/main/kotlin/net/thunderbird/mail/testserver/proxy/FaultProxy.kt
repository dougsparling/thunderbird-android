package net.thunderbird.mail.testserver.proxy

import java.io.Closeable

/**
 * An in-process TCP proxy between the app under test and the test mail server.
 *
 * It records a transcript of every connection for diagnostics and applies [NetworkRules] to inject faults. One proxy
 * is created per test so rules never leak between tests running in parallel.
 *
 * The proxy understands IMAP framing (lines and literals) for the transcript and for IMAP rules. Any other protocol is
 * forwarded unchanged; connection and byte rules still apply to it.
 *
 * The rules model and its DSL (`networkRules { ... }`) live in this package.
 */
interface FaultProxy : Closeable {
    /** Local port the app should connect to (host is 127.0.0.1). */
    val port: Int

    /** The rules currently in effect; [NetworkRules.NONE] until [apply] is called. */
    val activeRules: NetworkRules

    /**
     * Replaces the active rules. Rules take effect for data seen after this call, including on connections that are
     * already open. Use counts (`once()`, `times(n)`) and `onConnect(n)` numbering start fresh with each call.
     */
    fun apply(rules: NetworkRules)

    /** Human-readable transcript of all connections so far, in order, with direction markers. */
    fun transcript(): String

    /** Ends every open connection now, cleanly or with a TCP RST if [reset] is true. */
    fun disconnectAll(reset: Boolean = false)

    /** Stops accepting connections, closes all connections and waits for the proxy's threads to finish. */
    override fun close()

    companion object {
        fun start(upstreamHost: String, upstreamPort: Int): FaultProxy =
            start(upstreamHost, upstreamPort, Transcript.DEFAULT_MAX_CHARS)

        /** Like [start], with the transcript capped at [maxTranscriptChars] characters. */
        fun start(upstreamHost: String, upstreamPort: Int, maxTranscriptChars: Int): FaultProxy =
            SocketFaultProxy(upstreamHost, upstreamPort, maxTranscriptChars)
    }
}

/**
 * Applies [rules] while [block] runs, then restores the rules that were active before. The restored rules start with
 * fresh use counts.
 */
inline fun <T> FaultProxy.during(rules: NetworkRules, block: () -> T): T {
    val previous = activeRules
    apply(rules)
    try {
        return block()
    } finally {
        apply(previous)
    }
}
