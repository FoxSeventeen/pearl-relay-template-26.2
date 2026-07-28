# Pearl Relay v1.1 Safe Trigger Checklist

## Approval

- [x] Project owner reviews and approves `tasks/plan.md`

## Phase 1: Build and contract foundation

- [x] Task 1: Make the Java 25 build portable
- [x] Task 1: Add unit and Fabric GameTest harnesses
- [x] Task 2: Add typed preflight failure codes
- [x] Task 2: Add schema version and target fingerprint
- [x] Task 2: Preserve legacy configuration with resave guidance
- [x] Task 3: Resolve and validate the target during `save`
- [ ] Checkpoint A: Build, config, and no-chunk-load checks pass

## Phase 2: Safe trigger

- [x] Task 4: Implement side-effect-free named-relay preflight
- [x] Task 4: Require at least one invoking-player pearl in target chunk
- [x] Task 4: Allow multiple owned pearls without selecting one
- [x] Task 4: Reject changed block type but allow state changes
- [x] Task 5: Run preflight before fake-player creation
- [x] Task 5: Preserve `spawn -> lookAt -> use once` activation
- [x] Task 6: Add execution lifecycle, deadlines, and idempotent cleanup
- [x] Task 6: Reject duplicate active execution for the same bot
- [ ] Checkpoint B: End-to-end and failure-injection tests pass

## Phase 3: Observability and remote validation

- [ ] Task 7: Add structured accepted/rejected/terminal logs
- [ ] Task 7: Add exact player-facing failures
- [ ] Task 8: Build and checksum the candidate JAR
- [ ] Task 8: Back up the previous isolated-test JAR
- [ ] Task 8: Deploy only to `/opt/minecraft-pearlrelay-test`
- [ ] Task 8: Run headless command, persistence, failure, and cleanup matrix
- [ ] Task 8: Restart the isolated server and rerun orphan checks

## Phase 4: Documentation and RC

- [ ] Task 9: Update README
- [ ] Task 9: Create curated CHANGELOG
- [ ] Task 9: Document legacy relay resaving
- [ ] Task 9: Write every concrete player test behavior with exact steps
- [ ] Task 9: Produce the headless RC test report
- [ ] Checkpoint C: Code review and RC readiness pass
- [ ] Task 10: Verify whether a truthful `v1.0.0` baseline tag can be created
- [ ] Task 10: Merge reviewed work from `codex/v1.1-safe-trigger`
- [ ] Task 10: Create and push annotated `v1.1.0-rc.1`
- [ ] Task 10: Publish GitHub prerelease, JAR, SHA-256, and reports

## Phase 5: Player acceptance and final release

- [ ] Valid save/list/fire/remove
- [ ] Real pearl stasis teleport
- [ ] Correct fake-player spawn, aim, single use, and cleanup
- [ ] Same block type with changed state remains valid
- [ ] Replaced target block is rejected before bot creation
- [ ] Loaded chunk without invoking-player pearl is rejected
- [ ] Other-player pearl does not satisfy readiness
- [ ] Multiple invoking-player pearls are allowed
- [ ] Unloaded chunk is rejected without automatic loading
- [ ] Blocked spawn or ray is rejected
- [ ] Duplicate fire does not create duplicate active bots
- [ ] Post-spawn failure still removes the bot
- [ ] Configuration persists across restart
- [ ] Legacy relay requests resave without data loss
- [ ] Two real players cannot see or trigger each other's relays
- [ ] Player messages and structured logs match outcomes
- [ ] Fix failures through additional RC tags as needed
- [ ] Rerun all automated tests on the accepted commit
- [ ] Create and push annotated `v1.1.0`
- [ ] Publish final GitHub Release and verify artifact SHA-256
