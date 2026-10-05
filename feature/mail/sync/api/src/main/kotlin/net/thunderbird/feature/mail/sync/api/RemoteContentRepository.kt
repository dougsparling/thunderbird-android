package net.thunderbird.feature.mail.sync.api

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mail.Part
import kotlinx.coroutines.flow.Flow
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

/**
 * Downloads what the app doesn't have yet: message bodies, attachments and server search results.
 *
 * Downloads run with the other work for the servers, ahead of background work. Server searches run on their own.
 */
interface RemoteContentRepository {
    /** Downloads [message] as far as the account downloads messages automatically. */
    suspend fun downloadPartial(message: MessageReference): Outcome<Unit, DownloadError>

    /** Downloads [message] completely. */
    suspend fun downloadComplete(message: MessageReference): Outcome<Unit, DownloadError>

    /** Downloads the content of [part], a part of a message the app has stored, and stores it with the message. */
    suspend fun downloadAttachment(part: Part): Outcome<Unit, DownloadError>

    /** Progress of attachment downloads, in bytes downloaded so far. */
    fun observeAttachmentProgress(): Flow<Int>

    /**
     * Searches the folder on the server and downloads the headers of the results the app doesn't have yet, up to the
     * account's limit. Cancelling the collection cancels the search.
     */
    fun searchOnServer(
        accountId: AccountId,
        folderId: Long,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
    ): Flow<RemoteSearchEvent>

    /**
     * Downloads the headers of more results of a server search, [messageServerIds] from [RemoteSearchEvent.Finished].
     */
    suspend fun loadSearchResults(accountId: AccountId, folderId: Long, messageServerIds: List<String>)
}

sealed interface DownloadError {
    /** The message isn't on the server (or never was). */
    data object MessageNotFound : DownloadError

    /** Downloading failed, e.g. because the server couldn't be reached. */
    data class Failed(val message: String?) : DownloadError
}

/** Progress of [RemoteContentRepository.searchOnServer]. */
sealed interface RemoteSearchEvent {
    data object Started : RemoteSearchEvent

    /** The server found [resultCount] messages; the app downloads at most [resultLimit] (0: no limit) for now. */
    data class ServerQueryComplete(val resultCount: Int, val resultLimit: Int) : RemoteSearchEvent

    data class Failed(val message: String?) : RemoteSearchEvent

    /**
     * The search is over (also after [Failed]). [moreResults] are the server IDs of results that weren't downloaded
     * because of the limit, see [RemoteContentRepository.loadSearchResults].
     */
    data class Finished(val resultLimit: Int, val moreResults: List<String>) : RemoteSearchEvent
}
