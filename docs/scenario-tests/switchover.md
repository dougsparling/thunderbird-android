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
- **Slice 4 (next):** port the controller and its helpers to Kotlin in `:internal`, implementing the contracts
  directly (one class per contract, plus shared pieces: account stores and backends, a `SyncEventBus` replacing the
  listener set and `MemorizingMessagingListener`, server-error notifications, and a pending-command queue whose
  processor dispatches on the command type instead of `PendingCommand.execute(controller)`). The command classes
  become Kotlin data classes in `legacy:core` (`LocalStore` and storage migrations use them) with Moshi codegen
  adapters that read and write the same JSON. Per-call listeners become return values (e.g. a check's sync failures
  for `syncPeriodically`). `MailSyncWorker` becomes a `CoroutineWorker`. Then delete the controller, its helpers, the
  `ControllerEngine` seam and the adapters, and the listener types nothing uses any more.

## Open items

- **`MessageListCache` stays.** Applying moves and deletes locally right away (so the cache could go) lets a sync
  that's already running download the moved message again into the source folder, until the next sync removes it.
  Today the cache hides the message and the local move runs after that sync. The suite can't see this (it waits for
  idle), so it needs a deliberate decision, e.g. together with per-message reconciliation.
- **Not run offline:** `:legacy:ui:legacy` unit tests (Robolectric wants an Android SDK jar that isn't cached) and
  `:feature:navigation:drawer:dropdown` unit tests (`ui-test-junit4` isn't cached).
