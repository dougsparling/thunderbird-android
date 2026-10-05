# Scenario backlog

Scenarios that are not in the suite because the app (or, where noted, the harness) doesn't support them today. The
suite pins behaviour the app gets right; these are the known or predicted exceptions. Each one becomes a regular
scenario once the behaviour is fixed (or the harness gains the missing capability).

Plan item IDs refer to [`scenario-plan.md`](scenario-plan.md). Line references are from the read-through of
`MessagingController.java`, `ImapSync.kt` and friends at the time of writing and may drift.

## Confirmed: [CHECK] scenarios that failed

### A3 OfflineArchiveThenDeleteScenarioTest (GitHub #8622, #4708)

- **Given** INBOX has A (synced) and an Archive folder with SPECIAL-USE `\Archive`.
- **When** the user goes offline, archives A, then deletes A from Archive while still offline; goes online and
  refreshes INBOX, Archive and Trash.
- **Then (expected)** the server has A only in Trash, `\Seen`; the app shows A only in Trash; no ghost copies.
- **Observed:** the archive is replayed (`UID MOVE 1 "Archive"` from INBOX, then `UID STORE 1 +FLAGS.SILENT (\Seen)` in
  Archive), but the delete never reaches the server: no MOVE/COPY to Trash and no `\Deleted` STORE. A stays in the
  server's Archive, Trash is empty. Failing assertion: server Archive is empty.
- Likely area: the pending delete refers to the message by its local (not yet server-assigned) UID in Archive
  (`MessagingController.java` setFlag/delete with local UIDs, ~:1180-1209, :2170-2191).

### A9 MoveMessageDeletedByOtherClientScenarioTest

- **Given** INBOX has A (synced) and an Archive folder with SPECIAL-USE `\Archive`.
- **When** the user goes offline and archives A; another client deletes and expunges A on the server; the user goes
  online and refreshes INBOX and Archive.
- **Then (expected)** A is in neither folder, in the app or on the server; no crash.
- **Observed:** the server is correct (INBOX and Archive empty) and the controller survives, but the app keeps a ghost
  copy of A (read) in its Archive list after refreshing Archive. Failing assertion: the app's Archive list is empty.
- Likely area: the local copy created by the offline move isn't removed when the server-side move fails
  (`MessagingController.java` move/copy ~:969-1032, :1872-1943).

### Lost response to a move leaves a duplicate (found 2026-10-05 while writing E12.3; same family as B3)

- **Given** INBOX has A, synced; folder Work exists. **When** the user moves A to Work and the connection drops after
  the server applied `UID MOVE` (proxy `afterServerResponds { disconnect() }`, once); the user refreshes INBOX and
  Work (with or without an app restart in between).
- **Then (expected)** A is in Work once, on the server and in the app.
- **Observed:** the server is right, but the app shows A twice in Work. The retried `UID MOVE` gets
  `BAD MOVE failed. Invalid messageset.` (A is already gone from INBOX), the command is dropped as a permanent
  failure without removing the app's placeholder copy in Work, and the next sync of Work downloads the real copy next
  to it (`MessagingController.java` pending commands ~:796-843, move/copy ~:969-1032).

## Pinned as current behaviour (in the suite, documented in the scenario)

These scenarios pass because they pin what the app does today, which looks wrong. A replacement must match them for
parity; fixing them is a separate decision.

- **MoveToDraftsScenarioTest:** "Move to Drafts" saves a draft but deletes only the app's copy of the message
  (`moveToDraftsFolderInBackground` calls `message.destroy()`); the server keeps it in INBOX, so it reappears after
  the next refresh.
- **CertificateErrorScenarioTest:** a certificate the device doesn't trust, found while refreshing the folder list
  (e.g. during account setup), doesn't notify the user: the folder list refresh wraps the
  `CertificateValidationException` in a `MessagingException`, and `notifyUserIfCertificateProblem` only checks the
  top-level exception type.

- **PeriodicSyncRetryAfterNetworkFailureScenarioTest (plan C11):** after a periodic sync fails because the server is
  unreachable, the WorkManager retry skips INBOX as "checked too recently", because the failed sync recorded INBOX as
  checked (`ImapSync.kt` ~:244-251), so new mail waits for the next regular run. Visible since sync timestamps use the
  scenario clock; before, the wall clock made every folder look stale and the scenario passed for the wrong reason.

## Blocked by the harness

### C4 NoNotificationStormAfterUidValidityScenarioTest

- **Given** new-mail notifications on; INBOX has 10 old unread messages, already synced.
- **When** another client recreates INBOX with the same 10 messages (new UIDVALIDITY); the app syncs INBOX via push or
  periodic sync.
- **Then (expected)** no new-mail notifications; the app still lists the 10 messages.
- **Blocked:** `server.recreateFolder` can't recreate INBOX (INBOX can't be deleted). Needs a harness way to change
  INBOX's UIDVALIDITY, e.g. through the James WebAdmin API or by recreating the user's mailbox.

### Not covered

- The ongoing "checking mail" and "sending" notifications (`account_notify_sync`): they only exist while a background
  sync or send runs, and the harness can't look at the device in the middle of one.
- `clearNewMessages` (the "new messages" view) and `sendMessageBlocking` (Autocrypt setup message): not reachable from
  the legacy message list's regular screens.
- OAuth sign-in itself (needs an OAuth provider); only the "not signed in" state is pinned.

## Predicted: [BACKLOG] scenarios (not implemented)

Predicted to fail from code analysis and GitHub issues. Not implemented or run.

### A1 UidValidityPendingDeleteScenarioTest

- **Given** Work has A, synced. **When** `UID MOVE` is dropped (proxy `beforeServerSees`, once); the user deletes A;
  another client recreates Work containing B; the user refreshes Work and Trash.
- **Then** B is still in Work, unread, and not in Trash.
- **Predicted:** pending commands ignore UIDVALIDITY, so the retried delete hits B (`ImapSync.kt` UIDVALIDITY
  ~:88-98; pending commands `MessagingController.java` ~:796-843).

### A2 StarAfterOfflineArchiveScenarioTest (GitHub #7688, #823)

- **Given** INBOX has A, synced; Archive folder exists. **When** offline, the user archives A and stars it in Archive;
  goes online and refreshes.
- **Then** A is `\Flagged` in the server's Archive, starred in the app, not in INBOX.
- **Predicted:** the star command carries a local placeholder UID (`K9LOCAL:…`) → NumberFormatException / crash
  (`MessagingController.java` ~:1180-1209).

### A4 TransientNoKeepsDeleteScenarioTest

- **Given** INBOX has A, synced. **When** `UID MOVE` is answered `NO [UNAVAILABLE]` once (proxy `respond`); the user
  deletes A; refreshes twice.
- **Then** A ends up in the server's Trash, never back in INBOX, no ghost.
- **Predicted:** a tagged NO is treated as permanent and the command is dropped (`MessagingController.java` ~:796-843).
  Related, already observed while building the harness: `UID STORE` answered once with `NO [UNAVAILABLE]` loses the
  mark-as-read (unread on the server and, after the next sync, in the app).

### A5 MoveToDeletedFolderScenarioTest (GitHub #1130, #5558)

- **Given** INBOX has A and B; folder Projects exists; synced. **When** another client deletes Projects; the user
  moves A to Projects, then marks B read; refreshes INBOX and the folder list.
- **Then** B is `\Seen` on the server (the queue isn't blocked) and A is visible in INBOX again (matching the server).
- **Predicted:** A stays hidden forever.

### A6 FailedStoreKeepsReadScenarioTest

- **Given** INBOX has unread A, synced. **When** the user marks A read; `UID STORE` is dropped (`beforeServerSees`)
  twice; pull to refresh.
- **Then** A is still read in the app right after, and `\Seen` on the server after another refresh.
- **Predicted:** A flips back to unread.

### A7 PendingFlagForVanishedFolderScenarioTest

- **Given** Work has A, synced. **When** offline, the user marks A read; another client deletes Work; the user goes
  online, refreshes the folder list and INBOX.
- **Then** INBOX syncs, the app stays responsive (controller thread alive), Work is gone.

### B3 DeleteLostMoveResponseScenarioTest

- **Given** INBOX has unread A, synced. **When** the connection drops after the server applies `UID MOVE` (proxy
  `afterServerResponds { disconnect() }`, once); the user deletes A; refreshes INBOX and Trash.
- **Then** A is in the server's Trash, `\Seen`; the app shows it read in Trash, exactly one copy.
- **Predicted:** A ends up unread (no COPYUID on the retry, so the follow-up `\Seen` has no target)
  (`ImapBackend.moveMessagesAndMarkAsRead` ~:101-119).

### C1 FlagsFetchNoKeepsFlagsScenarioTest

- **Given** INBOX has read A and starred B, synced. **When** the flags-only `UID FETCH` is answered NO once (proxy
  `respond` with an argument predicate); refresh.
- **Then** A is still read and B still starred.
- **Predicted:** `RealImapFolder.fetch` ignores the tagged NO (~:617-679) and the flags are cleared.

### C2 NotificationAfterPartialDownloadScenarioTest

- **Given** new-mail notifications on; baseline synced. **When** 3 messages are delivered; the connection drops
  part-way through the body fetch once; time advances through two periodic syncs.
- **Then** a new-mail notification covers the 3 messages.
- **Predicted:** none, because the highest UID is saved in a `finally` block even when the download fails
  (`ImapSync.kt` ~:162-165, :261-265).

### C3 NoNotificationsForOldMailAfterFailedFirstSyncScenarioTest (GitHub #2092, #1454)

- **Given** INBOX has 3 old unread messages; new-mail notifications on, with a check interval. **When** `EXAMINE` is
  dropped once on the first sync; two periodic syncs run.
- **Then** no new-mail notifications.
- **Predicted:** 3 notifications for old mail (`ImapSync.kt` lastChecked on failure ~:244-251).
