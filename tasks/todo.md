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
- [x] Checkpoint A: Build, config, and no-chunk-load checks pass

## Phase 2: Safe trigger

- [x] Task 4: Implement side-effect-free named-relay preflight
- [x] Task 4: Require at least one invoking-player pearl in target chunk
- [x] Task 4: Allow multiple owned pearls without selecting one
- [x] Task 4: Reject changed block type but allow state changes
- [x] Task 5: Run preflight before fake-player creation
- [x] Task 5: Preserve `spawn -> lookAt -> use once` activation
- [x] Task 6: Add execution lifecycle, deadlines, and idempotent cleanup
- [x] Task 6: Reject duplicate active execution for the same bot
- [x] Checkpoint B: End-to-end and failure-injection tests pass

## Phase 3: Observability and remote validation

- [x] Task 7: Add structured accepted/rejected/terminal logs
- [x] Task 7: Add exact player-facing failures
- [x] Task 8: Build and checksum the candidate JAR
- [x] Task 8: Back up the previous isolated-test JAR
- [x] Task 8: Deploy only to `/opt/minecraft-pearlrelay-test`
- [x] Task 8: Run headless command, persistence, failure, and cleanup matrix
- [x] Task 8: Restart the isolated server and rerun orphan checks

## Phase 4: Documentation and RC

- [x] Task 9: Update README
- [x] Task 9: Create curated CHANGELOG
- [x] Task 9: Document legacy relay resaving
- [x] Task 9: Write every concrete player test behavior with exact steps
- [x] Task 9: Produce the headless RC test report
- [x] Checkpoint C: Code review and RC readiness pass
- [x] Task 10: Resolve `v1.0.0` tag provenance (unverified; do not create)
- [ ] Task 10: Merge reviewed work from `v1.1-safe-trigger`
- [x] Task 10: Create and push annotated `v1.1.0-rc.1`
- [x] Task 10: Publish GitHub prerelease, JAR, SHA-256, and reports

## Phase 4.1: Headless hardening (`v1.1.0-rc.2`)

- [x] Task 1: Freeze RC1 and create `headless-hardening`
- [x] Task 2: Add a Java 25 GitHub Actions quality gate
- [x] Task 2: Prove the quality gate rejects a deliberately failing assertion
- [x] Task 3: Add full-dispatcher command GameTests with two UUIDs
- [x] Task 3: Assert accepted/rejected/terminal log cardinality and cleanup
- [x] Task 4: Reject unloaded spawn/path chunks before world or entity reads
- [x] Task 4: Assert ticket, readiness, fake-player, and interaction stability
- [x] Task 4: Cover positive and negative chunk boundaries
- [x] Deploy and restart RC2 only in `/opt/minecraft-pearlrelay-test`
- [x] Leave production and isolated test ports stopped after validation
- [x] Create and push annotated `v1.1.0-rc.2`
- [x] Publish RC2 GitHub prerelease, JAR, SHA-256, and report

## 阶段 4.2：自动化玩家测试替代（完成）

- [x] 审查并批准
      [`tasks/plan-client-e2e.md`](plan-client-e2e.md)
- [x] 执行
      [`tasks/todo-client-e2e.md`](todo-client-e2e.md)
- [x] 完成客户端边界可行性（Checkpoint A）
- [x] 完成真实滞留装置传送（Checkpoint B）
- [x] 完成玩家可见生命周期和生产 JAR 客户端本地门槛
- [x] 在同一提交上取得首个绿色客户端 E2E 工作流及可下载证据
- [x] 使用客户端 E2E 证据替代真实装置和玩家可见体验的人工回归
- [x] 已决定当前不需要同时运行多个原生客户端

## Phase 5：稳定版审批与发布

- [x] 原 16 项玩家回归全部映射到自动化或隔离服务端证据
- [x] 删除重复的强制真人回归，只保留条件式探索检查
- [x] 双原生客户端当前不提供新的服务端隔离证据，不实施
- [x] 项目所有者批准修订后的发布门槛
- [ ] 在最终候选提交上重跑全部自动化并核对证据产物
- [x] 本次不声明指定整合环境/主观体验承诺，无需探索检查
- [ ] 修复阻断问题并按需创建新的 RC
- [ ] 创建并推送带注释的 `v1.1.0`
- [ ] 发布最终 GitHub Release 并核对产物 SHA-256
