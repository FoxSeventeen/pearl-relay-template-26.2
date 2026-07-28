# Implementation Plan: Pearl Relay v1.1 Safe Trigger

## Status

- Intent confirmed with the project owner on 2026-07-27.
- Implementation started on branch `codex/v1.1-safe-trigger`.
- Task 1 completed on 2026-07-27: Java 25 build, JUnit, and server GameTest pass.
- The existing production server remains stopped.
- All automated live-server work must use `/opt/minecraft-pearlrelay-test`.

## Overview

Pearl Relay v1.1 will make `/pearlrelay fire <name>` fail safe. Before a fake
player is created, the mod will verify the dimension, loaded chunks, saved
activation target, fake-player reachability, and the presence of at least one
Ender Pearl owned by the invoking player in the activation target's chunk.
Valid relays continue to be triggered by spawning a Carpet fake player at the
saved `spawn` position, looking at the saved `lookAt` position, and issuing one
right-click. The mod does not select, move, or directly activate an Ender Pearl.

The execution path will be moved from three independent pending lists to an
explicit lifecycle with timeouts and idempotent cleanup. Every accepted,
rejected, completed, and failed trigger will produce a structured log event.

## Confirmed Intent

- **Primary user:** a technical Minecraft server player triggering their own
  named pearl stasis relays.
- **Desired outcome:** valid relays fire reliably; invalid relays are rejected
  with an exact reason and never leave an orphan fake player.
- **No automatic chunk loading:** an unloaded relevant chunk is a rejection.
- **Pearl readiness:** at least one Ender Pearl owned by the invoking player
  must exist in the target block's chunk.
- **Multiple pearls:** allowed. The check is boolean; no pearl is selected.
- **Target integrity:** the saved target block type must still match. Mutable
  block state such as open/closed or powered/unpowered is not fingerprinted.
- **Release gate:** headless checks produce `v1.1.0-rc.1`; real-player
  acceptance is required before `v1.1.0`.
- **Out of scope:** permissions, cooldowns, GUI, automatic chunk loading, and
  direct control or selection of an Ender Pearl.

## Existing Baseline

The current `1.0.0` code already provides:

- `/pearlrelay test`
- `/pearlrelay fireRaw <bot> <dimension> <spawn> <lookAt>`
- `/pearlrelay save|list|fire|remove`
- Per-player UUID JSON configuration
- Carpet fake-player creation/reuse, delayed aiming, one use action, and cleanup

Known baseline gaps:

- No automated test source set exists.
- `gradle.properties` contains a Windows-only JDK path; the current macOS
  workspace has no usable Java runtime.
- `fire` creates the fake player before a complete safety preflight.
- Target block identity is not persisted.
- Pearl presence and ownership are not checked.
- Cleanup is spread across independent static queues.
- No repository tags or changelog currently exist.

## Architecture Decisions

### 1. Keep activation and readiness separate

`spawn` and `lookAt` remain fake-player activation data. Pearl discovery is a
readiness predicate only:

```text
ownedPearlCountInTargetChunk >= 1
```

The pearl count is logged. It does not influence where the fake player looks or
what it right-clicks.

### 2. Do not cause chunk loads during preflight

The preflight service must use a non-loading chunk lookup for both the fake
player spawn chunk and target block chunk. It must not call an API that creates
a chunk ticket or synchronously loads/generates a chunk.

Automated tests will compare loaded-chunk/ticket state before and after rejected
requests.

### 3. Save a target fingerprint, not a full block state

Newly saved relays persist:

- Target block position
- Target block registry identifier
- Configuration schema version

The fingerprint excludes block-state properties. A saved oak trapdoor may open
or close and remain valid, but replacing it with a lever or air invalidates the
relay.

Legacy v1 configuration remains readable and listable. Because the original
block type cannot be reconstructed safely, firing a legacy relay returns a
`RELAY_REQUIRES_RESAVE` error. Saving the same relay name again upgrades it.

### 4. Use a typed, side-effect-free preflight result

Preflight returns either a validated execution request or a stable failure code.
It does not create or teleport a fake player.

Initial failure codes:

- `RELAY_NOT_FOUND`
- `RELAY_REQUIRES_RESAVE`
- `DIMENSION_UNAVAILABLE`
- `SPAWN_CHUNK_UNLOADED`
- `TARGET_CHUNK_UNLOADED`
- `SPAWN_POSITION_BLOCKED`
- `TARGET_BLOCK_CHANGED`
- `TARGET_UNREACHABLE`
- `OWNED_PEARL_NOT_FOUND`
- `EXECUTION_ALREADY_ACTIVE`
- `FAKE_PLAYER_NAME_IN_USE`

Every failure has one player-facing message and one structured-log form.

### 5. Replace pending lists with an execution lifecycle

One execution record owns all phases:

```text
PRECHECKED
    -> WAITING_FOR_FAKE_PLAYER
    -> AIMING
    -> USING
    -> CLEANING_UP
    -> COMPLETED

Any phase -> FAILED -> CLEANING_UP -> TERMINATED
```

Execution records have an ID, deadlines, terminal result, and idempotent
cleanup. Only one active execution is allowed for the same generated bot name.

### 6. Define success honestly

The server can prove that the fake player was created, aimed, issued one use
action, and was removed. It cannot generically prove that every redstone device
or pearl stasis design completed its physical purpose.

The mod therefore reports:

- `interaction dispatched` after the Carpet use action
- `execution completed` after fake-player cleanup

Actual pearl release/teleport remains a real-player acceptance test.

### 7. Use standard logs with stable key/value fields

Do not introduce a second log file in v1.1. Use the existing server log through
SLF4J with fields such as:

```text
event=relay_fire execution_id=... relay=... player_uuid=...
dimension=... target_chunk=... pearl_count=... phase=...
result=accepted|rejected|completed|failed code=... duration_ms=...
```

No credentials or full configuration file contents may be logged.

## Dependency Graph

```text
Portable build + test harness
        |
        +--> Target fingerprint schema + legacy handling
        |           |
        |           +--> Save-time target capture
        |                       |
        +--> Typed preflight service
                    |
                    +--> Named fire integration
                                |
                                +--> Execution lifecycle + cleanup
                                            |
                                            +--> Structured logging
                                                        |
                                                        +--> Remote RC validation
                                                                    |
                                                                    +--> Player acceptance
                                                                                |
                                                                                +--> v1.1.0
```

## Task 1: Establish a portable Java 25 build and test harness

**Description:** Remove the machine-specific JDK assumption, verify the project
on Java 25, and add the smallest unit/GameTest structure needed for test-first
implementation. Use the isolated server's Java 25 environment as the initial
canonical build environment if a local JDK is still unavailable.

**Acceptance criteria:**

- [ ] `./gradlew clean build` succeeds without a hard-coded Windows JDK path.
- [ ] A unit test task and a Fabric server/GameTest task can execute and report
      pass/fail.
- [ ] Build output remains ignored and no dependency or credential is committed.

**Verification:**

- [ ] Run `./gradlew clean test build`.
- [ ] Start a minimal Fabric GameTest server on Java 25.
- [ ] Record Minecraft, Loader, Fabric API, Carpet, Gradle, and Java versions.

**Dependencies:** None

**Files likely touched:**

- `build.gradle`
- `gradle.properties`
- `src/test/java/...`
- `src/gametest/java/...` or the Loom-supported equivalent

**Estimated scope:** Medium (3-5 files)

## Task 2: Define failure contracts and target fingerprint storage

**Description:** Add typed preflight failures and extend relay configuration
with a schema version and immutable target fingerprint. Preserve loading and
listing of v1 files, but fail safely with `RELAY_REQUIRES_RESAVE` until the user
overwrites a legacy relay.

**Acceptance criteria:**

- [ ] Newly saved relay JSON contains schema version, target position, and block
      registry ID.
- [ ] Existing v1 JSON loads without deletion or server crash.
- [ ] A legacy relay remains visible in `list` and returns a precise resave
      instruction from `fire`.

**Verification:**

- [ ] Unit tests cover v1 load, v2 round-trip, malformed/partial data, and
      overwrite migration.
- [ ] Golden JSON fixtures prove stable field names.
- [ ] Build succeeds after the schema change.

**Dependencies:** Task 1

**Files likely touched:**

- `src/main/java/com/foxseventeen/pearlrelay/config/RelayConfigManager.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayFailure.java`
- `src/test/resources/...`
- `src/test/java/...`

**Estimated scope:** Medium (3-5 files)

## Task 3: Capture and validate the activation target at save time

**Description:** Resolve the activation target from the configured spawn eye
position toward `lookAt`, require a loaded and reachable target block, then save
its position and registry ID. Saving must not create a fake player.

**Acceptance criteria:**

- [ ] `save` rejects unloaded spawn/target chunks, air/missed targets, blocked
      spawn positions, and targets outside fake-player reach.
- [ ] A valid target stores the exact hit block position and registry ID.
- [ ] Saving the same name safely overwrites and upgrades a legacy relay.

**Verification:**

- [ ] GameTests cover a reachable note block, obstructed ray, excessive
      distance, air target, and mutable block state.
- [ ] An assertion proves rejected saves create no fake player and no chunk
      ticket.
- [ ] Existing `save/list/remove` behavior remains functional.

**Dependencies:** Tasks 1-2

**Files likely touched:**

- `src/main/java/com/foxseventeen/pearlrelay/command/PearlRelayCommand.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayTargetResolver.java`
- `src/main/java/com/foxseventeen/pearlrelay/config/RelayConfigManager.java`
- `src/gametest/java/...`

**Estimated scope:** Medium (3-5 files)

## Checkpoint A: Foundation and configuration

- [ ] Clean build and all tests pass on Java 25.
- [ ] Old configuration is preserved and produces a migration message.
- [ ] New saves produce a reproducible target fingerprint.
- [ ] No command in this checkpoint creates an unintended chunk ticket.
- [ ] Review the JSON and player-facing error contract before continuing.

## Task 4: Implement side-effect-free named-relay preflight

**Description:** Build a dedicated preflight service for `fire`. Validate the
dimension, non-loading chunk state, spawn collision, saved target fingerprint,
raycast/reachability, and owned Ender Pearl presence in the target block's
chunk. Multiple owned pearls are accepted and counted.

**Acceptance criteria:**

- [ ] Every confirmed invalid state maps to one stable failure code.
- [ ] Zero matching owned pearls rejects; one or multiple matching pearls pass.
- [ ] Pearls owned by another player do not satisfy the check.
- [ ] Preflight has no fake-player, world mutation, or chunk-loading side effect.

**Verification:**

- [ ] Tests cover missing dimension, unloaded spawn chunk, unloaded target
      chunk, changed target block, unreachable target, no pearl, other-owner
      pearl, one owned pearl, and multiple owned pearls.
- [ ] Test block-state changes of the same block type remain valid.
- [ ] Compare loaded chunks/tickets and entity counts before and after every
      rejected case.

**Dependencies:** Tasks 1-3

**Files likely touched:**

- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayPreflightService.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayPreflightResult.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayFailure.java`
- `src/gametest/java/...`

**Estimated scope:** Medium (3-5 files)

## Task 5: Integrate preflight into `fire` without changing pearl activation

**Description:** Run preflight before fake-player creation. A validated request
then enters the existing spawn/look/use flow. `fireRaw` remains a developer
command: it shares structural target and cleanup checks but cannot apply
per-player pearl ownership validation.

**Acceptance criteria:**

- [ ] A rejected named `fire` never creates or teleports a fake player.
- [ ] A valid named `fire` still activates only the saved target block via
      `spawn -> lookAt -> use once`.
- [ ] The command never selects, moves, or directly interacts with a pearl.

**Verification:**

- [ ] A note-block GameTest proves one right-click changes `note=0` to `note=1`.
- [ ] Tests assert fake-player count remains unchanged for every preflight
      rejection.
- [ ] Regression smoke tests cover `test`, `fireRaw`, `save`, `list`, `fire`,
      and `remove`.

**Dependencies:** Task 4

**Files likely touched:**

- `src/main/java/com/foxseventeen/pearlrelay/command/PearlRelayCommand.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayPreflightService.java`
- `src/gametest/java/...`

**Estimated scope:** Small/Medium (2-4 files)

## Task 6: Replace pending queues with a fail-safe execution lifecycle

**Description:** Introduce an execution manager that owns all delayed aim, use,
timeout, completion, and cleanup behavior. Cleanup must be idempotent and run
after success, exceptions, spawn timeout, player disconnect, command races, and
server shutdown where possible.

**Acceptance criteria:**

- [ ] At most one active execution exists for a generated bot name.
- [ ] Every accepted execution reaches exactly one terminal result.
- [ ] Every terminal path attempts fake-player cleanup; repeated cleanup is safe.

**Verification:**

- [ ] Deterministic tick tests cover delayed fake spawn, successful use, spawn
      timeout, target removal after preflight, internal exception, duplicate
      fire, and cleanup of an already-absent bot.
- [ ] After each scenario, active execution count and matching fake-player count
      are zero.
- [ ] A server stop/restart smoke test leaves no orphan fake player.

**Dependencies:** Task 5

**Files likely touched:**

- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayExecution.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayExecutionManager.java`
- `src/main/java/com/foxseventeen/pearlrelay/command/PearlRelayCommand.java`
- `src/main/java/com/foxseventeen/pearlrelay/PearlRelayMod.java`
- `src/gametest/java/...`

**Estimated scope:** Medium (4-5 files)

## Checkpoint B: Safe end-to-end execution

- [ ] All confirmed preflight failures reject before fake-player creation.
- [ ] Valid note-block interaction works end to end.
- [ ] Every failure-injection test proves cleanup.
- [ ] Multiple owned pearls pass without any selection logic.
- [ ] No new chunk ticket is observed.
- [ ] Review execution states and timeout values before logging/release work.

## Task 7: Add structured lifecycle logging and player feedback

**Description:** Emit one accepted/rejected event and exactly one terminal event
per execution. Give the invoking player a concise immediate rejection or queued
message and an asynchronous terminal completion/failure message when possible.

**Acceptance criteria:**

- [ ] Logs include execution ID, relay, player UUID, dimension, target chunk,
      pearl count, phase, result, failure code, and duration where applicable.
- [ ] Rejection messages state the exact correctable cause.
- [ ] No credential, password, entire config file, or unrelated player data is
      logged.

**Verification:**

- [ ] Log-capture tests assert fields and one terminal event per execution.
- [ ] Error-message tests cover every stable failure code.
- [ ] Manual log review confirms successful and rejected examples are searchable
      with `grep 'event=relay_fire'`.

**Dependencies:** Task 6

**Files likely touched:**

- `src/main/java/com/foxseventeen/pearlrelay/PearlRelayMod.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayExecutionManager.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayFailure.java`
- `src/test/java/...`

**Estimated scope:** Medium (3-4 files)

## Task 8: Validate the candidate on the isolated remote server

**Description:** Build the candidate, verify artifact metadata/checksum, deploy
only to `/opt/minecraft-pearlrelay-test`, and execute the full headless matrix.
Do not start or modify the production world.

**Acceptance criteria:**

- [ ] Candidate JAR metadata reports the expected RC version and dependencies.
- [ ] Test server starts on `127.0.0.1:25566` with no new crash report.
- [ ] Headless named-relay tests exercise save, list, fire, persistence, remove,
      all preflight rejections, multiple pearls, and cleanup.

**Verification:**

- [ ] Record local/remote SHA-256 and archive the previous test JAR.
- [ ] Run the automated smoke script through the `pearlrelay-test` screen.
- [ ] Restart the test server and rerun persistence and orphan checks.
- [ ] Inspect startup and execution logs for unexpected WARN/ERROR entries.

**Dependencies:** Tasks 1-7

**Files likely touched:**

- `scripts/` or `src/gametest/` smoke-test support
- `tasks/test-results/v1.1.0-rc.1.md`
- No production-server files

**Estimated scope:** Small/Medium (1-3 repository files plus isolated deployment)

## Task 9: Prepare documentation and the complete player acceptance handoff

**Description:** Update user documentation, create a curated changelog, document
legacy relay resaving, and prepare the exact player test report before asking
the owner to join the game.

**Acceptance criteria:**

- [ ] The handoff lists every player behavior below with prerequisites, exact
      commands, actions, expected results, and failure criteria.
- [ ] README explains preflight behavior and stable errors.
- [ ] CHANGELOG separates Added, Changed, Fixed, and Known Limitations.

**Required player behaviors to test:**

1. Save, list, fire, and remove a valid named relay.
2. Trigger a real pearl stasis device and confirm the invoking player teleports.
3. Confirm the fake player appears at the saved spawn, looks at the correct
   target, uses once, and leaves.
4. Change only the target block state and confirm the relay remains valid.
5. Replace the target block with another type and confirm rejection before fake
   player creation.
6. Remove the player's pearl while keeping the chunk loaded another way and
   confirm `OWNED_PEARL_NOT_FOUND`.
7. Place only another player's pearl in the chunk and confirm rejection.
8. Place multiple invoking-player pearls in the target chunk and confirm the
   relay is allowed without selecting a pearl.
9. Make the spawn or target chunk unavailable and confirm no automatic loading.
10. Block the fake-player spawn or line of sight and confirm exact rejection.
11. Trigger the same relay twice quickly and confirm no duplicate active bot.
12. Force a post-spawn failure and confirm the bot is still removed.
13. Restart the server and confirm v2 relay configuration persists.
14. Load a legacy v1 relay and confirm it remains listed but requests a resave.
15. Use two real player accounts and confirm relay-list/config isolation.
16. Check that player messages and server logs match the actual outcome.

**Verification:**

- [ ] Review `docs/testing/v1.1.0-player-acceptance.md` line by line against the
      implemented failure codes.
- [ ] Do not report the RC as ready for player testing unless this document and
      the headless result report are both complete.
- [ ] Verify all commands use the final RC syntax.

**Dependencies:** Task 8

**Files likely touched:**

- `README.md`
- `CHANGELOG.md`
- `docs/testing/v1.1.0-player-acceptance.md`
- `tasks/test-results/v1.1.0-rc.1.md`

**Estimated scope:** Medium (4 files)

## Checkpoint C: RC readiness

- [ ] Clean Java 25 build passes.
- [ ] Unit, GameTest, and isolated-server suites pass.
- [ ] Candidate artifact checksum and metadata are recorded.
- [ ] No production files or ports were modified.
- [ ] Changelog and full player acceptance handoff are complete.
- [ ] Code review finds no high-severity issue.

## Task 10: Record and publish `v1.1.0-rc.1`

**Description:** Preserve a reviewable Git and GitHub history for the candidate.
Do not publish a tag from uncommitted or unverified sources.

**Branch and commit strategy:**

- Branch: `codex/v1.1-safe-trigger`
- Keep `main` deployable.
- Use atomic commits such as:
  1. `chore: make the Java 25 build portable`
  2. `test: add relay configuration and GameTest harness`
  3. `feat: persist relay target fingerprints`
  4. `feat: reject unsafe named relay triggers`
  5. `refactor: make relay execution cleanup fail-safe`
  6. `feat: add structured relay lifecycle logs`
  7. `docs: add v1.1 RC migration and player test guide`

**Version history strategy:**

- [ ] Verify commit `306e9bf` is the source baseline corresponding to deployed
      `1.0.0` before creating any retroactive `v1.0.0` tag.
- [ ] If provenance cannot be verified, document the deployed JAR SHA-256 and do
      not create a misleading historical tag.
- [ ] Ensure the JAR metadata, changelog, and tag all identify
      `1.1.0-rc.1`.
- [ ] Create annotated tag `v1.1.0-rc.1`.
- [ ] Push the branch and tag.
- [ ] Create a GitHub prerelease with changelog, known limitations, test report,
      JAR, and SHA-256.

**Acceptance criteria:**

- [ ] Tag points to the exact reviewed and remotely tested commit.
- [ ] Release artifact hash matches the isolated-server candidate.
- [ ] GitHub marks the release as a prerelease, not stable.

**Verification:**

- [ ] `git status --short` is clean.
- [ ] `git show v1.1.0-rc.1` displays the expected commit and annotation.
- [ ] Download the GitHub asset and verify its SHA-256.

**Dependencies:** Checkpoint C and owner approval to publish

**Files likely touched:** No additional source files; Git/GitHub metadata only

**Estimated scope:** Small

## Task 11: Complete player acceptance and publish `v1.1.0`

**Description:** The owner executes the documented real-player matrix. Fixes
produce `rc.2`, `rc.3`, and so on. The final tag is cut only from the exact
candidate that passed all mandatory checks.

**Acceptance criteria:**

- [ ] Every mandatory player behavior is marked pass with evidence or an
      approved exception.
- [ ] Actual pearl stasis teleport succeeds.
- [ ] No orphan fake player, unintended chunk load, or unexplained failure
      remains.

**Verification:**

- [ ] Archive the signed-off player acceptance report.
- [ ] Rerun all automated tests on the final commit.
- [ ] Build and checksum the final artifact.
- [ ] Create annotated tag `v1.1.0` and a non-prerelease GitHub Release.
- [ ] Verify the published artifact hash and `fabric.mod.json` version.

**Dependencies:** Task 10 and completed real-player acceptance

**Files likely touched:**

- `CHANGELOG.md`
- `docs/testing/v1.1.0-player-acceptance.md`
- Release metadata

**Estimated scope:** Small/Medium

## Definition of Done

v1.1.0 is done only when:

- [ ] Invalid named triggers perform no fake-player or chunk-loading side effect.
- [ ] Valid named triggers dispatch exactly one fake-player use action.
- [ ] Every accepted execution has one terminal result and no orphan bot.
- [ ] Target fingerprint and owned-pearl rules match the confirmed intent.
- [ ] Unit, GameTest, remote headless, restart, and real-player tests pass.
- [ ] The player handoff enumerates all concrete behaviors before testing begins.
- [ ] Documentation and migration guidance are current.
- [ ] The final JAR, SHA-256, changelog, commit, annotated tag, and GitHub Release
      all identify `v1.1.0`.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| A chunk API accidentally loads the chunk being inspected | High | Use non-loading lookup and assert ticket/loaded-state invariance |
| Pearl owner API or class names differ in Minecraft 26.2 mappings | Medium | Resolve against the pinned 26.2 source/Javadocs in Task 1 and cover with GameTests |
| A target-chunk-only pearl search misses a device spanning a chunk border | Medium | Document target-chunk scope; do not silently widen or make radius configurable in v1.1 |
| Legacy relays lack a trustworthy target fingerprint | Medium | Preserve data, refuse fire with resave guidance, never guess the historical block type |
| Carpet fake-player creation completes asynchronously | High | State machine, deadlines, idempotent cleanup, delayed-spawn tests |
| A use action is dispatched but the physical device does not activate | Medium | Report only dispatch/cleanup as server-proven; require real-device acceptance |
| Fabric API differs between build (`0.152.2`) and current server (`0.152.1`) | Medium | Record both versions and test the RC against the intended deployed dependency set |
| Current `1.0.0` has no repository tag | Medium | Verify provenance before retroactive tagging; otherwise document without inventing history |
| No local Java runtime is available | Medium | Make the build portable and use the isolated server's existing Java 25 initially |

## Open Questions

No product questions remain. Any Minecraft 26.2 API uncertainty is an
implementation research item in Task 1 and may not change the confirmed
behavior without owner approval.

## Explicit Non-Goals for v1.1

- Permission levels or permission-provider integration
- Per-relay or per-player cooldowns
- GUI/config screens
- Automatic or temporary chunk loading
- Configurable pearl search radius
- Pearl selection, movement, or direct activation
- Proving arbitrary redstone outcomes without a device-specific signal
- Production deployment before RC and player acceptance approval
