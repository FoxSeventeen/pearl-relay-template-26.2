# Pearl Relay 无玩家阶段硬化计划

## 状态

- 提案日期：2026-07-28
- 状态：执行中（Task 1–4 已获项目所有者确认）
- 基线：`v1.1.0-rc.1`
- 后续开发分支：`headless-hardening`（在 RC 基线固定后创建）
- 当前候选 JAR SHA-256：
  `499ffe2023d692c1e4f2f0e47621879b4c63f3752d2292609cbf559d67c1d8a2`

## 目标

在暂时不能进行玩家实机验证的前提下，继续提高 Pearl Relay 的可重复
验证能力、配置可靠性和故障可诊断性，但不扩大 `fire` 的玩家行为合同，
也不把无客户端结果误报为真实珍珠装置验收。

本阶段完成后，项目应能在每次提交和每个候选包上自动回答：

1. 命令合同和全部预检错误码是否仍然正确；
2. 拒绝是否完全无假人、无交互、无新增区块加载副作用；
3. 接受执行是否只有一次使用、一个终态并最终清理；
4. 配置在异常写入或损坏时是否安全失败且可恢复；
5. 最终产物、依赖、测试证据和远端部署是否能够一一对应。

## 边界

- 冻结 `/pearlrelay fire <name>` 的成功语义和现有错误码。
- `v1.1-safe-trigger` 保持为 RC 证据分支；后续工作使用新的
  `headless-hardening` 分支。
- 不增加 GUI、权限、冷却、自动区块加载或珍珠直接控制。
- 暂不增加 `/pearlrelay check` 等新的玩家指令；这些功能留到 `v1.2`
  设计，以免扩大 `v1.1` 实机验收矩阵。
- 不发布稳定版 `v1.1.0`。
- 生产服务器继续保持停止且不部署候选包。
- 所有远端测试只使用 `/opt/minecraft-pearlrelay-test`。
- 任一行为修正都必须升级为新的 RC，重新运行完整自动化矩阵。

## 依赖关系

```text
冻结 rc.1 基线
      |
      +--> CI 质量门
      |       |
      |       +--> 命令级 GameTest 矩阵
      |                   |
      +--> 配置原子写入 ----+
                          |
                          +--> 压力/故障注入
                                      |
                                      +--> rc.1 保留或生成 rc.2
                                                  |
                                                  +--> 玩家实机验收
```

## Task 1：冻结并记录 RC 基线

**说明：** 保证后续硬化工作不会悄悄改变已经交付测试服的候选包。只有在
项目所有者批准后，才为当前候选创建 annotated tag 和 GitHub prerelease。

**验收标准：**

- [x] 当前提交、JAR SHA-256、Fabric 元数据和远端 JAR 完全对应并冻结为
      `rc.1`；后续生产代码修复使用 `rc.2`。
- [x] `v1.1.0-rc.1` prerelease 明确标注“尚未完成玩家实机验收”。
- [x] 从冻结基线创建 `headless-hardening`，不在 RC 证据分支上
      继续开发。

**验证：**

- [x] `git status --short` 为空（计划文件提交后复核）。
- [x] `git show v1.1.0-rc.1` 指向被验证的候选提交。
- [x] 下载 prerelease JAR 后重新计算 SHA-256。

**依赖：** 项目所有者批准发布 RC

**预计范围：** S；仅 Git/GitHub 发布元数据

## Task 2：建立 GitHub Actions 自动质量门

**说明：** 把本地 Java 25 构建、JUnit、Fabric GameTest、JAR 元数据和
校验和检查变成每次 push/PR 自动执行的质量门，不包含任何服务器凭据或
远端自动部署。

**验收标准：**

- [x] CI 使用 Java 25 和 Gradle Wrapper 执行 `clean test build`。
- [x] JUnit 或任一必需 GameTest 失败时工作流失败。
- [x] CI 上传非 sources JAR、测试报告和 SHA-256 清单。

**验证：**

- [x] 正常分支 CI 全绿且产物版本为 `1.1.0-rc.1` 或当前 RC。
- [x] 临时破坏一个断言可证明 CI 会失败，恢复后重新通过。
- [x] 仓库和工作流日志中不存在密码、服务器 IP 凭据或私有配置。

**证据：**

- 正常运行：<https://github.com/FoxSeventeen/pearl-relay-template-26.2/actions/runs/30322852891>
- 失败断言证明：<https://github.com/FoxSeventeen/pearl-relay-template-26.2/actions/runs/30323090685>
- 失败证明分支在验证后已从本地和远端删除；错误断言从未进入
  `headless-hardening`。

**依赖：** Task 1 的候选标识规则

**可能涉及文件：**

- `.github/workflows/verify.yml`
- `build.gradle`
- `README.md`

**预计范围：** M；2–3 个文件

## Checkpoint A：可重复构建

- [x] 全新环境可以只依赖仓库和公开依赖复现构建。
- [x] 43 个以上 JUnit 与 15 个以上 GameTest 自动通过。
- [x] CI 产物 SHA-256、版本和测试报告可下载核对。
- [x] CI 不接触生产服或隔离测试服。

## Task 3：增加命令级 GameTest 集成层

**说明：** 现有测试分别覆盖核心服务和部分真实 Carpet 生命周期；下一步
直接以两个不同 UUID 的模拟服务端玩家执行完整命令树，并捕获玩家消息、
返回值、日志和实体状态。

**验收标准：**

- [ ] 自动覆盖 `test/save/list/fire/remove` 的有效流程和玩家隔离。
- [ ] 自动覆盖旧版配置重存、0/他人/1/多颗珍珠以及重复触发。
- [ ] 每个拒绝断言错误码、假人数、交互次数和日志事件数量。

**验证：**

- [ ] GameTest 使用真实命令分发器，不直接绕过命令层调用预检。
- [ ] 接受执行恰好一条 `accepted`、一条同 ID `terminal`。
- [ ] 预检拒绝恰好一条 `rejected`，没有对应假人或 `terminal`。

**依赖：** Task 2

**可能涉及文件：**

- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayCommandGameTests.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayGameTests.java`
- `src/test/java/com/foxseventeen/pearlrelay/relay/RelayEventReporterTest.java`
- `build.gradle`

**预计范围：** M；3–4 个文件

## Task 4：证明拒绝路径没有加载副作用

**说明：** 将“无自动区块加载”从实现推理提升为自动断言。记录命令前后的
区块实体 tick 状态、相关票据、假人和珍珠实体，并覆盖目标卸载与合成的
出生区块卸载边界。

**验收标准：**

- [ ] 所有卸载失败都在访问方块状态或扫描实体前返回。
- [ ] 拒绝前后相关区块 readiness、票据和实体数量保持一致。
- [ ] `getChunkNow` 可见但不可实体 tick 的邻区块仍然拒绝。

**验证：**

- [ ] 单元测试覆盖所有区块边界和负坐标。
- [ ] GameTest 或受控服务端测试证明目标卸载不会新增票据。
- [ ] 失败报告明确区分真实服务端证据与合成 WorldView 证据。

**依赖：** Task 3

**可能涉及文件：**

- `src/test/java/com/foxseventeen/pearlrelay/relay/RelayTargetResolverTest.java`
- `src/test/java/com/foxseventeen/pearlrelay/relay/RelayPreflightTest.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayCommandGameTests.java`
- `tasks/test-results/`

**预计范围：** M；3–4 个文件

## Checkpoint B：命令合同与无副作用

- [ ] 命令级有效流程和全部错误合同通过。
- [ ] 每个拒绝均为 0 个新假人、0 次使用和 0 个新增区块票据。
- [ ] 每个接受执行只有一次使用和一个终态。
- [ ] 两个 UUID 的配置、补全和触发保持隔离。

## Task 5：配置原子写入与损坏恢复

**说明：** 当前配置直接覆盖 JSON。改为同目录临时文件写入、刷新、原子
替换，并保留一个最近成功版本；损坏 JSON 必须返回稳定配置错误，不得删除
原文件或继续生成假人。

**验收标准：**

- [ ] 正常保存使用原子替换，旧文件不会出现半写入状态。
- [ ] 解析失败保留原文件并给出不泄露文件内容的稳定错误。
- [ ] 备份恢复不会跨 UUID、覆盖其他玩家或静默丢失其他中继。

**验证：**

- [ ] 单元测试注入写入失败、截断 JSON、无效字段和恢复失败。
- [ ] 每次失败后原配置或最近成功备份仍可读取。
- [ ] 失败路径不创建假人，不打印完整配置内容。

**依赖：** Checkpoint A；可与 Task 3 前半段独立进行

**可能涉及文件：**

- `src/main/java/com/foxseventeen/pearlrelay/config/RelayConfigStore.java`
- `src/main/java/com/foxseventeen/pearlrelay/command/PearlRelayCommand.java`
- `src/test/java/com/foxseventeen/pearlrelay/config/RelayConfigStoreTest.java`
- `README.md`

**预计范围：** M；3–4 个文件

## Task 6：生命周期压力和故障注入

**说明：** 对执行状态机进行重复触发、随机阶段异常、清理重试和停服测试，
证明长时间运行不会累积 active execution、假人或重复终态。

**验收标准：**

- [ ] 至少 1,000 次确定性模拟执行后 active count 为 0。
- [ ] spawn、aim、validate、use、cleanup 任一阶段抛错都只有一个终态。
- [ ] shutdown 与 cleanup 竞态不会重复使用或重复上报。

**验证：**

- [ ] 固定随机种子的属性/参数化测试可稳定复现失败。
- [ ] 每轮断言 `useCalls <= 1`、`terminalCount == 1`、`activeCount == 0`。
- [ ] 隔离服执行重复启停烟测，最终 0 玩家且无新崩溃报告。

**依赖：** Tasks 3–5

**可能涉及文件：**

- `src/test/java/com/foxseventeen/pearlrelay/relay/RelayExecutionManagerTest.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayCommandGameTests.java`
- `tasks/test-results/`

**预计范围：** M；2–3 个文件

## Checkpoint C：下一 RC 决策

- [ ] Java 25 clean build、全部 JUnit/GameTest 和隔离服重启通过。
- [ ] 代码审查没有高严重度问题。
- [ ] 若仅新增测试/CI 且候选字节未变，保留 `rc.1`。
- [ ] 若生产代码或玩家可见消息改变，升级 `1.1.0-rc.2`，重新构建、
      部署、校验和并运行全部无客户端矩阵。
- [ ] 更新玩家实机清单，但仍不把稳定版标记为通过。

## 玩家验证恢复后的顺序

1. 冻结当时最新 RC 的源码提交与 JAR。
2. 重新运行 CI、命令级 GameTest、压力测试和隔离服重启。
3. 按现有 16 组清单完成真实玩家测试。
4. 任一失败先修复并发布下一 RC，不跳过复测。
5. 全部通过后创建 annotated `v1.1.0` tag 和正式 GitHub Release。

## 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 自动化测试越来越多但仍不能证明真实传送 | 高 | 保留稳定版实机门槛，报告明确证据边界 |
| CI GameTest 偶发计时抖动 | 中 | 使用状态条件和宽松总超时，不依赖固定墙钟时间 |
| 配置恢复逻辑本身造成数据覆盖 | 高 | 同目录原子替换、每 UUID 隔离、故障注入测试 |
| 为测试加入生产后门或调试命令 | 高 | 使用测试 source set 和注入接口，不向发布 JAR 暴露 |
| 在 rc.1 上继续改代码导致产物身份混乱 | 高 | 任一生产代码变更升级 rc.2，记录提交和 SHA-256 |

## 暂缓到 v1.2 的功能

- `/pearlrelay check <name>` 只读预检
- 最近执行历史与 `/pearlrelay status`
- 管理员诊断导出
- 权限、冷却或批量触发

这些功能都很方便，但会增加玩家可见合同。当前先把 v1.1 的可靠性和发布
证据做扎实，再单独设计和验收。
