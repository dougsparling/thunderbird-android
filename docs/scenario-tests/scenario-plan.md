# Scenario plan (merged from code analysis + GitHub issue research)

PURPOSE: a safety net for removing legacy code — pin behaviour the app gets RIGHT today so a rewrite can't lose it.
Bug-hunting is out of scope for this round.

Conventions: one test method per scenario class; class name `<Topic>ScenarioTest`; assert user-visible state
(driver reads, device.notifications()) and server state (server.stateOf) — never app internals. Scenarios marked
[BACKLOG] are predicted to fail because of app bugs: do NOT implement them; they go to the backlog doc. Scenarios
marked [CHECK] may or may not pass: implement and run; if one fails because of app behaviour (confirmed from the
transcript/stack, not a test mistake), remove it from the suite and add it to the backlog doc with the observed
failure. "Offline" = proxy refuses connections
+ disconnect all (+ device offline if the harness offers a combined helper).

Code-analysis line refs: MessagingController.java pending commands :796-843 (NO = permanent, dropped, not reverted;
unexpected exception -> AssertionError in debug), setFlag local UIDs :1180-1209, move/copy :969-1032 & :1872-1943,
delete :2170-2191, empty trash :2255-2273; ImapSync.kt highest-UID in finally :162-165/:261-265, lastChecked on
failure :244-251, UIDVALIDITY :88-98, deleted placeholders :439-450; ImapBackend.moveMessagesAndMarkAsRead :101-119;
RealImapFolder.fetch ignores tagged NO (~:617-679).

## Group A — pending commands & offline
A1 [BACKLOG]  UidValidityPendingDeleteScenarioTest — pending delete must not hit another message after folder recreate
    (Work has A synced; UID MOVE dropped beforeServerSees once; user deletes A; server recreates Work with B;
    refresh Work+Trash; B still in Work unread, not in Trash). Predicted bug: pending cmds ignore UIDVALIDITY.
A2 [BACKLOG]  StarAfterOfflineArchiveScenarioTest — #7688/#823: archive offline, star in Archive, back online, refresh:
    A flagged in server Archive, starred in app, not in INBOX. Predicted: K9LOCAL uid -> NumberFormatException/crash.
A3 [CHECK]  OfflineArchiveThenDeleteScenarioTest — #8622/#4708: archive then delete while offline; online+refresh:
    server has A only in Trash (read), app shows A only in Trash, no ghosts.
A4 [BACKLOG]  TransientNoKeepsDeleteScenarioTest — UID MOVE answered `NO [UNAVAILABLE]` once (proxy respond); delete A;
    refresh twice: A ends in server Trash, never back in INBOX, no ghost. Predicted: NO is permanent, dropped.
A5 [BACKLOG]  MoveToDeletedFolderScenarioTest — #1130/#5558 poison command: server deletes Projects; user moves A to
    Projects; then marks B read; refresh + refresh folders: B \Seen on server (queue not blocked); A visible in
    INBOX again (matches server). Predicted: A hidden forever.
A6 [BACKLOG]  FailedStoreKeepsReadScenarioTest — mark read; UID STORE dropped beforeServerSees twice; pull to refresh:
    A still read in app right after; after another refresh server \Seen. Predicted: flips to unread.
A7 [BACKLOG]  PendingFlagForVanishedFolderScenarioTest — offline mark read in Work; server deletes Work; online; refresh
    folders; pull INBOX: INBOX syncs, app responsive (no dead controller thread), Work gone.
A8  ReadThenArchiveOrderScenarioTest — #6581: mark read then archive with STORE dropped after server responds:
    server Archive has A \Seen; transcript shows STORE before MOVE.
A9 [CHECK]  MoveMessageDeletedByOtherClientScenarioTest — offline archive A; server expunges A; online; refresh INBOX &
    Archive: A nowhere, no ghost, no crash.
A10 OfflineChangesAllReachServerScenarioTest — offline: mark A read, star B, delete C, move D to Work; online,
    refresh: server reflects all four; app matches server.

## Group B — delete / trash / server-side changes
B1  EmptyTrashScenarioTest — #11256/#11255: Trash has 3 msgs; empty trash: server Trash empty (expunged, not
    just \Deleted); app Trash empty.
B2  DeleteMovesToTrashScenarioTest — #7721/#6582: delete A from INBOX: server INBOX lacks A (expunged/moved),
    Trash has A \Seen (mark-as-read-on-delete default); app shows A in Trash without extra refresh? (check #7401).
B3 [BACKLOG]  DeleteLostMoveResponseScenarioTest — UID MOVE afterServerResponds disconnect once; delete A; refresh INBOX &
    Trash: server Trash A \Seen, app shows read in Trash, exactly one copy. Predicted: unread (no COPYUID on retry).
B4  ServerSideDeletionsScenarioTest — #9076: server expunges A and marks B \Deleted (not expunged); refresh:
    both gone from app; server untouched by app.
B5  ServerSideFlagChangesScenarioTest — other client marks A read, stars B, unmarks C; refresh: app mirrors.
B6 [CHECK]  FlagConflictScenarioTest — offline user marks A read; other client stars A; online refresh: A read AND
    starred on server and in app.
B7  FolderRecreatedScenarioTest — #1074: Work synced with A,B; server recreates Work with C; refresh: app shows
    exactly C.
B8  FolderRenamedOnServerScenarioTest — #3026: server renames Work->Projects; refresh folders + Projects:
    Work gone, Projects has the messages.
B9 [CHECK]  FolderDeletedOnServerScenarioTest — #4584: server deletes Work while app knows it; refresh folders & INBOX:
    no crash, Work gone, INBOX fine.
B10 MarkAllReadScenarioTest — mark all read in INBOX with 3 unread: server all \Seen, app all read. (Variant
    noted but not asserted: message delivered after last sync — document behaviour in a comment only.)
B11 LoadMoreThenNewMailScenarioTest — 30 msgs, 25 shown; load more -> 30; deliver 5; refresh -> 35.

## Group C — sync correctness, notifications, push, auth
C1 [BACKLOG]  FlagsFetchNoKeepsFlagsScenarioTest — A read, B starred, synced; `UID FETCH` of FLAGS-only answered NO once
    (respond + args predicate); refresh: A still read, B still starred. Predicted: flags cleared.
C2 [BACKLOG]  NotificationAfterPartialDownloadScenarioTest — notifyNewMail; baseline synced; deliver 3; disconnect
    afterBytes during body fetch once; advance time twice: a new-mail notification covers the 3.
    Predicted: none (highest UID saved in finally).
C3 [BACKLOG]  NoNotificationsForOldMailAfterFailedFirstSyncScenarioTest — #2092/#1454: 3 old unread; EXAMINE dropped once on
    first sync; notifyNewMail + interval; two periodic syncs: no new-mail notifications. Predicted: 3 notifications.
C4 [CHECK]  NoNotificationStormAfterUidValidityScenarioTest — notifyNewMail; 10 old unread synced; server recreates INBOX
    (same 10 messages); refresh: no new-mail notifications.
C5  MalformedMessagesScenarioTest — raw(): broken multipart boundary, unknown charset, NUL bytes, 8-bit headers,
    no Date/Message-ID, very long header + good messages: all listed with subjects.
C6  LargeMessageInterruptedScenarioTest — >128KB message + small ones; disconnect afterBytes during large body
    once; two refreshes: all listed.
C7  WrongPasswordScenarioTest — account with wrong password; pull to refresh: auth-error notification (check its
    tap action/text); user fixes password; refresh: notification cleared, INBOX synced.
C8 [CHECK]  PushResumesAfterDisconnectScenarioTest — push listening; proxy.disconnectAll(); deliver B; advance time
    (IDLE retry/refresh interval): B appears; app listening again. (Needs alarms in advanceTime.)
C9  PushSeesOtherClientStarScenarioTest / PushSeesOtherClientExpungeScenarioTest — push listening; other client
    stars A (expunges B): eventually A starred (B gone).
C10 [CHECK]  PushAcrossOfflineScenarioTest — push listening; device offline (+proxy down); deliver B; online: B arrives,
    listening again.
C11 [CHECK]  PeriodicSyncRetryAfterNetworkFailureScenarioTest — interval 15m; first run offline; online; advance by
    backoff: new mail fetched? (lastChecked-on-failure may skip — decide expected = mail fetched by next due run;
    mark KnownBug only if clearly a bug, else assert documented behaviour with a comment).

## Extra characterization scenarios (core behaviour a rewrite must keep)
D1  MoveToFolderScenarioTest — move A from INBOX to Work: server Work has A, INBOX doesn't; app matches.
D2  ArchiveScenarioTest — archive A: server Archive has A; app shows it in Archive only.
D3  StarSyncScenarioTest — star A, unstar B (was starred): server flags match; app matches after refresh.
D4  MarkUnreadScenarioTest — read A marked unread: server lacks \Seen; app unread.
D5  DeleteFromTrashScenarioTest — delete A in Trash: removed from server entirely.
D6  NewServerFolderScenarioTest — server gains folder Clients/2025 with a message; refresh folders + pull:
    folder listed, message shown.
D7  VisibleLimitScenarioTest — 40 messages on server, display count 25: app shows newest 25, oldest not listed.

## Group E — parity gaps for replacing MessagingController (added 2026-10-05)

PURPOSE: every public operation of `MessagingController` that a user can reach from the legacy message list, the
message view, compose, notifications, settings or background work is pinned by at least one scenario, so the
replacement can be checked against the same suite. Same conventions as above. `[H…]` names the harness capability a
scenario needs (see "Harness work for group E" below). Expected outcomes are what the app does today; where today's
behaviour looks wrong but is consistent, the scenario pins it and says so in a comment (parity first).

### E1 Sending (Outbox, SMTP) [H1]
E1.1  SendMessageScenarioTest — compose and send to another test user: recipient's INBOX has it (subject, body);
      sender's server Sent has it `\Seen`; app Outbox empty; app Sent shows it.
E1.2  SendWithoutSentUploadScenarioTest — "upload sent messages" off: delivered; server Sent empty; app Sent empty
      (the local copy is deleted).
E1.3  SendWhileOfflineScenarioTest — offline: send; Outbox shows it; nothing delivered; online + pull to refresh:
      delivered, Outbox empty.
E1.4  SendTransientFailureScenarioTest — SMTP connection dropped once (proxy): message stays in Outbox; next attempt
      delivers it; a send-failed notification appears after the failure and is gone after the success.
E1.5  SendPermanentFailureScenarioTest — recipient rejected with 5xx: message stays in Outbox, not retried by later
      attempts, send-failed notification shown.
E1.6  SendRetriesExhaustedScenarioTest — SMTP down for MAX_SEND_ATTEMPTS attempts: message stops being retried
      (stays in Outbox) once the limit is reached.
E1.7  SendAuthFailureScenarioTest — wrong SMTP password: message stays in Outbox, outgoing auth-error notification;
      after fixing the password the next attempt sends it.
E1.8  SentUploadInterruptedScenarioTest — APPEND to Sent dropped after the server stored it: after the retry,
      server Sent has exactly one copy (no duplicate; `X_REMOTE_COPY_STARTED` + Message-ID lookup).
E1.9  PeriodicSyncSendsOutboxScenarioTest — message left in Outbox while offline is sent by the next periodic sync.

### E2 Drafts [H2]
E2.1  SaveDraftScenarioTest — save a draft: server Drafts has it (`\Draft`, `\Seen`); app Drafts shows it.
E2.2  UpdateDraftScenarioTest — save, edit, save again: server Drafts has exactly the latest version.
E2.3  SendDraftScenarioTest — open draft, send: delivered; server and app Drafts empty.
E2.4  DiscardDraftScenarioTest — discard a saved draft: gone from server Drafts (and not in Trash, which is the
      app's "delete draft skipping trash" path) — pin whatever today does.
E2.5  SaveDraftOfflineScenarioTest — save offline, edit offline, online + refresh: server Drafts has one, latest copy.
E2.6  MoveToDraftsScenarioTest — "move to drafts" on a message in INBOX: message leaves INBOX (server + app) and
      appears in Drafts.

### E3 Copy and thread actions [H3]
E3.1  CopyToFolderScenarioTest — copy A from INBOX to Work: both folders have A on server and in app.
E3.2  ThreadedDeleteScenarioTest — threaded list, delete a 3-message thread: all three in server Trash.
E3.3  ThreadedArchiveScenarioTest — archive a thread: all messages in server Archive.
E3.4  ThreadedMoveScenarioTest / ThreadedCopyScenarioTest — move/copy a thread to Work.
E3.5  ThreadedMarkReadScenarioTest / ThreadedStarScenarioTest — flags on a thread reach every message on the server.
E3.6  ThreadAcrossFoldersScenarioTest — thread with a message in INBOX and a reply in Sent: delete the thread from
      INBOX's threaded list; pin which messages move (today: messages of the thread in the acting folder's thread).

### E4 Reading messages [H4]
E4.1  OpenMessageMarksReadScenarioTest — open unread A: read in app and `\Seen` on server; no extra refresh.
E4.2  OpenMessageWithoutMarkReadScenarioTest — "mark as read when opened" off: A stays unread; its new-mail
      notification is still removed.
E4.3  OpenLargeMessageDownloadsBodyScenarioTest — message above the auto-download size: list shows it, opening
      downloads the full body (text visible).
E4.4  DownloadAttachmentScenarioTest — open message with an attachment above the auto-download size; download it:
      bytes match.
E4.5  AttachmentDownloadFailsScenarioTest — connection dropped during the part FETCH: download reports failure; a
      second attempt succeeds.
E4.6  OpenMessageClearsNotificationScenarioTest — push delivers A with notification; opening A removes it.

### E5 Remote search [H5]
E5.1  RemoteSearchScenarioTest — older message not synced locally (beyond display count): server search finds it,
      it's listed and openable.
E5.2  RemoteSearchResultLimitScenarioTest — more hits than the remote-search limit: first N shown, "load more
      results" fetches the rest.
E5.3  RemoteSearchFailsScenarioTest — SEARCH dropped: search reports failure, app keeps working.

### E6 Delete, expunge and spam policies [H6]
E6.1  DeletePolicyNeverScenarioTest — "delete from server: never": app hides A (moves to local Trash), server untouched.
E6.2  DeletePolicyMarkReadScenarioTest — "mark as read": server A `\Seen`, still in INBOX.
E6.3  DeleteWithoutTrashFolderScenarioTest — no trash folder: A flagged `\Deleted` (and expunged per expunge policy).
E6.4  DeleteKeepsUnreadScenarioTest — "mark as read on delete" off: A in server Trash, unread.
E6.5  ManualExpungeScenarioTest — expunge policy "manually": deleted-without-trash message stays `\Deleted` on server
      until the user expunges the folder.
E6.6  EmptySpamScenarioTest — Spam has 2 messages; empty spam: server Spam empty.
E6.7  MoveToSpamScenarioTest — move A to Spam (the "spam" action): server Spam has A.
E6.8  ClearLocalFolderScenarioTest — "clear local messages" in folder settings: app folder empty, server untouched;
      next refresh brings them back.

### E7 Notifications [H7]
E7.1  NotificationMarkReadActionScenarioTest — new-mail notification, tap "Mark read": `\Seen` on server, notification
      gone.
E7.2  NotificationDeleteActionScenarioTest — "Delete" action: A in server Trash.
E7.3  NotificationArchiveActionScenarioTest — "Archive" action.
E7.4  NotificationSpamActionScenarioTest — "Spam" action.
E7.5  NotificationStarActionScenarioTest — "Star" action.
E7.6  NotificationClearedWhenReadElsewhereScenarioTest — another client marks A read; next sync removes A's
      notification.
E7.7  NotificationClearedWhenDeletedElsewhereScenarioTest — another client expunges A; next sync removes it.
E7.8  NoNotificationForAlreadyReadScenarioTest — A delivered already `\Seen`: no notification.
E7.9  SyncNotificationScenarioTest — "show sync notification" on: an ongoing "checking mail" notification during
      sync, gone after.
E7.10 NotificationsForSeveralMessagesScenarioTest — 3 new messages in one sync: one summary (pin count/text).

### E8 Multiple accounts and unified inbox [H8]
E8.1  SyncAllAccountsScenarioTest — two accounts on different users; "sync all" (drawer): both INBOXes refreshed.
E8.2  UnifiedInboxActionsScenarioTest — mark read and delete one message from each account in the unified inbox:
      each server changed, other untouched.
E8.3  AccountsSyncIndependentlyScenarioTest — account B's server unreachable: account A still syncs.
E8.4  RemoveAccountScenarioTest — remove an account with offline changes pending: app has no trace of it; the other
      account keeps working; server of the removed one untouched by the pending changes.
E8.5  ActionDuringSlowSyncScenarioTest — slow first sync (proxy throttle) on A; mark read on B meanwhile: both finish
      and reach their servers (ordering across accounts not asserted).

### E9 POP3 [H9]
E9.1  Pop3FetchScenarioTest — POP3 account: INBOX shows server mail; new mail appears on refresh.
E9.2  Pop3DeleteScenarioTest — delete: local Trash only; server per delete policy (pin today's default).
E9.3  Pop3NoFlagSyncScenarioTest — mark read/star: local only, nothing sent to the server; capability-gated actions
      (move, archive) refused as in the UI.
E9.4  Pop3EmptyTrashScenarioTest — local-only trash emptied.

### E10 Folder settings and sync scope [H10]
E10.1 HiddenFolderNotSyncedScenarioTest — folder hidden: periodic sync skips it.
E10.2 SyncDisabledFolderScenarioTest — folder sync off: periodic sync skips it, pull to refresh still syncs it.
E10.3 FolderSyncedTooRecentlyScenarioTest — periodic sync shortly after a manual refresh doesn't re-sync the folder.
E10.4 FolderListRefreshedWhenStaleScenarioTest — new server folder appears after 30 minutes without an explicit
      folder refresh (staleness check on sync).
E10.5 UnreadCountsScenarioTest — unread counts in the folder list follow mark read, delete, move, server changes.

### E11 Account state and errors [H11]
E11.1 OAuthSignInRequiredScenarioTest — OAuth account without a token: sync skipped, "sign in" notification.
E11.2 CertificateErrorScenarioTest — server certificate untrusted: certificate-error notification, no sync.
E11.3 OutgoingAuthCheckScenarioTest — `checkAuthenticationProblem` paths reachable from settings.

### E12 Durability [H12]
E12.1 PendingChangesSurviveRestartScenarioTest — offline: mark read, star, delete, move; app restarts; online +
      refresh: all four reach the server.
E12.2 OutboxSurvivesRestartScenarioTest — offline send; restart; online: delivered.
E12.3 InterruptedMoveSurvivesRestartScenarioTest — MOVE applied but response lost, app restarts: one copy, right place.

### E13 Concurrency inside one account [H13]
E13.1 ActionDuringLongSyncScenarioTest — first sync of a large folder throttled; user stars a message in another
      folder meanwhile: star reaches server, sync completes.
E13.2 OpenMessageDuringSyncScenarioTest — open a not-yet-downloaded message while INBOX syncs: body loads.
E13.3 PushAndPeriodicOverlapScenarioTest — push and periodic sync of the same folder: no duplicate messages.

## Harness work for group E

H1  SMTP: enable James SMTP with AUTH (plaintext, loopback), port in TestServerConfig; a second fault proxy for SMTP;
    AccountSpec gets SMTP settings; driver.send(account, to, subject, text, attachments). Delivery checked via the
    recipient's server state. Reject rules: unknown local recipients get 5xx (ValidRcptHandler).
H2  Compose: driver.saveDraft / editDraft / sendDraft / discardDraft / moveToDrafts, mirroring MessageCompose and
    SaveMessageTask.
H3  Threaded list: threaded message list reads and thread actions; driver.copy; fixture threads via
    In-Reply-To/References headers.
H4  Message view: driver.open(account, folder, subject) → ClientMessageContent (text, attachments), mirroring
    MessageViewFragment/MessageLoaderHelper; driver.downloadAttachment.
H5  Search: driver.searchOnServer / loadMoreSearchResults, mirroring LegacyMessageListFragment's remote search.
H6  Account settings: ClientAccountSettings (delete policy, mark read on delete, expunge policy, mark read on open,
    auto-download size, upload sent, notify sync, remote search limit) at account creation and via
    driver.changeSettings; driver.expunge, emptySpam, clearLocalMessages.
H7  Notification actions: ClientNotification gains its actions; device.tapAction(notification, label) fires the
    PendingIntent like SystemUI.
H8  Multi-account: several client.account() calls; driver.syncAllAccounts, unified inbox reads/actions,
    driver.removeAccount.
H9  POP3: enable James POP3; client.account(user, protocol = POP3).
H10 Folder settings: driver.setFolderVisible / setFolderSyncEnabled.
H11 OAuth account without token; TLS endpoint with an untrusted certificate.
H12 App restart inside one test: stop the sync core and Koin, re-run app startup on the same data directory.
H13 Slow server: proxy latency/throttle rules (exist) applied per connection.
