package com.fsck.k9.ui.helper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Starts a change the user made to their mail (flags, moves, deletes, ...) from UI code.
 *
 * The change starts right away, on the calling thread, so it is queued before the call returns and in the order the
 * user made the changes. Use the app-wide coroutine scope, so the change isn't cancelled when the screen goes away.
 */
fun CoroutineScope.launchUserChange(change: suspend CoroutineScope.() -> Unit): Job {
    return launch(start = CoroutineStart.UNDISPATCHED, block = change)
}
