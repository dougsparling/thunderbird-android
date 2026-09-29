package net.thunderbird.mail.testserver.proxy

import kotlin.time.Duration

/** Direction of traffic through the proxy. */
enum class Direction {
    /** From the app (client) to the mail server. */
    UPSTREAM,

    /** From the mail server to the app (client). */
    DOWNSTREAM,
}

/** When an [FaultTrigger.ImapCommand] rule runs its actions, relative to the matching command. */
enum class CommandTiming {
    /**
     * The client's command line is held before it reaches the server and the actions run. If the actions end the
     * connection (disconnect, reset, stall) the server never sees the command; after a delay it is forwarded.
     */
    BEFORE_SERVER_SEES,

    /**
     * The command is forwarded. When the server's tagged response for the command's tag arrives, the actions run
     * before that response is forwarded. With disconnect/reset/stall the server has applied the command but the
     * client never learns the result.
     */
    AFTER_SERVER_RESPONDS,
}

/**
 * What the proxy does when a rule fires. Actions run in order; disconnect, reset, stall, refuse and respond end the
 * list.
 */
sealed interface FaultAction {
    val description: String

    /** Closes both sides of the connection cleanly (FIN). */
    data object Disconnect : FaultAction {
        override val description = "disconnect"
    }

    /** Aborts both sides of the connection with a TCP RST (SO_LINGER 0). The app sees "connection reset". */
    data object Reset : FaultAction {
        override val description = "reset"
    }

    /** Pauses forwarding on the connection for [duration], then continues. */
    data class Delay(val duration: Duration) : FaultAction {
        override val description = "delay $duration"
    }

    /**
     * Stops forwarding in both directions until the proxy is closed. The connection stays open.
     *
     * The app's socket read timeout is 60 seconds, so a scenario that waits for the app to notice a stall will be
     * slow. Prefer [Disconnect] or [Reset] unless the stall itself is what is being tested.
     */
    data object Stall : FaultAction {
        override val description = "stall"
    }

    /**
     * Only valid in `onConnect` rules. The proxy accepts the TCP connection and immediately aborts it with a RST,
     * before connecting upstream. The app sees a reset on its first read rather than a refused `connect()`.
     */
    data object Refuse : FaultAction {
        override val description = "refuse"
    }

    /**
     * Only valid in `beforeServerSees` command rules. The proxy answers the command itself with the client's tag
     * followed by [text], e.g. `a12 NO [UNAVAILABLE] Try again later`, and drops the command (including any literal
     * data belonging to it), so the server never sees it. The connection stays open.
     */
    data class Respond(val text: String) : FaultAction {
        override val description = "respond \"$text\""
    }
}

/** What makes a [FaultRule] fire. */
sealed interface FaultTrigger {
    val description: String

    /**
     * A client command line whose command name equals [name] (upper case, single spaces, e.g. `UID FETCH`) and, if
     * [arguments] is set, whose arguments match it. Bytes inside literals and continuation lines are never treated as
     * commands.
     */
    data class ImapCommand(
        val name: String,
        val timing: CommandTiming,
        val arguments: ArgumentsMatcher? = null,
    ) : FaultTrigger {
        override val description: String
            get() = "onCommand $name" + arguments?.let { " [${it.label}]" }.orEmpty() + " " + when (timing) {
                CommandTiming.BEFORE_SERVER_SEES -> "beforeServerSees"
                CommandTiming.AFTER_SERVER_RESPONDS -> "afterServerResponds"
            }

        /** True if a command called [commandName] with [commandArguments] triggers this rule. */
        fun matches(commandName: String, commandArguments: String): Boolean =
            name == commandName && arguments?.predicate?.invoke(commandArguments) != false
    }

    /**
     * Decides from a command's arguments whether an [ImapCommand] rule fires. The arguments are the rest of the
     * command line after the command name, as sent (e.g. `1:* (FLAGS)` for `a5 UID FETCH 1:* (FLAGS)`), without the
     * line terminator and without literal data. [label] names the matcher in rule descriptions and the transcript.
     */
    class ArgumentsMatcher(val label: String, val predicate: (arguments: String) -> Boolean) {
        override fun toString() = "ArgumentsMatcher($label)"
    }

    /**
     * A server response line (not a literal, not the remainder of a line after a literal) for which [predicate]
     * returns true. The line is passed without its line terminator. Actions run before the line is forwarded.
     */
    class ImapResponse(val label: String, val predicate: (String) -> Boolean) : FaultTrigger {
        override val description: String
            get() = "onResponse $label"

        override fun toString() = "ImapResponse($label)"
    }

    /**
     * The [connectionNumber]th connection accepted after the rules were applied (1-based), or every connection when
     * it is null. Actions run before the proxy connects upstream.
     */
    data class Connect(val connectionNumber: Int?) : FaultTrigger {
        override val description: String
            get() = if (connectionNumber == null) "onConnect" else "onConnect #$connectionNumber"
    }

    /**
     * Fires once a connection has forwarded exactly [bytes] bytes in [direction]. The data is split so the first
     * [bytes] bytes are delivered before the actions run. Counted per connection from its start.
     */
    data class AfterBytes(val bytes: Long, val direction: Direction) : FaultTrigger {
        override val description: String
            get() = "afterBytes $bytes $direction"
    }
}

/**
 * A single fault: when [trigger] matches, run [actions]. [limit] is how often the rule may fire in total, across all
 * connections; null means every time.
 */
data class FaultRule(
    val trigger: FaultTrigger,
    val actions: List<FaultAction>,
    val limit: Int?,
) {
    val description: String
        get() = trigger.description

    fun describe(): String {
        val count = when (limit) {
            null -> "always"
            1 -> "once"
            else -> "$limit times"
        }
        return "${trigger.description} -> ${actions.joinToString(", ") { it.description }} [$count]"
    }
}

/**
 * The complete set of network conditions a [FaultProxy] applies. Build it with [networkRules].
 *
 * @property latency Added before forwarding each chunk of data read from either side.
 * @property bytesPerSecond Caps forwarding speed in each direction of each connection; null means unlimited.
 * @property refuseConnections When true every new connection is refused, simulating being offline.
 */
data class NetworkRules(
    val faults: List<FaultRule> = emptyList(),
    val latency: Duration = Duration.ZERO,
    val bytesPerSecond: Long? = null,
    val refuseConnections: Boolean = false,
) {
    fun describe(): String {
        val parts = buildList {
            faults.forEach { add(it.describe()) }
            if (latency > Duration.ZERO) add("latency $latency")
            bytesPerSecond?.let { add("throttle $it bytes/s") }
            if (refuseConnections) add("refuseConnections")
        }
        return if (parts.isEmpty()) "none" else parts.joinToString("; ")
    }

    companion object {
        /** No faults: the proxy forwards everything unchanged. */
        val NONE = NetworkRules()
    }
}

@DslMarker
annotation class NetworkRulesDsl

/**
 * Builds [NetworkRules]:
 *
 * ```
 * networkRules {
 *     imap.onCommand("UID STORE").afterServerResponds { disconnect() }.once()
 *     imap.onResponse { line -> line.startsWith("* 3 EXPUNGE") }.then { delay(500.milliseconds) }
 *     onConnect(2) { refuse() }
 *     latency(50.milliseconds)
 * }
 * ```
 *
 * Every fault rule fires once unless `.times(n)` or `.always()` is called on it.
 */
fun networkRules(block: NetworkRulesBuilder.() -> Unit): NetworkRules = NetworkRulesBuilder().apply(block).build()

@NetworkRulesDsl
class NetworkRulesBuilder internal constructor() {
    private val entries = mutableListOf<RuleEntry>()
    private var latency = Duration.ZERO
    private var bytesPerSecond: Long? = null
    private var refuseConnections = false

    /** IMAP-aware rules. */
    val imap = ImapRulesBuilder(this)

    /** Fires for the [connectionNumber]th connection after the rules are applied (1-based), or for any connection. */
    fun onConnect(connectionNumber: Int? = null, actions: FaultActionsBuilder.() -> Unit): RuleHandle {
        require(connectionNumber == null || connectionNumber > 0) { "connectionNumber must be positive" }
        return add(FaultTrigger.Connect(connectionNumber), actions, allowRefuse = true)
    }

    /** Fires once a connection has forwarded [bytes] bytes in [direction]. */
    fun afterBytes(bytes: Long, direction: Direction, actions: FaultActionsBuilder.() -> Unit): RuleHandle {
        require(bytes > 0) { "bytes must be positive" }
        return add(FaultTrigger.AfterBytes(bytes, direction), actions)
    }

    /** Delays each chunk of data in both directions by [duration]. */
    fun latency(duration: Duration) {
        require(!duration.isNegative()) { "latency must not be negative" }
        latency = duration
    }

    /** Limits each direction of each connection to [bytesPerSecond]. */
    fun throttle(bytesPerSecond: Long) {
        require(bytesPerSecond > 0) { "bytesPerSecond must be positive" }
        this.bytesPerSecond = bytesPerSecond
    }

    /** Refuses every new connection ("offline"). Existing connections are not affected. */
    fun refuseConnections() {
        refuseConnections = true
    }

    internal fun add(
        trigger: FaultTrigger,
        actions: FaultActionsBuilder.() -> Unit,
        allowRefuse: Boolean = false,
        allowRespond: Boolean = false,
    ): RuleHandle {
        val actionList = FaultActionsBuilder().apply(actions).build(allowRefuse, allowRespond)
        val entry = RuleEntry(trigger, actionList)
        entries += entry
        return RuleHandle(entry)
    }

    internal fun build() = NetworkRules(
        faults = entries.map { FaultRule(it.trigger, it.actions, it.limit) },
        latency = latency,
        bytesPerSecond = bytesPerSecond,
        refuseConnections = refuseConnections,
    )
}

internal class RuleEntry(val trigger: FaultTrigger, val actions: List<FaultAction>) {
    var limit: Int? = 1
}

/** Sets how often a rule may fire, counted across all connections. The default is once. */
@NetworkRulesDsl
class RuleHandle internal constructor(private val entry: RuleEntry) {
    fun once() = times(1)

    fun times(count: Int) {
        require(count > 0) { "count must be positive" }
        entry.limit = count
    }

    fun always() {
        entry.limit = null
    }
}

@NetworkRulesDsl
class ImapRulesBuilder internal constructor(private val parent: NetworkRulesBuilder) {
    /**
     * Matches client commands by name, case-insensitively, after the tag. Use the full name for two-word commands:
     * `onCommand("UID FETCH")` matches `a1 UID FETCH ...` but `onCommand("FETCH")` does not.
     */
    fun onCommand(name: String): CommandRuleBuilder = commandRule(name, arguments = null)

    /**
     * Like [onCommand], but only matches commands whose arguments satisfy [predicate], e.g. flag-only fetches:
     *
     * ```
     * imap.onCommand("UID FETCH") { args -> "FLAGS" in args && "BODY" !in args }
     * ```
     *
     * The arguments are the rest of the command line after the command name, see [FaultTrigger.ArgumentsMatcher].
     * [label] names the predicate in rule descriptions and the transcript.
     */
    fun onCommand(
        name: String,
        label: String = "<arguments predicate>",
        predicate: (arguments: String) -> Boolean,
    ): CommandRuleBuilder = commandRule(name, FaultTrigger.ArgumentsMatcher(label, predicate))

    private fun commandRule(name: String, arguments: FaultTrigger.ArgumentsMatcher?): CommandRuleBuilder {
        val normalized = ImapSyntax.normalizeCommandName(name)
        require(normalized.isNotEmpty()) { "command name must not be blank" }
        return CommandRuleBuilder(parent, normalized, arguments)
    }

    /** Matches server response lines. [label] names the rule in the transcript. */
    fun onResponse(label: String = "<predicate>", predicate: (line: String) -> Boolean): ResponseRuleBuilder {
        return ResponseRuleBuilder(parent, FaultTrigger.ImapResponse(label, predicate))
    }
}

@NetworkRulesDsl
class CommandRuleBuilder internal constructor(
    private val parent: NetworkRulesBuilder,
    private val name: String,
    private val arguments: FaultTrigger.ArgumentsMatcher?,
) {
    /** See [CommandTiming.BEFORE_SERVER_SEES]. The only timing that allows `respond()`. */
    fun beforeServerSees(actions: FaultActionsBuilder.() -> Unit): RuleHandle = parent.add(
        FaultTrigger.ImapCommand(name, CommandTiming.BEFORE_SERVER_SEES, arguments),
        actions,
        allowRespond = true,
    )

    /** See [CommandTiming.AFTER_SERVER_RESPONDS]. */
    fun afterServerResponds(actions: FaultActionsBuilder.() -> Unit): RuleHandle =
        parent.add(FaultTrigger.ImapCommand(name, CommandTiming.AFTER_SERVER_RESPONDS, arguments), actions)
}

@NetworkRulesDsl
class ResponseRuleBuilder internal constructor(
    private val parent: NetworkRulesBuilder,
    private val trigger: FaultTrigger.ImapResponse,
) {
    fun then(actions: FaultActionsBuilder.() -> Unit): RuleHandle = parent.add(trigger, actions)
}

@NetworkRulesDsl
class FaultActionsBuilder internal constructor() {
    private val actions = mutableListOf<FaultAction>()

    /** See [FaultAction.Disconnect]. */
    fun disconnect() {
        actions += FaultAction.Disconnect
    }

    /** See [FaultAction.Reset]. */
    fun reset() {
        actions += FaultAction.Reset
    }

    /** See [FaultAction.Delay]. */
    fun delay(duration: Duration) {
        require(!duration.isNegative()) { "delay must not be negative" }
        actions += FaultAction.Delay(duration)
    }

    /** See [FaultAction.Stall]. */
    fun stall() {
        actions += FaultAction.Stall
    }

    /** See [FaultAction.Refuse]. Only allowed in `onConnect`. */
    fun refuse() {
        actions += FaultAction.Refuse
    }

    /**
     * See [FaultAction.Respond]: answers the command with its tag and [text], e.g. `respond("NO [UNAVAILABLE] Busy")`.
     * Only allowed in `beforeServerSees`.
     */
    fun respond(text: String) {
        require(text.isNotBlank()) { "response text must not be blank" }
        require('\r' !in text && '\n' !in text) { "response text must be a single line" }
        actions += FaultAction.Respond(text)
    }

    internal fun build(allowRefuse: Boolean, allowRespond: Boolean): List<FaultAction> {
        require(actions.isNotEmpty()) { "A rule needs at least one action" }
        require(allowRefuse || FaultAction.Refuse !in actions) { "refuse() is only allowed in onConnect rules" }
        require(allowRespond || actions.none { it is FaultAction.Respond }) {
            "respond() is only allowed in beforeServerSees command rules"
        }
        val terminalIndex = actions.indexOfFirst { it.isTerminal }
        require(terminalIndex == -1 || terminalIndex == actions.lastIndex) {
            "${actions[terminalIndex].description} ends the connection and must be the last action"
        }
        return actions.toList()
    }
}

internal val FaultAction.isTerminal: Boolean
    get() = this !is FaultAction.Delay
