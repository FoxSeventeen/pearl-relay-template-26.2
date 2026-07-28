# Pearl Relay v1.2.0 开发计划：玩家快照保存

## 状态

- 计划状态：草案，等待项目所有者整体审批
- 功能意图：项目所有者已于 2026-07-28 确认
- 版本目标：`1.2.0`
- 规划分支：`v1.2-planning`
- 实现分支：审批后从 `main` 创建 `v1.2-player-snapshot-save`
- 基线：正式版 `v1.1.0`

## 概述

v1.2.0 的首要目标是让玩家本人充当一次性的“假人位置与姿态模板”。
玩家站在未来假人应出现的位置、看向点火方块，然后执行：

```mcfunction
/pearlrelay save <名称>
```

模组直接采集当前玩家的维度、脚部精确坐标和视线命中点，经过现有
`RelayTargetResolver` 验证后，保存为当前 schema v2 的
`dimension + spawn + lookAt + target`。触发时仍完整执行 v1.1 的安全预检，
不会因为保存更方便而放宽区块、碰撞、目标、珍珠所有权或清理规则。

现有完整参数形式继续保留：

```mcfunction
/pearlrelay save <名称> <维度> <假人出生坐标> <看向坐标>
```

本版本同时完成 v1.1 遗留的配置原子写入、损坏恢复和生命周期压力验证，
确保新增的高频保存入口不会扩大配置损坏风险。

## 已确认意图

1. `/pearlrelay save <名称>` 是普通玩家的推荐保存方式。
2. 保存位置是执行玩家当前脚部的精确 `x/y/z`，不取整到方块中心。
3. 保存维度是执行玩家当前所在维度。
4. 保存方向来自玩家当前视线实际命中的方块与命中位置。
5. 模组保存有效瞄准结果，不额外持久化裸 `yaw/pitch`。
6. 保存时玩家本人可以站在未来假人的出生位置。
7. 触发时该位置若仍被玩家占用，继续按 v1.1 规则拒绝。
8. 控制台和命令方块不能使用简写，必须获得稳定、明确的玩家限定错误。
9. 原完整参数命令继续工作，现有配置和调用方不需要迁移。
10. 简写保存只改变数据采集方式，不改变 `fire` 的执行和安全合同。

## 范围

### v1.2.0 MVP 范围内

- 玩家快照形式的 `/pearlrelay save <名称>`
- 旧完整参数形式向后兼容
- 当前维度、精确位置、视线命中点和目标指纹采集
- 保存时复用现有无区块加载的目标解析
- 配置原子写入、最近成功备份和安全恢复
- 命令级、真实客户端和生产 JAR 客户端自动化
- 1,000 次生命周期压力与阶段故障注入
- 隔离服务端 RC 验证、版本记录和发布材料

### 明确不在本次范围

- `/pearlrelay check <名称>`
- `/pearlrelay status` 或最近执行历史
- 管理员诊断导出
- 权限节点、冷却、批量触发
- GUI、客户端模组或自定义网络协议
- 自动加载区块
- 自动选择、移动或直接释放珍珠
- 精确保存玩家姿势、潜行状态或动画
- 删除或隐藏完整参数保存命令

## 架构决策

### 1. 在现有命令树上增加重载，不创建第二套保存命令

`save` 的 `name` 参数节点直接执行玩家快照保存，现有
`dimension -> spawn -> lookAt` 子树保持不变：

```text
pearlrelay save <name>                         -> 玩家快照
pearlrelay save <name> <dimension> <spawn> ... -> 高级参数
```

这样最短路径最方便，同时不破坏脚本、管理员工具或旧文档中的完整写法。

### 2. 保存视线命中点，而不是增加 yaw/pitch 字段

玩家当前视线先在原版生存交互距离内取得精确方块命中点，再把该点作为
`lookAt` 交给现有 `RelayTargetResolver`。解析器会从未来假人的标准站立
眼高重新验证射线、区块状态、出生空间和目标方块。

因此：

- 玩家所见方向确实参与采集；
- 假人的实际几何仍是最终真值；
- 潜行或特殊姿势造成的眼高差异会在保存时被安全拒绝，而不是留下无法触发
  的配置；
- schema v2 已能表达结果，不需要 schema v3。

### 3. 保存与触发继续共享目标解析和失败合同

简写保存不得自己实现一套射线或区块判断。它只负责收集输入，随后调用已有
解析器。目标、路径或出生区块未达到实体 tick 状态时直接拒绝，不申请票据，
不生成区块。

### 4. 保存时忽略玩家本人占位，触发时不忽略

保存阶段只验证方块碰撞和假人站立几何；执行玩家本人出现在采集点是预期
行为。`fire` 阶段仍运行玩家占位预检，任何玩家尚未离开出生空间都返回
`SPAWN_POSITION_BLOCKED`，且不创建假人。

### 5. 新保存入口之前先硬化配置写入

当前配置使用 `Files.newBufferedWriter` 直接覆盖主文件。v1.2 先实现同目录
临时文件、刷新、原子替换和最近成功备份，再接入玩家快照保存，避免便利
入口增加写入频率后放大半写风险。

### 6. 损坏恢复采用保守、可追溯策略

推荐行为：

1. 主文件解析失败时保留损坏内容，不继续 `save/list/fire/remove`。
2. 若最近成功备份完整有效，先保存损坏副本，再原子恢复主文件。
3. 当前命令返回稳定的“已恢复，请重试”错误，不在恢复同一调用中触发假人。
4. 没有有效备份时只返回稳定损坏错误，不覆盖或删除任何文件。

该策略需要在 Task 2 开始前由项目所有者确认；默认不得静默丢弃无效中继。

## 依赖关系

```text
v1.1.0 正式基线
        |
        v
Task 1 配置原子写入
        |
        v
Task 2 损坏检测与恢复
        |
        +------------------+
        |                  |
        v                  v
Task 3 玩家快照命令    Task 6 生命周期压力
        |
        v
Task 4 命令级 GameTest
        |
        v
Task 5 真实客户端 E2E
        |
        +---------> Checkpoint B
                         |
                         v
              Task 7 RC 与隔离服验证
                         |
                         v
                 Task 8 正式发布
```

## Task 1：配置原子写入

**说明：** 把每个 UUID 配置从直接覆盖改为同目录临时写入、刷新、原子替换，
并只在新内容完整写入后更新最近成功备份。

**验收标准：**

- [ ] 主文件替换前临时文件已完整写入并刷新，支持时使用
      `ATOMIC_MOVE + REPLACE_EXISTING`。
- [ ] 写入、刷新或替换任一步失败时，旧主文件和其他 UUID 文件保持不变。
- [ ] 成功保存后只保留一个最近成功备份，不把半写临时文件当作备份。

**验证：**

- [ ] 单元测试注入临时写入、刷新、备份和原子替换失败。
- [ ] 每个失败点后重新加载仍得到最近一次成功配置。
- [ ] `git diff --check`、JUnit 和 `./gradlew --no-daemon clean test build`
      通过。

**依赖：** v1.1.0 基线

**可能涉及文件：**

- `src/main/java/com/foxseventeen/pearlrelay/config/RelayConfigStore.java`
- `src/test/java/com/foxseventeen/pearlrelay/config/RelayConfigStoreTest.java`

**预计范围：** S；2 个文件

## Task 2：损坏配置检测与安全恢复

**说明：** 对 JSON 解析、schema 和每个中继字段进行整体校验；不再静默
过滤无效条目。实现带稳定错误码、损坏副本和有效备份验证的恢复流程。

**验收标准：**

- [ ] 截断 JSON、非法字段或无效中继使整个玩家配置安全失败，不静默删条目。
- [ ] 只有同一 UUID 的有效备份可恢复；损坏主文件被保留且当前命令不继续。
- [ ] 玩家消息和日志只包含错误码、UUID/路径标识等必要元数据，不泄露配置
      正文。

**验证：**

- [ ] 覆盖无备份、有效备份、损坏备份、恢复写入失败和两个 UUID 隔离。
- [ ] 配置失败的 `fire` 保持 0 假人、0 使用、0 新增区块票据。
- [ ] 恢复后下一次命令可读取最近成功配置，且原损坏副本仍存在。

**依赖：** Task 1

**可能涉及文件：**

- `src/main/java/com/foxseventeen/pearlrelay/config/RelayConfigStore.java`
- `src/main/java/com/foxseventeen/pearlrelay/config/RelayConfigManager.java`
- `src/main/java/com/foxseventeen/pearlrelay/config/RelayConfigException.java`
- `src/test/java/com/foxseventeen/pearlrelay/config/RelayConfigStoreTest.java`

**预计范围：** M；4 个文件

## Checkpoint A：配置写入边界

- [ ] 正常、失败和恢复路径都不产生半写主文件。
- [ ] 玩家 UUID、主文件、备份和损坏副本严格隔离。
- [ ] 旧 schema v1 和当前 schema v2 的迁移合同保持不变。
- [ ] 完整 JUnit、服务端 GameTest 和 clean build 通过。
- [ ] 项目所有者确认恢复策略后再进入玩家快照实现。

## Task 3：实现玩家快照保存命令

**说明：** 给 `save <name>` 增加执行点，从 `ServerPlayer` 收集当前维度、
脚部精确位置和视线命中点，复用现有解析器保存 schema v2 中继。

**验收标准：**

- [ ] 玩家站立并看向有效目标时，简写保存的 dimension、spawn、lookAt 和
      target 与实际采集值一致。
- [ ] 无方块命中、超出交互距离、路径/出生区块未加载或假人几何不可达时，
      返回既有稳定失败，不写配置。
- [ ] 控制台/命令方块获得玩家限定错误；完整参数保存结果保持兼容。

**验证：**

- [ ] 单元测试覆盖精确小数坐标、正负区块边界和视线命中点。
- [ ] 失败前后配置文件、区块票据和世界实体数量不变。
- [ ] 发布 JAR 的 schema 仍为 v2，未增加客户端入口或生产依赖。

**依赖：** Checkpoint A

**可能涉及文件：**

- `src/main/java/com/foxseventeen/pearlrelay/command/PearlRelayCommand.java`
- `src/main/java/com/foxseventeen/pearlrelay/relay/RelayTargetResolver.java`
- `src/test/java/com/foxseventeen/pearlrelay/relay/RelayTargetResolverTest.java`

**预计范围：** M；3 个文件

## Task 4：增加完整命令级 GameTest

**说明：** 使用真实 Brigadier 命令树和测试 `ServerPlayer` 验证玩家快照
保存、重新保存、完整参数兼容以及保存后触发的安全边界。

**验收标准：**

- [ ] `save <name>` 精确保存测试玩家的维度、脚部位置、命中点和目标指纹。
- [ ] 保存时允许创建者站在采集位置；其未离开便 `fire` 时在生成前拒绝。
- [ ] 玩家移开并准备本人珍珠后，快照保存的中继仍只使用一次并完整清理。

**验证：**

- [ ] 覆盖控制台拒绝、同名覆盖、双 UUID 隔离和旧完整参数形式。
- [ ] 覆盖无命中、目标改变、未加载路径和出生点被其他玩家占用。
- [ ] 每条拒绝断言 0 假人、0 使用、0 新增票据和唯一错误消息。

**依赖：** Task 3

**可能涉及文件：**

- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayCommandGameTests.java`
- `src/test/java/com/foxseventeen/pearlrelay/config/RelayConfigStoreTest.java`

**预计范围：** M；2 个文件

## Task 5：通过真实客户端验证最短玩家流程

**说明：** 把现有客户端端到端场景的保存步骤改为玩家实际站位、转向并发送
`save <name>`，证明不依赖直接调用服务端方法或手工坐标。

**验收标准：**

- [ ] 真实连接客户端站到夹具位置、看向目标并用简写保存成功。
- [ ] 客户端移开后执行 `fire`，假人从保存位置使用一次，珍珠释放并传送。
- [ ] 客户端可见消息、假人出现/退出、execution ID 和服务端终态一致。

**验证：**

- [ ] 开发客户端三个场景各 20/20。
- [ ] 生产发布 JAR 客户端三个场景各 20/20，`CodeSource` 仍来自发布 JAR。
- [ ] 保存快照、假人可见和传送后截图及日志作为 CI 证据上传。

**依赖：** Task 4

**可能涉及文件：**

- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayClientGameTests.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/ClientCommandDriver.java`

**预计范围：** M；2 个文件

## Checkpoint B：玩家快照保存可发布

- [ ] `save <name>` 是真实玩家可完成的最短保存路径。
- [ ] 完整参数形式和 v1.1 配置继续兼容。
- [ ] 所有保存拒绝路径没有配置、实体、交互或区块加载副作用。
- [ ] 快照保存后的真实珍珠装置传送连续通过。
- [ ] 代码审查没有高严重度问题。

## Task 6：生命周期压力与阶段故障注入

**说明：** 完成 v1.1 遗留的状态机压力任务，证明新增保存入口不会掩盖长期
执行中的 active execution、重复使用或重复终态问题。

**验收标准：**

- [ ] 固定随机种子至少执行 1,000 次后 `activeCount == 0`。
- [ ] spawn、aim、validate、use、cleanup 任一阶段抛错仍只有一个终态。
- [ ] shutdown 与 cleanup 竞态不产生重复使用、重复上报或孤儿假人。

**验证：**

- [ ] 每轮断言 `useCalls <= 1`、`terminalCount == 1`。
- [ ] 失败种子和阶段名称进入断言消息，可稳定复现。
- [ ] 隔离服重复启停后为 0 在线玩家、0 孤儿假人、0 新崩溃报告。

**依赖：** Task 2；可与 Tasks 3–5 的实现阶段独立进行

**可能涉及文件：**

- `src/test/java/com/foxseventeen/pearlrelay/relay/RelayExecutionManagerTest.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayCommandGameTests.java`
- `tasks/test-results/`

**预计范围：** M；2 个代码文件和结果报告

## Task 7：准备 v1.2.0-rc.1 并验证隔离服务器

**说明：** 完成版本、说明和发布材料，把候选 JAR 只部署到隔离测试服，
验证配置恢复和重复启停；生产服保持停止且继续使用 v1.1.0。

**验收标准：**

- [ ] README、CHANGELOG、命令帮助、迁移说明和测试报告描述同一行为合同。
- [ ] `v1.2.0-rc.1` JAR、提交、tag、SHA256SUMS 和 CI 证据一一对应。
- [ ] 隔离服验证完成后停止，生产服 JAR、世界、配置和 screen 不变。

**验证：**

- [ ] `clean test build`、开发客户端和生产 JAR 客户端门禁全部通过。
- [ ] 隔离服覆盖正常保存、损坏恢复、无备份失败、重启和孤儿检查。
- [ ] 下载 GitHub prerelease 资产并复核 SHA-256 与 `fabric.mod.json`。

**依赖：** Checkpoint B、Task 6

**可能涉及文件：**

- `gradle.properties`
- `README.md`
- `CHANGELOG.md`
- `tasks/test-results/v1.2.0-rc.1.md`

**预计范围：** M；4 个文件及发布操作

## Task 8：批准并发布 v1.2.0

**说明：** 在项目所有者批准 RC 证据后，冻结最终提交，创建正式 tag 与
Release；只有用户明确授权时才部署生产服务器。

**验收标准：**

- [ ] 最终候选提交的普通构建和生产 JAR 客户端 GitHub Actions 均绿色。
- [ ] annotated `v1.2.0` tag、正式 Release、JAR 和 SHA-256 完全一致。
- [ ] 生产部署前保留 v1.1.0 回滚副本，部署后按用户要求保持启动或停止。

**验证：**

- [ ] Release 为非 draft、非 prerelease，并包含 JAR、SHA256SUMS 和报告。
- [ ] GitHub asset digest、下载文件、部署文件和报告哈希一致。
- [ ] 最终 Git 状态、远端 tag、服务器状态和回滚路径形成发布记录。

**依赖：** Task 7、项目所有者批准

**可能涉及文件：**

- `gradle.properties`
- `CHANGELOG.md`
- `tasks/test-results/v1.2.0.md`
- `tasks/todo-v1.2.md`

**预计范围：** S；发布状态与记录

## 增量提交建议

实现阶段保持每个增量可独立回滚：

1. `fix: make relay config writes atomic`
2. `fix: recover damaged relay configs safely`
3. `feat: capture player snapshot when saving a relay`
4. `test: cover snapshot save command behavior`
5. `test: validate snapshot save through a real client`
6. `test: stress relay execution lifecycle`
7. `docs: prepare v1.2.0-rc.1`

不把配置重构、玩家功能、测试和发布材料混入同一个提交。

## 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 玩家潜行/游泳眼高与假人站立眼高不同 | 中 | 保存命中点后用未来假人标准几何再次解析；不匹配即拒绝 |
| 保存时玩家本人被当作出生占位 | 高 | 保存只检查方块碰撞；触发继续检查所有玩家占位 |
| 简写与完整参数命令解析冲突 | 中 | 在同一个 `name` 节点执行简写，保留更深参数子树并做命令级测试 |
| 原子替换在某些文件系统不可用 | 中 | 明确检测能力；采用安全回退且保证旧主文件不先删除 |
| 自动恢复覆盖仍有价值的损坏文件 | 高 | 先保留损坏副本，只使用通过完整验证的同 UUID 备份，当前命令不继续 |
| 新客户端测试引入计时抖动 | 中 | 等待状态条件而非固定 sleep，保留宽松总超时和 20 轮门槛 |
| 便利功能意外放宽 v1.1 安全规则 | 高 | 复用同一解析器和预检；拒绝路径断言实体、交互、票据均不变 |

## 开放问题

1. **已批准。** 配置损坏且存在有效同 UUID 备份时，保留损坏副本并自动
   恢复主文件；当前命令以 `CONFIG_RECOVERED_RETRY` 停止，玩家重试后才
   继续业务动作。

## 完成定义

- [ ] 玩家无需输入维度或坐标即可可靠保存中继。
- [ ] 快照保存准确复现当前玩家的有效位置和面向目标。
- [ ] 完整参数命令与 v1.1 配置保持兼容。
- [ ] 配置写入和恢复不静默丢失数据，不跨 UUID。
- [ ] 所有失败路径不创建假人、不使用方块、不新增区块票据。
- [ ] 1,000 次生命周期压力验证没有活动执行或孤儿假人。
- [ ] JUnit、服务端 GameTest、客户端 GameTest、生产 JAR GameTest 和隔离服
      证据全部通过。
- [ ] 文档、版本、提交、tag、JAR、SHA256SUMS 和 Release 一致。
- [ ] 项目所有者审查并批准发布证据。
