# Replacing MessagingController: switchover log

Branch `doug-messaging-controller-switchover`, stacked on `doug-scenario-harness`. Goal: delete `MessagingController`
and its helpers, with the app running on a replacement and the scenario suite (105 scenarios) green after every slice.
Decisions are in [`HANDOFF.md`](HANDOFF.md) ("Parity suite for replacing MessagingController").
Every bug found along the way, fixed or not, is listed in [`bugs.md`](bugs.md).

## Where the code goes

- `:feature:mail:sync:api`: focused contracts (capabilities, flags, moving and deleting, sync, outbox, remote content,
  drafts, notifications) with `suspend`/`Flow`/`Outcome`. Callers depend only on this.
- `:feature:mail:sync:internal`: the engine (one global serializer for remote work), the pending-command log and the
  implementations. It depends on `legacy:core` (LocalStore, MessageStore, BackendManager, NotificationController); this
  is a deliberate, temporary exception to "new modules don't depend on legacy modules", like
  `:feature:mail:message:list:internal` depending on `legacy:mailstore`. Bindings live in `app-common`.

Because `:internal` depends on `legacy:core`, the controller (still in `legacy:core`) can't call the engine directly.
Until it's deleted it reaches the engine through temporary seam interfaces in `legacy:core`
(`com.fsck.k9.controller.ControllerEngine`), implemented in `:internal` and bound in `app-common`.

## Slices

1. **Engine:** coroutine-based serializer (FIFO, foreground before background, one queue for all accounts) and
   pending-command replay in `:internal`; the controller hands `put`/`putBackground` and replay to it. The harness
   waits on the engine's idle signal instead of reflecting into the controller.
2. **Contracts:** focused interfaces in `:api`, first implemented by adapters over the controller; callers move to them.
3. **Flows:** listener callbacks become Flows (DB-driven folder counts, sync status StateFlow, suspend results);
   `MessageListCache` goes once local writes happen right away.
4. **Move and delete:** each responsibility moves into `:internal`; the controller and helpers are deleted;
   `MailSyncWorker` becomes a `CoroutineWorker`.

## Progress

- **Slice 1 (done):** `RemoteWorkSerializer` (one coroutine on a background-priority thread, priority FIFO, `isIdle`,
  `failure`) and `PendingCommandReplay` (same failure semantics as `processPendingCommandsSynchronous`) in
  `:internal`, behind `PendingCommandLog` (still the `LocalStore` table). The controller has no thread or queue of
  its own. The harness waits on `isIdle` (`RemoteWorkQueue`). Suite: 105/105.
  Build note: the module's unit tests need Robolectric on the test classpath, otherwise offline resolution picks an
  `error_prone_annotations` jar that is only cached for the other repository.
- **Slice 2 (done):** contracts in `:feature:mail:sync:api` (`MessageCapabilities`, `MessageFlagRepository`,
  `MessageMoveRepository`, `MessageDeleteRepository`, `MessageDraftRepository`, `MailSynchronizer` + `SyncEvent`,
  `OutboxSender`, `RemoteContentRepository` + `RemoteSearchEvent`, `NewMailNotifications`), first implemented by
  adapters over the controller (`...internal.legacy`). Every caller outside the controller uses them, including
  `LegacyScenarioDriver`. UI code starts changes with `launchUserChange` (app scope, undispatched), so they're applied
  and queued before the call returns, in the user's order. Java screens go through small Kotlin helpers
  (`MessageBodyDownloader`, `AttachmentProgressObserver`, `MessageComposeOperations`). Loading stored messages moved to
  `LocalMessageReader`. `MessagingControllerWrapper` is gone. Suite: 105/105.
- **Slice 3 (done, except `MessageListCache`):** folder counts, display folders and the unread widget listen to the
  message store's change signal (`MessageListRepository`) instead of `folderStatusChanged()`; the controller raises
  the signal wherever it called that. Sync progress reaches the UI as `SyncEvent`s, results as suspend returns.
  `MessageListCache` stays for now, see "Open items". Suite: 105/105.
- **Slice 4, step 1 (done):** pending commands are Kotlin data classes (`com.fsck.k9.controller.PendingCommands.kt`,
  same names and fields, no `execute(controller)`; the Java controller dispatches with `instanceof` for now).
  `PendingCommandSerializer` uses Moshi's generated adapters (KSP in `legacy:core`). Rows stored by earlier
  versions still read (the reflective adapter wrote fields alphabetically, with `databaseId`, which is now ignored);
  `PendingCommandSerializerTest` pins that with the exact old JSON. New rows use constructor order without
  `databaseId`. Suite: 105/105.
- **Slice 4, step 2 (done):** the contracts are implemented in `:internal` (package
  `net.thunderbird.feature.mail.sync.internal`) and bound instead of the `...internal.legacy` adapters; the
  controller is still in the code base but nothing creates it any more. Classes: `AccountStores`, `SyncEventBus`
  (memorizes folder sync state like `MemorizingMessagingListener`, replays it to new observers), `ServerErrorNotifier`,
  `PendingCommandQueue` + `PendingCommandProcessor` (still a `PendingCommandExecutor` for `PendingCommandReplay`),
  `LocalMessages` (grouping by account/folder, threads, `MessageListCache` calls), `MessageMover`, one `Default...`
  class per contract, `FolderSyncListener` (port of `ControllerSyncListener`), `ProgressBodyFactory`.
  Suite: 105/105 (one run had the known push timeout, `PushResumesAfterDisconnectScenarioTest`; it passed alone and
  in the next full run).
  Order of local writes, cache updates, queueing, events and notifications is the controller's. Differences:
  - Listener callbacks nothing observed are gone (`folderStatusChanged` is only the message store signal,
    `synchronizeMailboxNewMessage`/`RemovedMessage`/`HeadersStarted` aren't emitted). `syncPeriodically` records
    failures in `FolderSyncFailures`; the pending-command failure in `syncFolder` only goes there (no NPE without a
    per-call listener).
  - Work that ran on the controller's thread pool: server search is a `channelFlow` on IO with `runInterruptible`,
    `loadSearchResults` runs on IO, removing the notification of an opened message is launched on the app scope (IO).
  - A permanent send failure without a message stores an empty error instead of throwing (Java passed `null` into a
    non-null Kotlin parameter).
  - The remote search log line no longer contains the query.
  - Dispatchers are constructor parameters with defaults, so Koin's `verify()` accepts them.
- **Slice 4, step 3 (done):** deleted `MessagingController`, `ArchiveOperations`, `DraftOperations`,
  `NotificationOperations`, `MemorizingMessagingListener`, `ControllerExtension` (+ its bindings),
  `ControllerEngine`/`SerializerControllerEngine`/`FakeControllerEngine`, `ProgressBodyFactory`, `NotificationState`,
  `BackendDownloads`, `MessagingControllerTest`, the `...internal.legacy` adapters, and `MessagingListener`,
  `SimpleMessagingListener`, `MessagingControllerRegistry`, `MessagingControllerMailChecker`. `PendingCommandExecutor`
  moved to the engine package. `MailSyncWorker` is a `CoroutineWorker`; `syncPeriodically` records the last sync time
  when the check finishes (on the serializer), so it's kept if WorkManager stops the worker, like the blocking worker
  did. Under the harness, WorkManager runs coroutine workers on the configured `SynchronousExecutor`, so work still
  runs inside the test driver's calls. Comments in `UpgradeDatabaseActivity` and `ScenarioApplication` updated.
  Suite: 105/105. Slice 4 is done.
- **Cleanup (done):** deleted what the branches left unused: `MutableBoolean`, the Java wrapper
  `OutboxFolderManager.hasPendingMessagesSync`, the test stub `StubLocalDeleteOperationDecider`, and `legacy:core`'s
  test dependency on `:feature:notification:testing` (`FakeNotificationManager` stays in that module: it's the fake
  for a public API). Code that was already unused on `main` was left alone (see "Next session"). Suite: 105/105.

## Next session

The switchover is done; these are the checks and decisions left, roughly in order. Several need dependencies fetched
first (the connection is metered: build the list of what's missing, then ask the user to fetch it with
`-PuseChinaMirrors=false`).

1. **Release builds (done).** `minifyFossReleaseWithR8` passes for `app-thunderbird` and `app-k9mail` (R8 without
   signing). The stale `MessagingControllerCommands$*` keep rule is removed: Moshi's own R8 rules keep the generated
   adapters, and the mapping shows all ten `Pending*` classes and their `*JsonAdapter`s under their own names. Not
   checked at runtime: a release build reading pending commands written by an older version (needs a device).
2. **Code coverage (done).** `legacy:core`: 47.87 % branch, 53.81 % line (minimums 41 % / 46 %), `koverVerify`
   passes. `-PcodeCoverageDisabled=false` used to have no effect (a bug on `main` since `a6b58321a1`:
   `CodeCoverageExtension.initialize()` reset the `disabled` convention to `true`); fixed in `f17836d273`, which can be
   cherry-picked onto `main` on its own.
3. **Tests that weren't run:** unit tests of `:legacy:message` (6), `:legacy:ui:legacy` (270) and
   `:feature:navigation:drawer:dropdown` (37) now pass. Still not run: the full `./gradlew lint` and
   `connectedAndroidTest` (needs a device or emulator).
4. **Unit tests for the new classes (next up, sized 2026-10-06).** The engine has 17 tests (`SyncEventBus` 6,
   `RemoteWorkSerializer` 6, `PendingCommandReplay` 5). The 13 classes that do the sync, move, delete and send work
   have none and rely on the 105 scenarios. Estimate: about 100 tests for the whole module, about 55 for the first
   batch:

   |              Class               | Tests |                                     What to cover                                      |
   |----------------------------------|-------|----------------------------------------------------------------------------------------|
   | `PendingCommandProcessor`        | ~15   | each command type, permanent vs. temporary failure, UID mapping after a move           |
   | `DefaultMailSynchronizer`        | ~12   | "checked too recently", periodic vs. push, failure recording, null-listener regression |
   | `DefaultOutboxSender`            | ~10   | success, transient/permanent failure, retry limit, auth error, Sent upload             |
   | `DefaultMessageDeleteRepository` | ~10   | delete policy branches, no Trash folder, expunge, Outbox and Drafts                    |
   | `ServerErrorNotifier`            | ~8    | feature flag combinations per error type, clearing                                     |

   The other ten classes need about 45 more (2 to 6 each). The first batch includes regression tests for the two
   crashes fixed in the port (see [`bugs.md`](bugs.md)), which `AGENTS.md` asks for.

   **Decision for the user before starting:** how to set the tests up. `NotificationController` is a final class with
   an internal constructor, and `LocalStore`, `LocalFolder` and the backend have no fakes. Options: (a) Robolectric
   with a real `LocalStore` on a temporary database, like `legacy:core`'s tests (no production changes, slower
   tests); (b) small interfaces in front of the legacy store and notification code, with fakes (faster, cleaner
   tests, but production changes that need approval). Suggested: start with (a) on `ServerErrorNotifier` and one
   store-heavy class, then decide whether (b) is worth it.

5. **`MessageListCache`:** the deferred decision in "Open items".

6. **Pre-existing dead code (not from these branches, left alone):** on `main`, nothing called
   `MessagingController.clearCertificateErrorNotifications`, so the `clearCertificateErrorNotifications` methods of
   `NotificationController` and `CertificateErrorNotificationController` are unused outside tests: the app never
   clears certificate error notifications. Possibly a missing feature rather than dead code; ask before deleting.
   Also unused on `main` already, except by its own test: `legacy/core/.../controller/UidReverseComparator.java` (the
   IMAP backend has its own).

7. **Publishing (done 2026-10-06, fork only):** stacked pull requests in `dougsparling/thunderbird-android`, none
   against `thunderbird/thunderbird-android`: #1 `doug-china-mirrors` → `main`, #2 `doug-scenario-harness` →
   `doug-china-mirrors`, #3 this branch → `doug-scenario-harness`. Push follow-up commits to the matching branch.

8. **Scenario tests in CI (done 2026-10-06):** `.github/workflows/test-scenarios.yml` runs the suite on pull requests
   and on demand on a standard `ubuntu-latest` runner, about 18 minutes with the Gradle and Robolectric caches warm.
   Getting there needed three test-infrastructure fixes for James races under load (see `HANDOFF.md`, "James races
   under load"). Four consecutive runs passed (run 37371435765 in the fork, attempts 1 to 4). GitHub sometimes
   doesn't assign a runner to a re-run ("job was not acquired by Runner"); that isn't a test failure.

## Open items

- **`MessageListCache` stays.** Applying moves and deletes locally right away (so the cache could go) lets a sync
  that's already running download the moved message again into the source folder, until the next sync removes it.
  Today the cache hides the message and the local move runs after that sync. The suite can't see this (it waits for
  idle), so it needs a deliberate decision, e.g. together with per-message reconciliation.
- **New flake:** `FolderSyncedTooRecentlyScenarioTest` failed once in a full run while provisioning the test server
  (`IMAP LOGIN failed ... Invalid login/password` from the provisioning client, before any app code ran); it passed
  on its own.
- **Flake:** `PushSeesOtherClientExpungeScenarioTest` timed out once in a full run ("waiting for the app to send
  IDLE"); it passed on its own. Push scenarios are already known to be flaky under parallel load (`HANDOFF.md`).
- **Fetching dependencies:** this checkout's `local.properties` sets `useChinaMirrors=true`, so a plain `./gradlew`
  uses the mirrors, which hold incomplete copies of some artifacts (e.g. `com.github.gmazzo.buildconfig:plugin`).
  Fetch with `-PuseChinaMirrors=false`.
- **Robolectric SDK jars** aren't fetched by Gradle: Robolectric downloads them when a test first needs one, and
  it doesn't use the HTTP proxy, so the download stalls. `:legacy:ui:legacy` needs SDK 31
  (`org.robolectric:android-all-instrumented:12-robolectric-7732740-i7`); it was fetched into `~/.m2` with `curl
  --proxy`.
- **Wake lock tag:** the manual mail check still uses the tag `K9 MessagingController.checkMail`, unchanged on
  purpose.

