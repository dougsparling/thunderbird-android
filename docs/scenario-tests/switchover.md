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
