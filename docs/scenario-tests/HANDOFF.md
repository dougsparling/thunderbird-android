# Scenario test harness: handoff

Status as of 2026-09-29, branch `doug-scenario-harness` (branched from `doug-china-mirrors`, not pushed).

## Why this exists

The legacy sync core (`legacy/`, above all `MessagingController.java`, ~2.9k lines) is being replaced
strangler-fig style, but it has almost no end-to-end coverage and background sync is impractical to verify by hand.
The harness runs the **real app** (Robolectric, real Koin graph) against a **real IMAP server** (Apache James) and
checks behaviour from the outside, so the tests keep passing when the sync core is rewritten or the server is swapped.

**Goal of the test suite:** a safety net for removing legacy code. Pin behaviour the app gets *right* today.
Bug-hunting is explicitly out of scope for now; scenarios that expose bugs go to a backlog (see below).

## Working agreements with the user

- **Metered connection:** always run Gradle with `--offline` unless the user confirms they're on an unmetered
  connection. To fetch new dependencies the user runs the command themselves with `-PuseChinaMirrors=true`.
- **No production code changes**, except narrowly scoped dependency injection the user approves (so far only
  injecting `kotlin.time.Clock` into `MessagingController`). No structural refactors of app code.
- Commit in logical Conventional Commits pieces; don't push.
- Keep the driver API in user terms (what the user does), not in terms of how the app works.
- The user is short on Claude quota: delegate scenario writing to opencode (see below) and keep verification cheap.

## How to run

```
./gradlew --offline :app-thunderbird:testFossDebugUnitTest -PscenarioTests            # all scenarios
./gradlew --offline :app-thunderbird:testFossDebugUnitTest --tests 'net.thunderbird.android.scenario.PushScenarioTest'
./gradlew --offline :app-thunderbird:detekt :app-thunderbird:spotlessCheck             # CI gate is plain `detekt`
./gradlew --offline :mail:testserver:fixture:test :mail:testserver:provision:test
```

Scenario tests are excluded from normal unit test runs; they run with `-PscenarioTests` or when `--tests` names them.
Gradle starts James automatically (a build service in `build-plugin/.../testserver/`, James memory app 3.9.0 from
Maven Central, config templates in `mail/testserver/james/conf/`, working dir `build/testserver/james/`, log
`build/testserver/james/james.log`) and stops it at the end of the build. To use an existing server instead:
`-Ptestserver.imap=host:port -Ptestserver.domain=... [-Ptestserver.admin=url]`. Each scenario class runs in its own
JVM (`forkEvery = 1`), in parallel against the one James; each test gets its own server user.

## Architecture

|    Piece     |                                        Where                                        |                                                    Role                                                     |
|--------------|-------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Fixture DSL  | `mail/testserver/fixture`                                                           | `userFixture { inbox { message { … } }; folder("A") { folder("B") } }`, deterministic MIME, `raw()`/`eml()` |
| Provisioning | `mail/testserver/provision/.../provision`                                           | Independent IMAP client, seeder, server state reader, James WebAdmin adapter, `testserver.*` config         |
| Fault proxy  | `mail/testserver/provision/.../proxy`                                               | Per-test TCP proxy: readable, credential-redacted transcript (printed on failure), fault rules DSL          |
| Harness      | `app-thunderbird/src/test/kotlin/net/thunderbird/android/scenario/harness`          | `ScenarioTest`/`ScenarioRule`/`ScenarioScope`                                                               |
| Scenarios    | `app-thunderbird/src/test/kotlin/net/thunderbird/android/scenario/*ScenarioTest.kt` | One test method per class                                                                                   |

A scenario (`scenario { … }`) sees:

- `server`: create/seed users (`server.user { … }`), `deliver`, `stateOf(user)`, and helpers that act as another
  client on the server (being added; see "In flight").
- `client.account(user, password, checkIntervalMinutes, …)`: adds the account through the app's own account creator.
- `driver` (`ScenarioDriver`, implemented by `LegacyScenarioDriver`): **only things that depend on how the app is
  built.** User actions (`pullToRefresh`, `markRead`, `enablePush`, …) call the same entry points as the UI
  (`LegacyMessageListFragment`, which is the default message list while `enable_message_list_new_state` is off), and
  reads (`folderList`, `messageList`) use the UI's data sources. `awaitIdle()` waits for controller work. A rewrite
  would provide a new driver; the scenarios stay the same.
- `device` (`ScenarioDevice`, concrete, Robolectric): **Android platform behaviour, independent of the sync core.**
  Permissions (`setPermission(AppPermission.X, granted)`, deny only before first use), `notifications()`,
  `advanceTime(d)` (scenario clock; due WorkManager work runs as Android would), `settle()`, runs services the app
  starts. Starts "online, notifications + exact alarms granted".
- `proxy`, `network { rules }`, `awaitAppListening()` (IDLE accepted, read from the transcript), `eventually { }` for
  server-initiated effects.

## Decisions and gotchas (don't rediscover these)

- **One test method per scenario class.** `K9`/`Core` Kotlin objects cache Koin dependencies per class loader, so a
  second app instance in the same JVM would be stale. `ScenarioJvmGuard` enforces this.
- **`ScenarioApplication`** (test-only copy of the final `ThunderbirdApp`, without telemetry) loads Koin overrides
  after Koin starts and before `onCreate`: currently `ScenarioClock`. Keep in sync with `ThunderbirdApp`.
- **`ScenarioClock`** = real time + a growing offset. IMAP/POP3 sync still write "last checked" with the system clock
  (`ImapSync.kt:229,247`), and the offset keeps the scenario clock ahead of those writes. `MessagingController` uses
  the injected clock only for the "folder checked too recently" check (`MessagingController.java` ~:2498). The folder
  list staleness check (`:628`) still uses the wall clock.
- **Background work and the network:** due WorkManager work that needs a network (the periodic mail sync does) waits
  while `device.setOnline(false)` and runs once the device is back online, as on Android. To make a sync run and fail,
  keep the device online and break the server path with network rules (`refuseConnections()` + `proxy.disconnectAll()`).
- **WorkManager** is replaced with the test implementation (`SynchronousExecutor`, scenario clock via
  `Configuration.setClock`) before the app first gets it from Koin; a check fails loudly if that ever changes. The
  test scheduler resets constraints after every run, so due work is triggered with delays met first, constraints last.
- **Robolectric defaults differ from a set-up device:** exact alarms denied and the active network lacks
  `NET_CAPABILITY_INTERNET`; `ScenarioDevice` fixes both at start. Robolectric only records started services;
  `ScenarioDevice` runs them (so `PushService` posts its foreground notification).
- **Robolectric SDK pinned to 37** (`ScenarioTest.ROBOLECTRIC_SDK`) because only that android-all jar is cached.
- **IMAP compression is off** for test accounts, otherwise the proxy transcript is unreadable.
- **Proxy rule semantics:** `beforeServerSees` holds the command; `afterServerResponds` lets the server apply it and
  drops the reply. `onCommand("FETCH")` does not match `UID FETCH`. Counts reset on every `apply`. `refuse` = accept
  then RST. `stall()` is useless until timeouts are injectable (the app's read timeout is a fixed 60 s).
- **Offline:** pooled IMAP connections survive rule changes, so "offline" = `refuseConnections()` +
  `proxy.disconnectAll()`.
- **James:** separator is `.`; James auto-creates Drafts/Outbox/Sent/Spam/Trash for new users; requires a Bcc-removal
  mailet (already in the config); `<plainAuthDisallowed>false` + `<compress>false` in `imapserver.xml`.
- **Two message lists exist**: the new MVI one (`enable_message_list_new_state`, off by default) seems to only reload
  local data on `Refresh`, without contacting the server. Worth checking before that flag flips.
- **Detekt:** CI runs plain `./gradlew detekt` (covers test sources). The stricter `detektMain`/`detektTest` are not
  enforced, and existing modules don't pass them either.

## Harness extensions (done, committed)

- **Queue:** `awaitIdle` waits for the sync engine's idle signal (`RemoteWorkQueue`) and fails fast with
  `SyncEngineStoppedException` (with the cause) when the engine stops (debug builds throw `AssertionError` for
  unexpected pending-command exceptions, `PendingCommandReplay`). Before the switchover it reflected into
  `MessagingController`'s thread and queue.
- **Driver:** `markUnread`, `setStarred(…, starred)`, `delete`, `archive`, `move(…, to)`, `markAllRead`, `emptyTrash`,
  `loadMore`, `refreshFolders`, `updatePassword`, plus `client.account(…, notifyNewMail = false)`. Each mirrors the UI
  (comments name the UI code) and refuses where the UI would (no archive/trash folder, outbox, no "load more").
  `updatePassword` skips the settings screen's connection check.
- **Server (another client):** `deleteMessage(user, folder, subject, expunge = true)`, `moveMessage`, `setFlags(…, add,
  remove)`, `deleteFolder`, `renameFolder`, `recreateFolder(user, path) { messages }`; `ServerFolderState.uidValidity`.
- **Proxy:** `beforeServerSees { respond("NO [UNAVAILABLE] …") }` answers with the client's tag without forwarding;
  `imap.onCommand("UID FETCH", label = "…") { args -> … }` matches command arguments.
- **Device/scope:** `device.setOnline(online)` (app's network callbacks fire); `goOffline()`/`goOnline()` on the scope
  combine that with the proxy (refuse + reset connections). `advanceTime` also advances Robolectric's clock and
  `settle()` fires due AlarmManager alarms (push uses `ELAPSED_REALTIME_WAKEUP`; 30 minutes triggers an IDLE refresh).
- **Setup facts:** a new account auto-selects special folders from SPECIAL-USE (James' Trash works; seed
  `folder("Archive", specialUse = SpecialUse.ARCHIVE)` for archive) and enables INBOX notifications.

**Observed behaviour worth knowing when writing scenarios:**

- Pull to refresh never produces new-mail notifications (the UI passes `notify = false`); use push or periodic sync.
- Delete moves to Trash and marks `\Seen`; archive also marks `\Seen`.
- Messages flagged `\Deleted` but not expunged on the server are hidden by the app.
- James recreate: new UIDVALIDITY, UIDs restart at 1; the app handled it correctly.
- James has a server-side "Outbox", so the folder list shows two Outboxes.
- An account set up with a wrong password gets only the local Outbox and no auth notification; after
  `updatePassword`, call `refreshFolders` before INBOX exists.
- **Backlog candidate already seen:** `UID STORE` answered once with `NO [UNAVAILABLE]` loses the mark-as-read (unread
  on server and in app after the next sync): transient NO is treated as permanent (matches plan item A4/A6).

## Next: write the scenarios (delegated to opencode)

The plan is in [`scenario-plan.md`](scenario-plan.md): about 29 scenarios to implement, 8 marked `[CHECK]`
(implement, run, move to the backlog if they fail because of app behaviour), 10 marked `[BACKLOG]` (don't
implement; predicted app bugs from code analysis and GitHub issues). The line references there come from a
read-through of `MessagingController.java`, `ImapSync.kt` and friends.

**Delegation setup agreed with the user:** one `opencode run -m opencode/deepseek-v4.1-flash` per scenario,
**sequentially** in the main tree (parallel Gradle builds and a shared James conflict), driven by a background shell
loop that appends each result to a status file. Each task prompt must be self-contained and sharply defined:

- the exact file path and class name; Given/When/Then copied from the plan;
- the harness API signatures it may use, plus one existing scenario as a style reference (e.g.
  `MarkReadLostResponseScenarioTest.kt`, `PeriodicSyncScenarioTest.kt`);
- the command to run only that test (see above);
- rules: create only that one file; never edit the harness, test server modules or app code; never weaken an
  assertion to make it pass; one test method; format with `./gradlew --offline -q :app-thunderbird:spotlessApply`;
- **self-verification** (the user wants the subagent to own this): the scenario passes; then temporarily invert its key
  assertion, confirm it fails, restore it, confirm it passes; `git status` shows only its own new file;
- last line of output: `PASS <class>` or `FAIL <class>: <failing assertion> | <relevant transcript lines>` when the
  app misbehaves (for `[CHECK]` scenarios that means "move to backlog").

Afterwards (cheap, no deep review): run the full scenario suite once, check `git status` for stray edits, look only at
`FAIL` reports, and write `backlog.md` next to this file with every `[BACKLOG]` scenario plus confirmed `[CHECK]`
failures (Given/When/Then, issue numbers, file:line, observed failure). Commit the scenarios in a few groups.

## Deferred / open

- Drafts (compose flow) and sending/Outbox (needs James SMTP behind the proxy): not started.
- IMAP/POP3 still write "last checked" with the system clock (could get the same clock injection, as default
  parameters on `ImapSync`/`Pop3Sync`, if the user agrees).
- The folder list staleness check uses the wall clock (`MessagingController.java:628`).
- Transcript escapes backslashes (`\\Seen`), cosmetic.
- One-test-per-class limit goes away once the `K9`/`Core` singletons are gone.
- A UI-level driver (Maestro against James, or Compose tests) was discussed as a thin end-to-end layer; the user
  accepted the UI gap for now.
- A CI server matrix (Dovecot, Stalwart) was discussed; the provisioning layer is server-agnostic except the James
  adapter. Docker is deliberately not required.

## Scenario status (2026-09-29)

All non-[BACKLOG] plan items are written and committed: 26 pass (31 scenarios in the suite at the time, all green
with `-PscenarioTests`). Three are out of the suite and documented in [`backlog.md`](backlog.md): A3 and A9 fail because of
app behaviour; C4 needs a harness way to change INBOX's UIDVALIDITY. Written by opencode (DeepSeek Flash) and Claude
Sonnet subagents with self-verification (pass, invert key assertion → fail, restore).

Review (2026-09-30) found that self-verification doesn't catch assertions the app satisfies for another reason, so
review delegated scenarios for that. Fixed since: A8 (archiving marks read by itself, so the order is now checked
after the dropped connection), C11 (split: server unreachable while online →
`PeriodicSyncRetryAfterNetworkFailureScenarioTest`; device offline → `PeriodicSyncWaitsForNetworkScenarioTest`), C8/C10
(time no longer advances without bound inside `eventually`), C5/C6/B5/B11 tightened, A10 renamed to
`OfflineChangesAllReachServerScenarioTest` (the order of independent changes isn't pinned).

Cleanup (2026-09-30): scenarios follow the house test style (`// Arrange` / `// Act` / `// Assert`, see the rules in
`ScenarioTest`'s KDoc) and use shared helpers: `message("Subject")` in the fixture DSL (messages without a From get
`FixtureDefaults.SENDER`), `driver.subjects(account, folder)` / `driver.message(account, folder, subject)`,
`ServerFolderState.subjects`, and `TRASH`. Scenarios no longer refresh INBOX or the folder list right after adding an
account (setup already did), and no longer assert fixture state the harness guarantees. C9 is split into
`PushSeesOtherClientStarScenarioTest` and `PushSeesOtherClientExpungeScenarioTest`. 33 scenarios.

Known flake: `WrongPasswordScenarioTest` failed once in a full parallel run (no auth-error notification within the
20 s `eventually`, though the transcript shows the failed logins) and passes alone. Not investigated yet.

Delegation lesson: opencode auto-rejects tool calls outside the repo (e.g. `/tmp`) and ends the session, so task
prompts must say to stay inside the repo.

## Parity suite for replacing MessagingController (2026-10-05)

Goal (from the user): prove old and new sync core behave the same, then replace `MessagingController` on a stacked
branch. Decisions: one global serializer in the new engine (no queue per account), keep the pending-command log and
its semantics (parity; reconciliation is a later step whose acceptance tests are the backlog scenarios), no feature
flags, target only the legacy message list, ignore Global Database plans. Group E in `scenario-plan.md` lists the
scenarios; everything not blocked is written and committed, scenarios by Claude directly (no opencode).

**Build:** offline builds now need `-PuseChinaMirrors=false` (the user filled the cache from the official
repositories). `./gradlew --offline -PuseChinaMirrors=false :app-thunderbird:testFossDebugUnitTest -PscenarioTests`.

**Harness added:**

- Test server: James also serves SMTP (AUTH, unknown local recipients get 5xx), POP3, and IMAPS with a self-signed
  certificate generated by keytool (`testserver.smtp`, `.pop3`, `.imaps-untrusted`). Scope has `smtpProxy`,
  `pop3Proxy`, `smtpNetwork { }`; `goOffline()` covers all proxies.
- Driver: send, drafts, open/download message and attachments, remote search, thread actions, copy, spam, expunge,
  empty spam, batch actions across accounts, unified inbox, account and folder settings, notification buttons,
  delete confirmation, sync all, remove account, open folder, delete from outbox, outbox reads, non-blocking
  `startPullToRefresh`. Accounts: POP3, signed-out OAuth, untrusted TLS.
- Device: `tapNotificationAction`; notifications hide a group summary while its children show; the app's content
  providers are created (Robolectric doesn't).
- `restartApp()` (`AppRestart`): stops the old app, resets `K9`/`Core`/Application fields, `MessageListCache` and
  DataStore's open-file registries, restarts Koin and `onCreate` on the same files; scenario clock carries over.
- Scenarios start once feature flags have loaded (`awaitAppStarted`); before, early auth errors could notify nobody.
  This is the likely cause of the old `WrongPasswordScenarioTest` flake.

**Gotchas:** James delivers SMTP mail to local users asynchronously: check recipients in `eventually`, and check
"not sent" via the SMTP transcript (`C: DATA`). Opening a message dismisses notifications on the controller's thread
pool, which `awaitIdle` doesn't cover: use `eventually`. Message view text is HTML with a style sheet (the driver
strips it). The app's own deletes always `UID EXPUNGE`; the expunge setting only affects sync.

**Clock:** with the user's approval, `MessagingController` (folder list refresh, last sync) and `ImapSync` (last
checked) read the Koin `Clock`, so the scenario clock moves them; POP3 sync still uses the system clock. This
corrected `PeriodicSyncRetryAfterNetworkFailureScenarioTest` (the retry skips INBOX as checked too recently).

**New findings:** see `backlog.md` (lost move response duplicate; pinned Move to Drafts, certificate and retry
behaviour). Known flaky under parallel load: `PushScenarioTest` (once). Suite: 105 scenarios, all passing.

**Next:** stacked branch with the switchover (new modules, per the scoping discussion): engine under the controller
first, then entry points, keeping this suite green at each step, with a second `ScenarioDriver` only if the UI entry
points change.
