# 实施方案：Pearl Relay 自动化玩家测试替代

## 状态

- 提案日期：2026-07-28
- 目标环境：Minecraft 26.2、Java 25、Fabric Loader 0.19.3、
  Fabric API 0.152.2+26.2、Carpet 26.2
- 审批状态：项目所有者已于 2026-07-28 批准
- 实施状态：阶段 1–3 / Checkpoint A–C 已完成；下一步为阶段 4 的人工
  验收边界重写

## 概述

在现有 JUnit 和服务端 GameTest 之上增加真实客户端端到端测试层。新测试层
将启动真正的 Minecraft 客户端、驱动客户端输入、构造确定性的末影珍珠
滞留装置、通过客户端连接执行 Pearl Relay 指令，并断言执行者被传送到
预期位置。

目标是让日常开发、拉取请求和候选版本回归不再强制要求真人玩家参与。
稳定版发布前可以保留一次简短的探索性检查，但不得再重复自动化已经覆盖的
16 组行为。

## 成功标准

1. 全新检出的项目可以在本地以及使用 XVFB 的 Linux CI 中，无人值守地
   运行 Minecraft 26.2 客户端测试。
2. 真实客户端输入能够投掷一颗属于当前连接玩家的末影珍珠，并使其进入
   确定性的滞留装置。
3. 客户端发送 `save` 和 `fire` 后，Pearl Relay 只激活装置一次，连接
   玩家到达预期目的地。
4. 假玩家出生、朝向/使用、消失、玩家消息、日志和截图均作为测试证据保存。
5. 生产 JAR 在生产映射后的客户端类路径上通过测试，同时发布 JAR 仍不
   包含任何测试代码或夹具。

## 范围

### MVP 范围内

- Minecraft 26.2 的 Fabric Client GameTest
- 一个真实 Minecraft 客户端，连接集成服务器或进程内专用服务器
- 使用真实客户端鼠标输入投掷末影珍珠
- 通过客户端/服务端连接执行指令
- 确定性的世界和滞留装置搭建
- 服务端状态和客户端状态断言
- 将截图和日志作为 CI 产物
- 生产类路径客户端测试
- 根据自动化证据更新玩家验收门槛

### MVP 明确不做

- 通用 AI Minecraft 玩家
- 仅依靠计算机视觉决定通过或失败
- 支持 Forge 或 NeoForge
- 为其他 mod 提供公共测试框架 API
- 同时运行多个原生客户端
- 在 CI 中使用微软账号凭据
- 部署到生产服务器
- 声称自动化能证明主观手感或任意整合包兼容性

## 现有基线

仓库已经具备：

- 47 个 Fabric Loader JUnit 测试
- 21 个服务端 Fabric GameTest
- 真实 Minecraft 服务端世界断言
- 使用两个 UUID 验证指令、配置、列表和补全隔离
- Carpet 假玩家出生、单次使用和清理覆盖
- 区块就绪状态和票据副作用覆盖
- 结构化 `accepted`、`rejected`、`terminal` 日志断言
- Java 25 GitHub Actions 构建和产物验证

方案提出时的 Gradle 配置清楚地显示缺失的测试层：

```groovy
enableGameTests = true
enableClientGameTests = false
```

当前候选版本报告指出三个残留类别：

1. 真实滞留装置最终传送
2. 两个真实账号
3. 玩家可见体验

本方案解决第 1 和第 3 类。第 2 类的服务端合同已经由两个 UUID 覆盖；
是否增加两个同时运行的原生客户端，留到后续独立决策。

## 架构决策

### 1. 扩展 Fabric 官方测试栈

使用 Fabric Client GameTest，不另造一套机器人协议。它已经提供确定性的
客户端/服务端 tick、客户端输入、进程内世界/服务器创建、截图，以及与
XVFB 兼容的 CI 执行能力。

客户端测试入口实现：

```java
FabricClientGameTest
```

并且只存在于现有的 `gametest` source set 中。

### 2. 状态断言作为门槛，截图作为证据

测试门槛主要断言：

- 珍珠实体存在，且所有者为当前连接玩家
- 触发前珍珠保持在预期装置区域内
- 中继指令只被接受一次
- 恰好派发一次假玩家交互
- 玩家从起始区域移动到目的地区域
- 最终不存在假玩家或活跃执行
- `accepted` 和 `terminal` 日志使用同一个执行 ID

测试会在关键阶段保存截图，但初始版本不使用整屏像素完全一致作为通过条件。
整屏基线容易受 GPU、字体、分辨率和与 Pearl Relay 无关的渲染变化影响。

### 3. 构建领域专用夹具，不设计通用场景语言

创建一个小型测试辅助类 `PearlStasisFixture`，在超平坦世界中构建一套
已知可用的装置，并暴露以下命名位置：

- 玩家投掷位置
- 珍珠滞留区域
- 中继假玩家出生点
- 激活目标
- 预期传送目的地

夹具保持可读时优先通过代码搭建。如果稳定夹具增长到大约 40 个以上方块，
只把方块布局迁移到结构资源，编排和断言仍保留在 Java 中。

### 4. 必须经过真实客户端边界

世界搭建可以使用服务端测试上下文，但承担证明责任的动作必须经过客户端：

- 使用客户端输入 API 执行末影珍珠右键
- 从客户端连接发送中继指令
- 从客户端观察玩家位置和收到的消息

测试不得直接调用 `RelayPreflight`、`RelayExecutionManager` 或
`PearlRelayCommand`。

### 5. 测试支持不得进入生产包

不得仅为方便测试而增加测试专用生产指令或生产行为分支。测试类、夹具、
截图和辅助代码全部留在 `gametest` source set 中，不得进入发布 JAR。

### 6. 增加独立的生产客户端门槛

开发类路径下的客户端测试稳定后，增加 Fabric 的生产客户端 GameTest
任务。该任务验证映射后的生产产物，而不仅是开发环境中的类。

HeadlessMC/MC-Runtime-Test 是可选的独立启动器冒烟层，不是 MVP 依赖。

### Checkpoint B 后的实现说明

阶段 1 使用集成服务器验证了客户端输入边界。客户端运行日志符合
`pearlrelay` 的 `"environment": "server"` 元数据：客户端进程只加载测试
mod，不初始化 Pearl Relay 生产入口。阶段 2 的原版装置对照继续使用集成
服务器；Task 4 则通过 `TestWorldBuilder.createServer()` 在同一测试 JVM
中启动专用服务器，并由真实客户端连接。

Fabric Client GameTest 的专用服务器不会重新运行 Fabric Loader，也不会
自动补跑被客户端环境过滤掉的服务端入口。因此测试 source set 在专用
服务器启动前调用一次生产 `PearlRelayMod` 初始化器，注册的仍是生产指令和
生产 tick 回调。这个桥接只存在于 `gametest` source set，发布 JAR 不包含
测试入口、夹具或桥接代码。

## 依赖关系

```text
客户端 GameTest 基线
        |
        +--> 真实客户端输入可行性验证
                    |
                    +--> 确定性滞留装置夹具
                                |
                                +--> 真实传送端到端测试
                                            |
                                            +--> 玩家可见生命周期证据
                                                        |
                                                        +--> 生产客户端 CI
                                                                    |
                                                                    +--> 重写验收门槛
                                                                                |
                                                                                +--> 可选双客户端决策
```

## Task 1：搭建客户端 GameTest 基线

**说明：** 开启客户端测试运行器，证明 Minecraft 26.2 能在当前项目中
创建并关闭受控世界，且不改变生产行为。

**验收标准：**

- [x] `runClientGameTest` 能启动 26.2 客户端、创建超平坦世界并在无用户
      输入的情况下成功退出。
- [x] 测试 mod 同时注册服务端和客户端 GameTest 入口，专用服务器不会
      加载客户端类。
- [x] 发布 JAR 不包含客户端测试入口或任何测试类。

**验证：**

- [x] 执行 `./gradlew --no-daemon runClientGameTest`。
- [x] 执行 `./gradlew --no-daemon clean test build`。
- [x] 使用 `unzip -l` 检查非 sources 发布 JAR。

**依赖：** 无

**预计涉及文件：**

- `build.gradle`
- `src/gametest/resources/fabric.mod.json`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayClientGameTests.java`

**规模估计：** S（3 个文件）

## Task 2：验证真实输入和珍珠所有权

**说明：** 优先验证风险最高的假设。给连接客户端一颗末影珍珠，设置确定性
位置和视角，模拟使用键，并证明服务端收到的珍珠属于该玩家。

**验收标准：**

- [x] 测试使用 Fabric `TestInput` 执行使用动作，而不是直接构造
      `ThrownEnderpearl`。
- [x] 服务端恰好观察到一颗新珍珠，且所有者 UUID 等于连接玩家 UUID。
- [x] 测试在本地至少连续执行 20 次仍保持确定性。

**验证：**

- [x] 开启截图后执行一次聚焦客户端测试。
- [x] 在干净测试世界中连续执行聚焦测试 20 次。
- [x] 失败时保留客户端日志、服务端日志和截图。

**依赖：** Task 1

**预计涉及文件：**

- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayClientGameTests.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/ClientTestAssertions.java`

**规模估计：** S（1–2 个文件）

## Checkpoint A：客户端边界可行

- [x] Java 25 干净构建和全部现有测试仍然通过。
- [x] 一次真实客户端鼠标动作产生一颗归属正确的服务端珍珠。
- [x] 连续 20 次执行没有时间或所有权抖动。
- [x] 如果本检查点不稳定，停止并重新评估；不得在不稳定的输入边界上继续
      构建完整装置。

## Task 3：构建确定性的滞留装置夹具

**说明：** 增加一套测试专用的最小末影珍珠滞留装置，并证明原版机制能够
保持客户端投掷的珍珠，直到激活方块被使用。

**验收标准：**

- [x] 夹具暴露投掷、出生、目标、滞留区域和预期目的地的命名坐标。
- [x] 客户端投掷的珍珠在规定稳定窗口内保持在滞留区域，且玩家不会提前
      传送。
- [x] 直接激活一次夹具后，珍珠释放，玩家进入目的地容差包围盒。

**验证：**

- [x] 在不使用 Pearl Relay 的情况下执行夹具对照测试。
- [x] 连续重复滞留/释放对照场景 20 次。
- [x] 失败时记录释放前后玩家和珍珠坐标。

**依赖：** Checkpoint A

**预计涉及文件：**

- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlStasisFixture.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayClientGameTests.java`
- 仅在必要时使用
  `src/gametest/resources/data/pearlrelay-gametest/structure/`

**规模估计：** S/M（2–3 个文件）

## Task 4：证明 Pearl Relay 完成真实传送

**说明：** 使用公共 Pearl Relay 流程替换对夹具的直接激活。通过
`TestWorldBuilder.createServer()` 启动进程内专用服务器，使用测试 source
set 的一次性桥接注册生产初始化器，再由连接客户端保存中继、投掷珍珠、
触发中继，并由释放的珍珠完成传送。

**验收标准：**

- [x] `save` 和 `fire` 经过客户端/服务端连接，并返回预期玩家消息。
- [x] 执行者的真实珍珠被释放，客户端进入预期目的地容差包围盒。
- [x] 执行包含一条 `accepted`、一条 `completed terminal`、一次假玩家
      使用，最终没有遗留假玩家或活跃执行。

**验证：**

- [x] 连续执行聚焦端到端测试 20 次。
- [x] 每次终态后比较客户端和服务端玩家位置。
- [x] 断言事件数量及执行 ID 的关联关系。

**依赖：** Task 3

**预计涉及文件：**

- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayClientGameTests.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/ClientCommandDriver.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/ClientTestAssertions.java`

**规模估计：** M（2–3 个文件）

## Checkpoint B：替代真实装置人工验收

- [x] 现有 JUnit 和服务端 GameTest 通过。
- [x] 夹具对照测试证明原版滞留/释放行为。
- [x] Pearl Relay 使真实连接玩家完成传送。
- [x] 连续 20 次执行没有时间抖动或孤儿假玩家。
- [x] 原人工验收中的真实装置传送项目已有可重复的自动化证据。

## Task 5：捕获玩家可见生命周期

**说明：** 为原先需要观察者确认的部分增加证据：假玩家在保存位置出现、
面向激活目标、只使用一次、退出，同时执行客户端收到一致反馈。

**验收标准：**

- [x] 服务端断言证明假玩家名称、位置、视线方向、一次使用和最终移除。
- [x] 客户端断言证明收到同一执行的排队消息和终态消息。
- [x] 保存触发前、可观察到假玩家时以及传送后的截图；若瞬态截图没有捕获
      假玩家，不得用截图替代状态断言。

**验证：**

- [x] 使用固定分辨率、语言、GUI 缩放、FOV 和渲染距离执行测试。
- [x] 无论测试通过还是失败，都上传截图和两端日志。
- [x] 确认截图动作不会引入改变结果的额外游戏 tick。

**依赖：** Task 4

**预计涉及文件：**

- `src/gametest/java/com/foxseventeen/pearlrelay/test/PearlRelayClientGameTests.java`
- `src/gametest/java/com/foxseventeen/pearlrelay/test/ClientTestAssertions.java`
- 只有在后续证明必要时，才添加
  `src/gametest/resources/assets/pearlrelay-gametest/` 中的局部截图模板

**规模估计：** S/M（2–3 个文件）

## Task 6：增加生产客户端 CI

**说明：** 在 GitHub Actions 中通过 XVFB 运行客户端套件，并增加生产映射
后的客户端任务，使构建产物本身而非仅开发环境类通过发布门槛。

**验收标准：**

- [x] CI 使用 Java 25 运行客户端套件，任一必需客户端断言失败时任务失败。
- [x] 生产客户端 GameTest 加载预期发布 JAR 和 Fabric API 版本。
- [x] 每次运行上传测试结果、客户端/服务端日志、崩溃报告和截图。

**验证：**

- [x] 一次绿色工作流证明正常路径。
- [x] 临时破坏断言证明客户端任务会阻断工作流；破坏内容不得进入目标分支。
- [x] 产物检查证明被测试 JAR 的 SHA-256 与上传的发布产物一致。

**依赖：** Task 5

**预计涉及文件：**

- `build.gradle`
- `.github/workflows/verify.yml`
- `tasks/test-results/client-e2e-baseline.md`

**规模估计：** M（3 个文件）

## Checkpoint C：发布级客户端门槛

- [x] 干净构建、JUnit、服务端 GameTest、客户端 GameTest 和生产客户端
      GameTest 全部通过。
- [x] CI 失败实验证明新任务是真实门槛。
- [x] 所有必需证据均可下载，并关联到同一个提交/JAR 哈希。
- [x] 工作流不包含微软账号或生产服务器凭据。

## Task 7：重写验收边界

**说明：** 使用自动化证据替换重复的人工回归步骤，同时保留简短、诚实的
探索性发布检查。

**验收标准：**

- [ ] 原 16 项玩家测试分别映射到 JUnit、服务端 GameTest、客户端
      GameTest、隔离服务端或明确命名的残留人工检查。
- [ ] Checkpoint C 通过后，真实传送和玩家可见生命周期不再标记为未自动化。
- [ ] 残留人工检查只覆盖主观体验和指定整合包/环境兼容性。

**验证：**

- [ ] 对照当前所有失败码审查覆盖矩阵。
- [ ] 确认所有自动化证据都指向准确的候选版本哈希。
- [ ] 修订后的门槛获批前不得发布稳定版 `v1.1.0`。

**依赖：** Checkpoint C

**预计涉及文件：**

- `docs/testing/v1.1.0-player-acceptance.md`
- `tasks/test-results/v1.1.0-rc.2.md` 或下一个 RC 报告
- `tasks/todo.md`

**规模估计：** M（3 个文件）

## 决策门：是否需要同时运行两个原生客户端

Task 7 完成后，再评估两个同时运行的原生客户端，相比现有双 UUID 服务端
测试是否能提供足够的额外证据。

仅在至少满足以下一项时继续：

- 发现依赖真实登录/连接生命周期的缺陷
- Pearl Relay 增加客户端配置或网络功能
- 出现支持 26.2 的成熟协议机器人
- 发布政策明确要求两个原生客户端进程

如果均不满足，则保留双 UUID 服务端测试作为隔离证据，避免增加无必要的
编排复杂度。

## 可选 Task 8：验证两个原生客户端

**说明：** 启动两个隔离的原生客户端连接同一个离线模式测试服，通过
测试专用控制器编排最小的保存、列表和触发隔离场景。

**验收标准：**

- [ ] 两个客户端使用不同 UUID 和独立游戏目录。
- [ ] 每个客户端只能看到自己的中继，不能触发对方中继。
- [ ] 不需要或保存任何认证秘密。

**验证：**

- [ ] 在 Linux CI 中连续执行场景 10 次。
- [ ] 分别归档两个客户端和服务端日志。
- [ ] 通过、失败或超时时都能可靠停止两个客户端和服务端。

**依赖：** 决策门明确批准

**预计涉及范围：**

- 独立测试控制器 source set 或测试专用 mod
- 独立的多客户端 CI 工作流
- 测试编排脚本/配置

**规模估计：** L，需要在实施前另行拆分为更小任务并审批。

## 可选 Task 9：增加独立启动器冒烟测试

**说明：** 使用 HeadlessMC/MC-Runtime-Test 独立安装 Fabric、放入构建的
mod、进入世界并正常退出。该测试用于验证 Loom 客户端运行器之外的启动和
打包行为。

**验收标准：**

- [ ] Minecraft 26.2 使用构建的发布 JAR 和锁定依赖成功启动。
- [ ] 测试进入世界、观察到 Pearl Relay 已加载并正常退出。
- [ ] 在明确运行时间和抖动率前，该任务只在夜间或发布流程执行。

**验证：**

- [ ] 针对一个已知良好的候选版本执行冒烟任务。
- [ ] 故意不放入 Pearl Relay，证明“预期 mod 已加载”断言会失败。
- [ ] 对比加载的 JAR 哈希与发布产物。

**依赖：** Checkpoint C，且需要独立批准

**预计涉及文件：**

- `.github/workflows/runtime-smoke.yml`
- 测试/运行时配置

**规模估计：** S/M（1–3 个文件）

## 风险登记

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| Fabric Client GameTest API 属于实验性 API，可能随版本变化 | 中 | 锁定 Fabric API；保持辅助层轻薄；所有 API 调用限制在测试代码 |
| 真实输入或珍珠物理产生抖动 | 高 | 优先做输入可行性验证；固定 tick、位置、视角和世界；要求连续 20 次通过 |
| 滞留装置依赖随机或环境敏感机制 | 高 | 关闭天气、刷怪和昼夜变量；固定种子；先独立证明夹具行为 |
| 客户端/服务端 tick 同步掩盖竞态 | 高 | 使用 Client GameTest 的 tick 等待和状态谓词，禁止使用墙钟 sleep |
| CI 渲染器导致截图差异 | 中 | 初期截图只作证据；门槛依赖语义状态；稳定后才增加局部模板 |
| 假玩家存在时间太短，客户端来不及渲染 | 低/中 | 以服务端生命周期断言为准；不得为了截图放慢生产行为 |
| 生产测试意外把测试代码打入 JAR | 高 | Task 1 和 Task 6 均检查发布 JAR；隔离测试 source set |
| 客户端任务拖慢每个 PR | 中 | 测量运行时间；缓存依赖；拆分快速服务端任务和客户端任务；只在有证据时使用路径过滤 |
| 双原生客户端带来不成比例的复杂度 | 中 | 放在明确的决策门之后 |

## 工作量估计

假设 Fabric 26.2 客户端运行器没有底层缺陷：

- Task 1–2 和 Checkpoint A：1 个专注开发环节
- Task 3–4 和 Checkpoint B：2–3 个专注开发环节
- Task 5–6 和 Checkpoint C：1–2 个专注开发环节
- Task 7：1 个专注开发环节

MVP 预计需要 5–7 个专注开发环节。最大的未知项是确定性滞留装置夹具。
估计不包含可选的双客户端工作。

## 完成定义

- [ ] Pearl Relay 日常回归不再需要真人玩家。
- [ ] 负责证明结果的末影珍珠由真实 Minecraft 客户端投掷。
- [ ] Pearl Relay 使真实连接玩家完成传送。
- [ ] 假玩家生命周期、玩家消息、日志和清理均自动验证。
- [ ] 客户端测试进入 CI，并验证生产映射后的产物。
- [ ] 现有测试继续通过，发布 JAR 仍不包含测试内容。
- [ ] 验收文档准确区分自动化事实与残留主观检查。
- [ ] 项目所有者批准修订后的发布门槛。

## 参考资料

- Fabric 自动化测试：
  <https://docs.fabricmc.net/develop/automatic-testing>
- Fabric API 26.2 Javadocs：
  <https://maven.fabricmc.net/docs/fabric-api-0.152.0%2B26.2/index-all.html>
- MC-Runtime-Test：
  <https://github.com/headlesshq/mc-runtime-test>
- HeadlessMC：
  <https://github.com/headlesshq/headlessmc>
- Mineflayer：
  <https://github.com/PrismarineJS/mineflayer>
