# 客户端 E2E 阶段 3 结果

## 结论

2026-07-28，Task 5、Task 6 和 Checkpoint C 通过。客户端测试能证明假
玩家的可见生命周期，生产客户端任务从发布 JAR 加载主代码，并且首次
GitHub Actions 生产客户端工作流已在提交
`d749d64e998f593e4e9f3f08bfe5880e3e694ce1` 上绿色完成。

## 环境

- macOS aarch64
- Eclipse Temurin `25.0.3+9-LTS`
- Gradle `9.5.1`
- Fabric Loom `1.17.0-alpha.7`
- Minecraft `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.152.2+26.2`
- Carpet `26.2+v260616`

## 玩家可见生命周期断言

`FakePlayerLifecycleProbe` 在每轮执行中同时检查服务端和客户端：

- 假玩家名称严格等于保存响应中的确定性 bot 名；
- 服务端实体类型是 Carpet `EntityPlayerMPFake`；
- 出生位置与保存位置一致；
- 眼睛朝向目标的归一化点积至少为 `0.999`；
- 活板门只有一次“开到关”状态转换；
- 终态后客户端和服务端均不再存在该假玩家；
- 服务端玩家列表只剩真实连接玩家。

客户端消息断言覆盖 `Saved relay`、`Relay queued`、
`Relay '<name>' completed` 和 `Removed relay`。结构化日志仍要求每轮恰好
一条 `accepted` 和一条同 `execution_id` 的 `completed terminal`。

## 固定视觉证据

客户端固定为：

- `1280×720`
- GUI 缩放 `2`
- FOV `70`
- 渲染距离和模拟距离 `5`
- 语言 `en_us`

第一次执行生成 5 张 `1280×720` PNG，其中 Task 5 的三张必需证据是：

1. `pearlrelay-relay-before-trigger.png`
2. `pearlrelay-relay-fake-player-visible.png`
3. `pearlrelay-relay-after-teleport.png`

可见阶段截图实际包含假玩家及名称。Fabric 截图调用在当前 API 实现中同步
完成，不推进 GameTest tick；测试还检查返回路径已经写成普通文件。

## 生产客户端任务

`runProductionClientGameTest` 使用 Loom 的 `ClientProductionRunTask`：

- 独立的 gametest JAR 提供测试入口；
- 普通发布 JAR 提供生产类；
- 运行时断言 `PearlRelayMod` 的 `CodeSource` 是普通发布 JAR；
- 生产依赖显式使用 Fabric API `0.152.2+26.2` 和 Carpet `26.2`；
- 每次删除并重建隔离运行目录，写入测试 EULA；
- CI 下开启 XVFB，并关闭 Fabric 文档提到的网络同步器；
- 窗口参数固定为 `1280×720`。

实现过程中捕获了两个真实的生产边界问题：

1. 服务端环境 mod 在生产客户端发现阶段被过滤，主类必须来自发布 JAR
   类路径；
2. 全新隔离目录没有 EULA 时，进程内专用服务器不会启动。

两项修复均保留在测试/构建边界，没有修改生产 mod 的环境声明。

## 正常路径结果

### 开发态客户端

```shell
./gradlew --no-daemon runClientGameTest
```

- 默认 20 轮；
- 20 次真实输入和珍珠所有权；
- 20 次原版滞留/释放对照；
- 20 次 Pearl Relay 完整传送和玩家可见生命周期；
- `BUILD SUCCESSFUL`，耗时 6 分 2 秒。

### 生产发布 JAR 客户端

```shell
./gradlew --no-daemon runProductionClientGameTest
```

- 默认 20 轮；
- `BUILD SUCCESSFUL`，耗时 6 分 9 秒；
- 日志包含 20 条 `Relay queued` 和 20 条 `completed`；
- 假玩家加入和离开各有 40 条可见日志，即服务端和客户端各 20 条；
- 启动日志确认 `fabric-api 0.152.2+26.2`；
- 运行前后发布 JAR SHA-256 一致：
  `7fe3ee64fbc11cdab5af2abb1487e01c63fbaaaebbabe855a2b1889af457909b`。

## 故障门禁实验

临时把“一次激活”的通过条件改成要求两次，然后用 1 轮生产客户端任务
执行。测试在真实观察值为 `1` 时抛出 `AssertionError`，
`runProductionClientGameTest` 以退出码 `1` 结束，Gradle 报
`BUILD FAILED`（1 分 34 秒）。

随后立即恢复为要求一次激活；恢复后的聚焦生产任务和正式 20 轮任务均
通过。故意破坏内容不在当前工作树中。

## 全量回归和发布隔离

```shell
./gradlew --no-daemon clean test build
```

- 最终源码状态下 `BUILD SUCCESSFUL`，耗时 1 分 37 秒；
- JUnit：47/47 通过；
- Fabric 服务端 GameTest：21/21 通过；
- `git diff --check` 无错误；
- `pearlrelay-1.1.0-rc.2.jar` 不包含测试包、gametest 元数据或夹具；
- 发布 JAR SHA-256：
  `7fe3ee64fbc11cdab5af2abb1487e01c63fbaaaebbabe855a2b1889af457909b`。

## CI 证据设计

`.github/workflows/verify.yml` 新增依赖普通构建任务的
`client-e2e` 作业。它使用 Java 25，确保 `xvfb-run` 可用，构建发布 JAR
和测试支持 JAR，运行生产客户端任务，再验证：

- 发布 JAR 没有测试代码；
- 测试前后 SHA-256 一致；
- 运行日志包含项目指定的 Fabric API 版本；
- 上传的候选 JAR、`COMMIT_SHA` 和 `SHA256SUMS` 相互关联；
- 无论成功或失败都上传日志、截图、崩溃报告和身份文件。

工作流没有微软账号、Minecraft 账号或生产服务器凭据。Fabric 的客户端
GameTest、生产运行任务和 XVFB 用法依据官方文档：

- https://docs.fabricmc.net/develop/automatic-testing
- https://docs.fabricmc.net/develop/loom/production-run-tasks

## GitHub Actions 实际结果

工作流：
https://github.com/FoxSeventeen/pearl-relay-template-26.2/actions/runs/30335238993

- `Java 25 build and tests`：绿色，耗时 1 分 30 秒；
- `Java 25 production client GameTest`：绿色，耗时 5 分 47 秒；
- 生产客户端测试自身耗时 4 分 40 秒；
- JAR 来源、Fabric API 版本、测试代码隔离和测试后哈希校验全部通过；
- 候选产物和证据产物均可下载，保留 14 天；
- 证据产物包含 `latest.log`、5 张 `1280×720` 截图、`COMMIT_SHA`、
  `RELEASE_JAR` 和 `SHA256SUMS`。

下载后复核：

- 两份 `COMMIT_SHA` 都是
  `d749d64e998f593e4e9f3f08bfe5880e3e694ce1`；
- 普通构建发布 JAR、生产客户端候选 JAR、候选校验文件和证据校验文件
  的 SHA-256 均为
  `dd308aa436285e670b88319290c47814da03577298ae2b0dd108806536d595e2`；
- CI 中被测试、校验和上传的是同一个 JAR 文件。

本地 macOS 构建的 JAR 哈希不同，是 Gradle JAR 中构建时间元数据造成的
跨环境差异；Checkpoint C 关联的是同一 CI 作业内实际被测试并上传的候选
文件，不依赖跨机器可重现哈希。

Checkpoint C 已关闭。下一步是阶段 4：把原 16 项玩家验收逐项映射到自动化
证据，只保留主观体验和指定整合环境兼容性的探索性检查。
