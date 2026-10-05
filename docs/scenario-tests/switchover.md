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
- **Slice 4, step 2 (next): Kotlin implementation in `:internal`, bound instead of the adapters.** Design worked
  out, no code yet. Package `net.thunderbird.feature.mail.sync.internal`:
  - `AccountStores`: replaces `LegacyAccounts`; account by `AccountId`/UUID (same cached instance), all accounts,
    `saveAccount` (via `LegacyAccountDtoManager`, which `Preferences` implements), backend, `LocalStore`,
    `MessageStore`, folder ID <-> server ID, and `notifyChanged(account)` =
    `MessageListRepository.notifyMessageListChanged` (what `notifyFolderStatusChanged` does now).
  - `SyncEventBus`: replaces the listener set and `MemorizingMessagingListener`: `emit(SyncEvent)` memorizes per
    `uuid:folderId` (started/finished/failed + progress) and delivers under a lock; `observe()` is a `callbackFlow`
    that replays like `refreshOther()` and then gets live events; `forgetAccount()` for `onAccountRemoved`.
  - `ServerErrorNotifier`: `handleAuthenticationFailure` (OAuth migration, in-app notification via
    `NotificationManager.send` on a Main.immediate scope after creating it with `runBlocking`, like the compat
    classes did; old controller notification behind the feature flags), `handleException`,
    `notifyUserIfCertificateProblem`, `isAuthenticationProblem`, `checkAuthenticationProblem`.
  - `PendingCommandQueue` (`add`, `processInBackground` = old `processPendingCommands`, `processNow` = old
    `processPendingCommandsSynchronous` incl. certificate notification) and `PendingCommandProcessor` (the
    `processPending*` methods, `processPendingReplace` from `DraftOperations`, `destroyPlaceholderMessages`; `when` on
    the sealed `PendingCommand`). Work goes straight to `RemoteWorkSerializer` (`put` = FOREGROUND,
    `putBackground` = BACKGROUND).
  - One class per contract: `DefaultMessageCapabilities`, `DefaultMessageFlagRepository`,
    `DefaultMessageMoveRepository` (+ archive, `moveToDrafts`; shared `MessageMover` = `moveOrCopyMessageSynchronous`
    + `queueMoveOrCopy`, also used by delete), `DefaultMessageDeleteRepository` (exposes
    `deleteMessages(refs, skipTrashFolder)` for drafts), `DefaultMessageDraftRepository`, `DefaultOutboxSender`
    (exposes the background send for `checkMail`), `DefaultMailSynchronizer` (+ `FolderSyncListener` = port of
    `ControllerSyncListener`), `DefaultRemoteContentRepository` (search as a `flow` on IO with `runInterruptible`;
    attachment progress as a `MutableSharedFlow`; port `ProgressBodyFactory`), `DefaultNewMailNotifications`
    (`NotificationOperations`). Shared helper for grouping references by account/folder, threads, and
    `MessageListCache` hide/flag calls (kept, see "Open items").
  - Keep the controller's exact order of local writes, cache updates, queueing, events and notifications.
    Per-call listeners become return values: `checkMail` completes when "finalize sync" runs; `syncPeriodically`
    tracks its own folder-sync failures (the `ControllerSyncListener.syncFailed` path and the
    pending-command-failure path in `syncFolder`, which only told the per-call listener and NPE'd without one;
    don't emit that one globally). `checkMailStarted` is emitted on the caller's thread before queueing.
  - Inject `PowerManager` (`com.fsck.k9.mail.power`), `Logger` (+ `named("syncDebug")`), `Clock`,
    `FeatureFlagProvider`, `NotificationController`, `NotificationStrategy`, `OutboxFolderManager`,
    `SaveMessageDataCreator`, `LocalDeleteOperationDecider`, `LocalMessageUidPrefixProvider`, `LocalMessageReader`,
    the app scope. The account's `toString()` is privacy-safe for logs.
- **Slice 4, step 3:** delete `MessagingController`, `ArchiveOperations`, `DraftOperations`,
  `NotificationOperations`, `MemorizingMessagingListener`, `ControllerExtension` (+ `TestApp` binding,
  `controllerExtensions` in `LegacyCommonAppModule`), `ControllerEngine`/`SerializerControllerEngine`/
  `FakeControllerEngine`, `ProgressBodyFactory`, `MessagingControllerTest` (mock-based; scenarios cover it), the
  `...internal.legacy` adapters, and listener types nothing uses any more (`MessagingListener`,
  `SimpleMessagingListener`, `MessagingControllerRegistry`, `MessagingControllerMailChecker` in `legacy:message`).
  `MailSyncWorker` becomes a `CoroutineWorker`. Update the comment in `UpgradeDatabaseActivity` and
  `ScenarioApplication`.

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
