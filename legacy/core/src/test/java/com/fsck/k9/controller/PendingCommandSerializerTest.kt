package com.fsck.k9.controller

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.prop
import com.fsck.k9.K9RobolectricTest
import net.thunderbird.core.common.mail.Flag
import org.junit.Test

class PendingCommandSerializerTest : K9RobolectricTest() {
    private val testSubject = PendingCommandSerializer.getInstance()

    @Test
    fun `serialize() writes fields in alphabetical order, including databaseId`() {
        // Arrange
        val command = PendingSetFlag.create(FOLDER_ID, true, Flag.SEEN, listOf("uid_1", "uid_2"))

        // Act
        val json = testSubject.serialize(command)

        // Assert
        assertThat(json).isEqualTo(
            """{"databaseId":0,"flag":"SEEN","folderId":42,"newState":true,"uids":["uid_1","uid_2"]}""",
        )
    }

    @Test
    fun `serialize() leaves out null fields`() {
        // Arrange
        val command = PendingMoveOrCopy.create(FOLDER_ID, DEST_FOLDER_ID, true, UID_MAP)

        // Act
        val json = testSubject.serialize(command)

        // Assert
        assertThat(json).isEqualTo(
            """{"databaseId":0,"destFolderId":23,"isCopy":true,"newUidMap":{"uid_1":"uid_other_1",""" +
                """"uid_2":"uid_other_2"},"srcFolderId":42}""",
        )
    }

    @Test
    fun `unserialize() reads commands stored by earlier versions`() {
        // Arrange
        val json = """{"databaseId":7,"folderId":42,"uid":"uid"}"""

        // Act
        val command = testSubject.unserialize(DATABASE_ID, COMMAND_APPEND, json)

        // Assert
        assertThat(command).isInstanceOf<PendingAppend>().all {
            prop("databaseId") { it.databaseId }.isEqualTo(DATABASE_ID)
            prop("folderId") { it.folderId }.isEqualTo(FOLDER_ID)
            prop("uid") { it.uid }.isEqualTo("uid")
        }
    }

    @Test
    fun `unserialize() reads move commands that only have UIDs`() {
        // Arrange
        val json = """{"databaseId":0,"destFolderId":23,"isCopy":false,"srcFolderId":42,"uids":["uid_1"]}"""

        // Act
        val command = testSubject.unserialize(DATABASE_ID, COMMAND_MOVE_OR_COPY, json)

        // Assert
        assertThat(command).isInstanceOf<PendingMoveOrCopy>().all {
            prop("uids") { it.uids }.isEqualTo(listOf("uid_1"))
            prop("newUidMap") { it.newUidMap }.isNull()
        }
    }

    @Test
    fun `serialized commands read back the same`() {
        // Arrange
        val commands = listOf(
            PendingMoveOrCopy.create(FOLDER_ID, DEST_FOLDER_ID, false, UID_MAP),
            PendingMoveAndMarkAsRead.create(FOLDER_ID, DEST_FOLDER_ID, UID_MAP),
            PendingAppend.create(FOLDER_ID, "uid"),
            PendingReplace.create(FOLDER_ID, 1L, Long.MAX_VALUE),
            PendingEmptySpam.create(),
            PendingEmptyTrash.create(),
            PendingExpunge.create(FOLDER_ID),
            PendingMarkAllAsRead.create(FOLDER_ID),
            PendingSetFlag.create(FOLDER_ID, false, Flag.FLAGGED, listOf("uid")),
            PendingDelete.create(FOLDER_ID, listOf("uid_1", "uid_2")),
        )

        for (command in commands) {
            // Act
            val json = testSubject.serialize(command)
            val result = testSubject.unserialize(databaseId = 0, command.commandName, json)

            // Assert
            assertThat(testSubject.serialize(result)).isEqualTo(json)
        }
    }

    @Test
    fun `unserialize() with unknown command name fails`() {
        assertFailure {
            testSubject.unserialize(DATABASE_ID, "BAD_COMMAND_NAME", "{}")
        }.isInstanceOf<IllegalArgumentException>()
    }

    private companion object {
        const val DATABASE_ID = 123L
        const val FOLDER_ID = 42L
        const val DEST_FOLDER_ID = 23L
        val UID_MAP = linkedMapOf("uid_1" to "uid_other_1", "uid_2" to "uid_other_2")
    }
}
