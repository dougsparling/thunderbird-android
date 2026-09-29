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
A10 OfflineChangesAppliedInOrderScenarioTest — offline: mark A read, star B, delete C, move D to Work; online,
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
C9  PushSeesOtherClientChangesScenarioTest — push listening; other client stars A, expunges B: eventually A
    starred, B gone.
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
