package com.fsck.k9.controller

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import net.thunderbird.core.common.mail.Flag
import okio.Buffer

/**
 * Reads and writes the JSON stored for each [PendingCommand] in the `pending_commands` table.
 *
 * The format is what Moshi's reflection-based adapter produced for the original Java classes: one JSON object per
 * command with the command's fields (including `databaseId`) in alphabetical order, `null` fields left out, flags by
 * name. Unknown fields are ignored when reading.
 */
class PendingCommandSerializer private constructor() {
    fun <T : PendingCommand> serialize(command: T): String {
        val fields = command.toFields() ?: throw IllegalArgumentException("Unsupported pending command type!")
        fields["databaseId"] = command.databaseId

        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginObject()
            for ((name, value) in fields.toSortedMap()) {
                if (value != null) {
                    writer.name(name)
                    writer.writeValue(value)
                }
            }
            writer.endObject()
        }
        return buffer.readUtf8()
    }

    fun unserialize(databaseId: Long, commandName: String, data: String): PendingCommand {
        require(commandName in COMMAND_NAMES) { "Unsupported pending command type!" }

        val fields = JsonReader.of(Buffer().writeUtf8(data)).use { reader -> reader.readObject() }
        val command = fields.toCommand(commandName)
        command.databaseId = databaseId
        return command
    }

    private fun PendingCommand.toFields(): MutableMap<String, Any?>? = when (this) {
        is PendingMoveOrCopy -> mutableMapOf(
            "srcFolderId" to srcFolderId,
            "destFolderId" to destFolderId,
            "isCopy" to isCopy,
            "uids" to uids,
            "newUidMap" to newUidMap,
        )
        is PendingMoveAndMarkAsRead -> mutableMapOf(
            "srcFolderId" to srcFolderId,
            "destFolderId" to destFolderId,
            "newUidMap" to newUidMap,
        )
        is PendingAppend -> mutableMapOf("folderId" to folderId, "uid" to uid)
        is PendingReplace -> mutableMapOf(
            "folderId" to folderId,
            "uploadMessageId" to uploadMessageId,
            "deleteMessageId" to deleteMessageId,
        )
        is PendingEmptySpam -> mutableMapOf()
        is PendingEmptyTrash -> mutableMapOf()
        is PendingExpunge -> mutableMapOf("folderId" to folderId)
        is PendingMarkAllAsRead -> mutableMapOf("folderId" to folderId)
        is PendingSetFlag -> mutableMapOf(
            "folderId" to folderId,
            "newState" to newState,
            "flag" to flag.name,
            "uids" to uids,
        )
        is PendingDelete -> mutableMapOf("folderId" to folderId, "uids" to uids)
    }

    private fun Map<String, Any?>.toCommand(commandName: String): PendingCommand = when (commandName) {
        COMMAND_MOVE_OR_COPY -> PendingMoveOrCopy(
            srcFolderId = long("srcFolderId"),
            destFolderId = long("destFolderId"),
            isCopy = boolean("isCopy"),
            uids = stringListOrNull("uids"),
            newUidMap = stringMapOrNull("newUidMap"),
        )
        COMMAND_MOVE_AND_MARK_AS_READ -> PendingMoveAndMarkAsRead(
            srcFolderId = long("srcFolderId"),
            destFolderId = long("destFolderId"),
            newUidMap = stringMapOrNull("newUidMap") ?: missing("newUidMap"),
        )
        COMMAND_APPEND -> PendingAppend(folderId = long("folderId"), uid = string("uid"))
        COMMAND_REPLACE -> PendingReplace(
            folderId = long("folderId"),
            uploadMessageId = long("uploadMessageId"),
            deleteMessageId = long("deleteMessageId"),
        )
        COMMAND_EMPTY_SPAM -> PendingEmptySpam()
        COMMAND_EMPTY_TRASH -> PendingEmptyTrash()
        COMMAND_EXPUNGE -> PendingExpunge(folderId = long("folderId"))
        COMMAND_MARK_ALL_AS_READ -> PendingMarkAllAsRead(folderId = long("folderId"))
        COMMAND_SET_FLAG -> PendingSetFlag(
            folderId = long("folderId"),
            newState = boolean("newState"),
            flag = Flag.valueOf(string("flag")),
            uids = stringListOrNull("uids") ?: missing("uids"),
        )
        COMMAND_DELETE -> PendingDelete(
            folderId = long("folderId"),
            uids = stringListOrNull("uids") ?: missing("uids"),
        )
        else -> throw IllegalArgumentException("Unsupported pending command type!")
    }

    companion object {
        private val INSTANCE = PendingCommandSerializer()

        private val COMMAND_NAMES = setOf(
            COMMAND_MOVE_OR_COPY,
            COMMAND_MOVE_AND_MARK_AS_READ,
            COMMAND_APPEND,
            COMMAND_REPLACE,
            COMMAND_EMPTY_SPAM,
            COMMAND_EMPTY_TRASH,
            COMMAND_EXPUNGE,
            COMMAND_MARK_ALL_AS_READ,
            COMMAND_SET_FLAG,
            COMMAND_DELETE,
        )

        @JvmStatic
        fun getInstance(): PendingCommandSerializer = INSTANCE
    }
}

/** A JSON number is kept as its text, so that IDs are read back exactly. */
private class JsonNumber(val text: String)

private fun JsonWriter.writeValue(value: Any) {
    when (value) {
        is Long -> value(value)
        is Boolean -> value(value)
        is String -> value(value)
        is List<*> -> {
            beginArray()
            value.forEach { element -> value(element as String) }
            endArray()
        }
        is Map<*, *> -> {
            beginObject()
            for ((key, element) in value) {
                name(key as String)
                value(element as String)
            }
            endObject()
        }
        else -> error("Unsupported value: $value")
    }
}

private fun JsonReader.readObject(): Map<String, Any?> {
    val fields = mutableMapOf<String, Any?>()
    beginObject()
    while (hasNext()) {
        fields[nextName()] = readValue()
    }
    endObject()
    return fields
}

private fun JsonReader.readValue(): Any? = when (peek()) {
    JsonReader.Token.NULL -> nextNull<Any>()
    JsonReader.Token.NUMBER -> JsonNumber(nextString())
    JsonReader.Token.BOOLEAN -> nextBoolean()
    JsonReader.Token.STRING -> nextString()
    JsonReader.Token.BEGIN_ARRAY -> {
        val list = mutableListOf<Any?>()
        beginArray()
        while (hasNext()) {
            list.add(readValue())
        }
        endArray()
        list
    }
    JsonReader.Token.BEGIN_OBJECT -> readObject()
    else -> error("Unexpected JSON token: ${peek()}")
}

private fun missing(name: String): Nothing = throw IllegalArgumentException("Pending command lacks '$name'")

private fun Map<String, Any?>.long(name: String): Long {
    val value = this[name] as? JsonNumber ?: missing(name)
    return value.text.toLong()
}

private fun Map<String, Any?>.boolean(name: String): Boolean = this[name] as? Boolean ?: missing(name)

private fun Map<String, Any?>.string(name: String): String = this[name] as? String ?: missing(name)

private fun Map<String, Any?>.stringListOrNull(name: String): List<String>? {
    val value = this[name] as? List<*> ?: return null
    return value.map { it as String }
}

private fun Map<String, Any?>.stringMapOrNull(name: String): Map<String, String>? {
    val value = this[name] as? Map<*, *> ?: return null
    return value.entries.associate { (key, element) -> key as String to element as String }
}
