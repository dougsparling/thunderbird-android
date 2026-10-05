# Replacing MessagingController: switchover log

Branch `doug-messaging-controller-switchover`, stacked on `doug-scenario-harness`. Goal: delete `MessagingController`
and its helpers, with the app running on a replacement and the scenario suite (105 scenarios) green after every slice.
Decisions are in [`HANDOFF.md`](HANDOFF.md) ("Parity suite for replacing MessagingController").

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
  Suite: 105/105 (one run had the known push timeout, `PushResumesAfterDisconnectScenarioTest`; it passed alone and
  in the next full run).
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
- **Not run offline:** `:legacy:ui:legacy` unit tests (Robolectric wants an Android SDK jar that isn't cached) and
  `:feature:navigation:drawer:dropdown` unit tests (`ui-test-junit4` isn't cached).
- **Not run offline (step 3):** `:legacy:message` unit tests (same `error_prone_annotations` gap as above).
- **Stale ProGuard rule:** `app-thunderbird` and `app-k9mail` `proguard-rules.pro` still keep
  `com.fsck.k9.controller.MessagingControllerCommands$*`, which no longer exists (the pending commands are
  `com.fsck.k9.controller.Pending*` with Moshi's generated adapters since step 1). Left alone; check a release build
  before removing it.
- **Wake lock tag:** the manual mail check still uses the tag `K9 MessagingController.checkMail`, unchanged on
  purpose.
- **Unused helper:** `com.fsck.k9.helper.MutableBoolean` was only used by the controller.
