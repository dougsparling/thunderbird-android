package com.fsck.k9.controller

import net.thunderbird.core.common.mail.Flag

internal const val COMMAND_APPEND = "append"
internal const val COMMAND_REPLACE = "replace"
internal const val COMMAND_MARK_ALL_AS_READ = "mark_all_as_read"
internal const val COMMAND_SET_FLAG = "set_flag"
internal const val COMMAND_DELETE = "delete"
internal const val COMMAND_EXPUNGE = "expunge"
internal const val COMMAND_MOVE_OR_COPY = "move_or_copy"
internal const val COMMAND_MOVE_AND_MARK_AS_READ = "move_and_mark_as_read"
internal const val COMMAND_EMPTY_SPAM = "empty_spam"
internal const val COMMAND_EMPTY_TRASH = "empty_trash"

/**
 * A change made locally that still has to reach the server.
 *
 * Commands are stored in the account's `pending_commands` table: [commandName] in the `command` column and the
 * command's properties as JSON (see [PendingCommandSerializer]) in the `data` column. Property names are part of that
 * format.
 */
sealed class PendingCommand {
    /** The command's row in the `pending_commands` table; set when the command is read from there. */
    @JvmField
    var databaseId: Long = 0

    abstract val commandName: String
}

class PendingMoveOrCopy internal constructor(
    @JvmField val srcFolderId: Long,
    @JvmField val destFolderId: Long,
    @JvmField val isCopy: Boolean,
    /** Only in commands stored by old versions, which didn't record the messages' new UIDs. */
    @JvmField val uids: List<String>?,
    @JvmField val newUidMap: Map<String, String>?,
) : PendingCommand() {
    override val commandName: String
        get() = COMMAND_MOVE_OR_COPY

    companion object {
        @JvmStatic
        fun create(
            srcFolderId: Long,
            destFolderId: Long,
            isCopy: Boolean,
            uidMap: Map<String, String>,
        ): PendingMoveOrCopy {
            requireValidUids(uidMap)
            return PendingMoveOrCopy(srcFolderId, destFolderId, isCopy, uids = null, newUidMap = uidMap)
        }
    }
}

class PendingMoveAndMarkAsRead internal constructor(
    @JvmField val srcFolderId: Long,
    @JvmField val destFolderId: Long,
    @JvmField val newUidMap: Map<String, String>,
) : PendingCommand() {
    override val commandName: String
        get() = COMMAND_MOVE_AND_MARK_AS_READ

    companion object {
        @JvmStatic
        fun create(srcFolderId: Long, destFolderId: Long, uidMap: Map<String, String>): PendingMoveAndMarkAsRead {
            requireValidUids(uidMap)
            return PendingMoveAndMarkAsRead(srcFolderId, destFolderId, uidMap)
        }
    }
}

class PendingEmptySpam internal constructor() : PendingCommand() {
    override val commandName: String
        get() = COMMAND_EMPTY_SPAM

    companion object {
        @JvmStatic
        fun create() = PendingEmptySpam()
    }
}

class PendingEmptyTrash internal constructor() : PendingCommand() {
    override val commandName: String
        get() = COMMAND_EMPTY_TRASH

    companion object {
        @JvmStatic
        fun create() = PendingEmptyTrash()
    }
}

class PendingSetFlag internal constructor(
    @JvmField val folderId: Long,
    @JvmField val newState: Boolean,
    @JvmField val flag: Flag,
    @JvmField val uids: List<String>,
) : PendingCommand() {
    override val commandName: String
        get() = COMMAND_SET_FLAG

    companion object {
        @JvmStatic
        fun create(folderId: Long, newState: Boolean, flag: Flag, uids: List<String>): PendingSetFlag {
            requireValidUids(uids)
            return PendingSetFlag(folderId, newState, flag, uids)
        }
    }
}

class PendingAppend internal constructor(
    @JvmField val folderId: Long,
    @JvmField val uid: String,
) : PendingCommand() {
    override val commandName: String
        get() = COMMAND_APPEND

    companion object {
        @JvmStatic
        fun create(folderId: Long, uid: String) = PendingAppend(folderId, uid)
    }
}

class PendingReplace internal constructor(
    @JvmField val folderId: Long,
    @JvmField val uploadMessageId: Long,
    @JvmField val deleteMessageId: Long,
) : PendingCommand() {
    override val commandName: String
        get() = COMMAND_REPLACE

    companion object {
        @JvmStatic
        fun create(folderId: Long, uploadMessageId: Long, deleteMessageId: Long) =
            PendingReplace(folderId, uploadMessageId, deleteMessageId)
    }
}

class PendingMarkAllAsRead internal constructor(
    @JvmField val folderId: Long,
) : PendingCommand() {
    override val commandName: String
        get() = COMMAND_MARK_ALL_AS_READ

    companion object {
        @JvmStatic
        fun create(folderId: Long) = PendingMarkAllAsRead(folderId)
    }
}

class PendingDelete internal constructor(
    @JvmField val folderId: Long,
    @JvmField val uids: List<String>,
) : PendingCommand() {
    override val commandName: String
        get() = COMMAND_DELETE

    companion object {
        @JvmStatic
        fun create(folderId: Long, uids: List<String>): PendingDelete {
            requireValidUids(uids)
            return PendingDelete(folderId, uids)
        }
    }
}

class PendingExpunge internal constructor(
    @JvmField val folderId: Long,
) : PendingCommand() {
    override val commandName: String
        get() = COMMAND_EXPUNGE

    companion object {
        @JvmStatic
        fun create(folderId: Long) = PendingExpunge(folderId)
    }
}
