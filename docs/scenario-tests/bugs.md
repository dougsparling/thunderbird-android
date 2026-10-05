# Bugs found while replacing MessagingController

Every bug this work turned up, in one place, with its status. It covers both branches: the scenario harness
(`doug-scenario-harness`) and the switchover (`doug-messaging-controller-switchover`).

Most of these are **found, not fixed**. The goal was parity: the new sync core does what `MessagingController` did,
bugs included, so the 105 scenarios could prove nothing changed. Only bugs that broke the port itself, or that the port
removed as a side effect, are fixed.

Details (Given/When/Then, transcripts, likely code areas) live in [`backlog.md`](backlog.md); this page is the index.
Line references to `MessagingController.java` are from `main`, where the file still exists.

|                          Status                           | Count |
|-----------------------------------------------------------|-------|
| Fixed on these branches                                   | 4     |
| Confirmed in the app, open                                | 3     |
| Wrong behaviour pinned for parity                         | 3     |
| Predicted from code and GitHub issues, not yet reproduced | 10    |
| Suspected, not checked                                    | 2     |

## Fixed

- **Sync crashed when a pending command had failed and nobody was listening.** `MessagingController.java:694` calls
  the listener unconditionally, but `:587` and `:606` (including the periodic sync) pass `null`. The folder's sync
  died with an NPE instead of recording the error. The port records the failure without a listener
  (`DefaultMailSynchronizer`).
- **A permanent send failure without an error message crashed.** Java passed `null` into the non-null Kotlin
  `setSendAttemptError` (`MessagingController.java:1661`), so sending stopped with a crash instead of marking the
  message failed. The port stores an empty error (`DefaultOutboxSender`).
- **Remote search logged the search query** (`MessagingController.java:457`). Search terms can be names or addresses,
  which the app must not log. The port's log line leaves the query out.
- **`-PcodeCoverageDisabled=false` had no effect** (nor `CODE_COVERAGE_DISABLED=false`). The build plugin's
  `CodeCoverageExtension.initialize()` reset the default to disabled, so coverage minimums were never checked unless a
  build script set `disabled = false`. On `main` since `a6b58321a1`; fixed in `f17836d273` with a regression test.
  The fix only touches the build plugin and can go to `main` on its own.

## Confirmed in the app, open

Each one has a scenario written; it's out of the suite because it fails. Full details are in
[`backlog.md`](backlog.md#confirmed-check-scenarios-that-failed).

- **A3 Archive then delete while offline** (GitHub #8622, #4708): the archive reaches the server, the delete never does.
  The message stays in the server's Archive.
- **A9 Archive a message another client deleted**: the server ends up right, but the app keeps a ghost copy in Archive.
- **Lost response to a move**: if the connection drops after the server applied `UID MOVE`, the app shows the message
  twice in the target folder. The retry fails as "permanent" and the app's placeholder copy is never cleaned up.

## Wrong behaviour pinned for parity

These scenarios are in the suite and pass, because they pin what the app does today. Fixing them means changing the
scenario too. Details in [`backlog.md`](backlog.md#pinned-as-current-behaviour-in-the-suite-documented-in-the-scenario).

- **Move to Drafts** saves a draft but deletes only the local copy; the original comes back from the server on the next
  refresh (`MoveToDraftsScenarioTest`).
- **Untrusted certificate during folder list refresh** (e.g. account setup) doesn't notify the user: the exception is
  wrapped, and the check only looks at the outer type (`CertificateErrorScenarioTest`).
- **Retry after a failed periodic sync skips INBOX** as "checked too recently", because the failed sync recorded INBOX
  as checked. New mail waits for the next regular run (`PeriodicSyncRetryAfterNetworkFailureScenarioTest`).

## Predicted, not yet reproduced

Found by reading the sync code and matching GitHub issues; no scenario yet. Details in
[`backlog.md`](backlog.md#predicted-backlog-scenarios-not-implemented).

- **A1:** pending commands ignore UIDVALIDITY, so a retried delete can hit a different message.
- **A2** (GitHub #7688, #823): starring a message after an offline archive uses its local placeholder UID; expected
  crash or lost star.
- **A4:** a transient `NO` to a delete is treated as permanent and the delete is dropped.
- **A5** (GitHub #1130, #5558): a move to a folder deleted on the server leaves the message hidden forever.
- **A6:** a failed `STORE` (seen once with `NO [UNAVAILABLE]`) flips a message back to unread.
- **A7:** a pending flag change for a folder another client deleted may stop the sync engine.
- **B3:** delete with a lost move response leaves the message unread in Trash (no COPYUID on the retry).
- **C1:** a tagged `NO` to a flags fetch is ignored and local flags are cleared.
- **C2:** no new-mail notification after a partial download (highest UID saved in a `finally`).
- **C3** (GitHub #2092, #1454): after a failed first sync, old mail notifies as new.

## Suspected, not checked

- **Certificate error notifications are never cleared.** On `main`, nothing calls
  `NotificationController.clearCertificateErrorNotifications`. It could be a missing feature rather than dead code.
- **The new MVI message list's refresh doesn't contact the server.** With `enable_message_list_new_state` on, `Refresh`
  only seems to reload local data. Worth checking before that flag is switched on.

## Bugs in Apache James 3.9 (the test server)

Found while getting the suite to run on GitHub's runners. Both only show up when the machine is busy, and the harness
works around both (see `HANDOFF.md`, "James races under load").

- **FETCH responses after the tagged completion:** James sometimes sends `<tag> OK FETCH completed` before some of
  the command's `* n FETCH` responses. The app then misses message bodies or reads them as part of its next command.
- **Lost users:** James' in-memory user store sometimes loses one of two users created at the same moment through
  WebAdmin; logging in as the lost user fails with "Invalid login/password".

## Not bugs, for the record

- **Test flakes** under parallel load: push scenarios (waiting for IDLE) and one provisioning login failure. All pass on
  their own; see the open items in [`switchover.md`](switchover.md#open-items).
- **POP3 sync uses the system clock** for "last checked". It's only a testability gap.

## Next steps

- [ ] Send the coverage fix (`f17836d273`) to `main` as its own pull request.
- [ ] Decide which of the confirmed and pinned bugs to fix after the switchover lands; each already has a failing or
  pinning scenario to start from.
- [ ] File GitHub issues for the confirmed bugs that don't have one yet (A9, lost move response) and for the
  certificate notification question.
- [ ] Write scenarios for the predicted bugs, one at a time, and move each one to "confirmed" or drop it.

