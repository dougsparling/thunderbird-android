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

| Piece | Where | Role |
|---|---|---|
| Fixture DSL | `mail/testserver/fixture` | `userFixture { inbox { message { … } }; folder("A") { folder("B") } }`, deterministic MIME, `raw()`/`eml()` |
| Provisioning | `mail/testserver/provision/.../provision` | Independent IMAP client, seeder, server state reader, James WebAdmin adapter, `testserver.*` config |
| Fault proxy | `mail/testserver/provision/.../proxy` | Per-test TCP proxy: readable, credential-redacted transcript (printed on failure), fault rules DSL |
| Harness | `app-thunderbird/src/test/kotlin/net/thunderbird/android/scenario/harness` | `ScenarioTest`/`ScenarioRule`/`ScenarioScope` |
| Scenarios | `app-thunderbird/src/test/kotlin/net/thunderbird/android/scenario/*ScenarioTest.kt` | One test method per class |

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

## In flight at handoff

A Claude subagent was extending the harness (uncommitted changes in the working tree; files named `Tmp*ScenarioTest.kt`
are its temporary verification tests and must be deleted before committing). Requested scope:
1. `MessagingControllerQueue.awaitIdle` fails fast (with the cause) when the controller thread dies. In debug builds
   `MessagingController` throws `AssertionError` for unexpected pending-command exceptions (~`:825-831`), which kills
   the thread.
2. ~~`@KnownBug`~~: dropped by the user; don't build it.
3. Driver actions: `delete`, `archive`, `move`, star/unstar, mark unread, `markAllRead`, `emptyTrash`, `loadMore`,
   `refreshFolders`, `updatePassword`; `AccountSpec.notifyNewMail`; Trash/Archive special folders working out of the
   box.
4. Server helpers (another client): `deleteMessage(expunge)`, `moveMessage`, `setFlags`, `deleteFolder`,
   `renameFolder`, `recreateFolder` (new UIDVALIDITY), via a new `DefaultMailboxEditor` in the provision module.
5. Proxy: `respond("NO …")` action (answer with the client's tag without forwarding), command-argument predicate
   `imap.onCommand("UID FETCH") { args -> … }`.
6. Device: going offline/online (connectivity callbacks), firing due AlarmManager alarms in `advanceTime`.

**Next session, first step:** check `git status`/`git diff`, delete the `Tmp*` tests, run the verification commands
above, review the new API (look at `ScenarioDriver`, `ScenarioServer`, `ScenarioDevice`, `ScenarioRule`,
`NetworkRules`), and commit in pieces (queue fix, proxy additions, server helpers, driver actions, device additions).
If something is half-done, finish or remove it; don't commit broken pieces.

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
