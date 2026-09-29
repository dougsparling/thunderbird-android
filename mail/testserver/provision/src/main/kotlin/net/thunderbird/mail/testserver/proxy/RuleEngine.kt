package net.thunderbird.mail.testserver.proxy

import java.util.concurrent.atomic.AtomicInteger

/**
 * Interprets one applied [NetworkRules]: matches events against the fault rules and tracks how often each rule may
 * still fire. Counts are shared by all connections. Thread-safe.
 *
 * Each `match*` function consumes one use of the rule it returns; at most one rule fires per event, the first
 * matching rule in declaration order that has uses left.
 */
internal class RuleEngine(val rules: NetworkRules) {
    private val remaining = rules.faults.map { rule -> rule.limit?.let { AtomicInteger(it) } }
    private val connections = AtomicInteger()

    private val byteThresholds: Map<Direction, List<Long>> = Direction.entries.associateWith { direction ->
        rules.faults.mapNotNull { (it.trigger as? FaultTrigger.AfterBytes)?.takeIf { t -> t.direction == direction } }
            .map { it.bytes }
            .distinct()
            .sorted()
    }

    /** Counts a new connection; returns its number since these rules were applied (1-based). */
    fun nextConnectionNumber(): Int = connections.incrementAndGet()

    fun matchConnect(connectionNumber: Int): FaultRule? = match { trigger ->
        trigger is FaultTrigger.Connect &&
            (trigger.connectionNumber == null || trigger.connectionNumber == connectionNumber)
    }

    /** [commandName] as produced by [ImapSyntax.parseCommand]. */
    fun matchCommand(commandName: String): FaultRule? = match { trigger ->
        trigger is FaultTrigger.ImapCommand && trigger.name == commandName
    }

    /** [line] is the response line without its terminator. */
    fun matchResponse(line: String): FaultRule? = match { trigger ->
        trigger is FaultTrigger.ImapResponse && trigger.predicate(line)
    }

    /** Byte counts at which [FaultTrigger.AfterBytes] rules for [direction] fire, ascending. */
    fun byteThresholds(direction: Direction): List<Long> = byteThresholds.getValue(direction)

    fun matchBytes(direction: Direction, bytes: Long): FaultRule? = match { trigger ->
        trigger is FaultTrigger.AfterBytes && trigger.direction == direction && trigger.bytes == bytes
    }

    private inline fun match(predicate: (FaultTrigger) -> Boolean): FaultRule? {
        rules.faults.forEachIndexed { index, rule ->
            if (predicate(rule.trigger) && tryConsume(index)) return rule
        }
        return null
    }

    private fun tryConsume(index: Int): Boolean {
        val counter = remaining[index] ?: return true
        return counter.getAndUpdate { if (it > 0) it - 1 else it } > 0
    }
}
