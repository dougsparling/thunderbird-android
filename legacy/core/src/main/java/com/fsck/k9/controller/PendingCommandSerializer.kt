package com.fsck.k9.controller

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi

/**
 * Reads and writes the JSON stored for each [PendingCommand] in the `pending_commands` table.
 *
 * Commands are JSON objects of their properties, `null` properties left out, flags by name. Unknown fields are ignored
 * when reading, e.g. `databaseId`, which versions before the Kotlin port wrote too.
 */
class PendingCommandSerializer private constructor() {
    private val adapters: Map<String, JsonAdapter<out PendingCommand>> = Moshi.Builder().build().let { moshi ->
        mapOf(
            COMMAND_MOVE_OR_COPY to moshi.adapter(PendingMoveOrCopy::class.java),
            COMMAND_MOVE_AND_MARK_AS_READ to moshi.adapter(PendingMoveAndMarkAsRead::class.java),
            COMMAND_APPEND to moshi.adapter(PendingAppend::class.java),
            COMMAND_REPLACE to moshi.adapter(PendingReplace::class.java),
            COMMAND_EMPTY_SPAM to moshi.adapter(PendingEmptySpam::class.java),
            COMMAND_EMPTY_TRASH to moshi.adapter(PendingEmptyTrash::class.java),
            COMMAND_EXPUNGE to moshi.adapter(PendingExpunge::class.java),
            COMMAND_MARK_ALL_AS_READ to moshi.adapter(PendingMarkAllAsRead::class.java),
            COMMAND_SET_FLAG to moshi.adapter(PendingSetFlag::class.java),
            COMMAND_DELETE to moshi.adapter(PendingDelete::class.java),
        )
    }

    fun <T : PendingCommand> serialize(command: T): String {
        @Suppress("UNCHECKED_CAST")
        val adapter = adapters[command.commandName] as JsonAdapter<T>?
            ?: throw IllegalArgumentException("Unsupported pending command type!")
        return adapter.toJson(command)
    }

    fun unserialize(databaseId: Long, commandName: String, data: String): PendingCommand {
        val adapter = adapters[commandName] ?: throw IllegalArgumentException("Unsupported pending command type!")
        val command = adapter.fromJson(data) ?: error("Pending command '$commandName' has no data")
        command.databaseId = databaseId
        return command
    }

    companion object {
        private val INSTANCE = PendingCommandSerializer()

        @JvmStatic
        fun getInstance(): PendingCommandSerializer = INSTANCE
    }
}
