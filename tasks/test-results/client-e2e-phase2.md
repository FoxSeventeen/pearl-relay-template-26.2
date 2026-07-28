# 客户端 E2E 阶段 2 结果

## 结论

2026-07-28，Checkpoint B 通过。测试用原版灵魂沙气泡柱和活板门构造了
确定性的末影珍珠滞留装置，并分别完成：

1. 不经过 Pearl Relay 的原版滞留/直接释放对照 20 次；
2. 真实客户端连接专用服务器，通过 `save`、`fire`、`remove` 公共指令
   完成真实珍珠释放和玩家传送 20 次。

正式门槛总耗时 5 分 34 秒，`BUILD SUCCESSFUL`。

## 环境

- macOS aarch64
- Eclipse Temurin `25.0.3+9-LTS`
- Gradle `9.5.1`
- Fabric Loom `1.17.0-alpha.7`
- Minecraft `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.152.2+26.2`
- Carpet `26.2+v260616`

## 执行命令

```shell
./gradlew --no-daemon runClientGameTest
```

未设置 `PEARLRELAY_CLIENT_TEST_REPETITIONS`，因此三个客户端场景均使用默认
20 次重复：

- 20 次基础真实右键和珍珠所有权检查；
- 20 次原版滞留装置对照；
- 20 次 Pearl Relay 真实传送。

## 原版夹具对照

`PearlStasisFixture` 暴露固定的投掷点、玩家等待点、假玩家出生点、激活
目标、滞留区域和传送目的地区域。测试每轮都：

1. 等待 30 格水柱全部转换为 `BUBBLE_COLUMN`；
2. 使用真实客户端鼠标输入投掷一颗末影珍珠；
3. 在服务端确认总珍珠数和当前玩家拥有数严格为 `1/1`；
4. 在 40 tick 稳定窗口内确认珍珠留在装置区域且玩家没有提前传送；
5. 直接调用原版活板门交互一次；
6. 同时确认服务端和客户端玩家进入目的地容差包围盒，珍珠数回到 `0/0`。

第一次成功滞留生成：
`build/run/clientGameTest/screenshots/0001_pearlrelay-vanilla-stasis-before-release.png`。

## Pearl Relay 端到端链路

测试通过 Fabric Client GameTest 创建进程内专用服务器，再由真实客户端
连接。由于 Client GameTest 的专用服务器与客户端位于同一 JVM，不会重新
运行 Fabric Loader，测试 source set 在启动前只调用一次生产
`PearlRelayMod` 初始化器。发布 JAR 不包含这个桥接。

每轮验证：

- 客户端连接发送 `pearlrelay save e2e ...`，收到 `Saved relay: e2e`；
- 客户端真实输入投掷当前连接玩家拥有的珍珠；
- 客户端发送 `pearlrelay fire e2e`，收到 `Relay queued: e2e`；
- 活板门只出现一次开到关的状态转换；
- 珍珠被释放，客户端和服务端玩家均到达目的地区域；
- 客户端收到同一中继的完成消息；
- 结构化日志恰好包含一条 `accepted` 和一条 `completed terminal`，
  两条事件的 `execution_id` 相同；
- 终态后只剩真实连接玩家，没有孤儿假玩家；
- 客户端发送 `pearlrelay remove e2e` 并收到成功消息。

正式日志的可见计数：

| 证据 | 次数 |
|---|---:|
| `Saved relay: e2e` | 20 |
| `Relay queued: e2e` | 20 |
| `Relay 'e2e' completed` | 20 |
| `Removed relay: e2e` | 20 |
| 假玩家服务端加入 | 20 |
| 假玩家服务端离开 | 20 |

结构化事件由测试专用 Log4j appender 在内存中按每轮 mark 隔离断言，避免
依赖文本日志刷新时机。

## 捕获并修复的真实输入问题

最初夹具场景复用瞬时 `pressMouse`。第一次传送后的下一轮可能在客户端尚未
发布新位置和朝向时触发，导致服务端没有收到珍珠。修正后每轮：

1. 同时等待客户端到达投掷点并收到末影珍珠；
2. 设置视角后等待一个客户端 tick；
3. 使用 `holdMouseFor(1, 2)` 保持一次连续物理手势跨过客户端 tick。

末影珍珠冷却保证这次连续手势最多产生一颗投射物，服务端仍保持严格的
`1/1` 总数/所有权断言。修正后两轮聚焦测试和默认 20 轮正式门槛均通过。

## 已知的测试环境日志

Carpet 首次为固定测试假玩家名解析档案时会请求 Mojang 档案接口并收到
404，然后使用离线档案正常创建假玩家。后续 19 轮复用缓存，不再重复查询。
该现象不需要账号凭据，也没有改变测试结果；阶段 3 的 CI 工作会继续验证
完全无人值守环境下的行为。

## 全量回归与发布隔离

```shell
./gradlew --no-daemon clean test build
```

- `BUILD SUCCESSFUL`，耗时 1 分 11 秒；
- JUnit：47/47 通过，0 failure、0 error、0 skipped；
- Fabric 服务端 GameTest：21/21 通过；
- `git diff --check` 无错误。

```shell
unzip -l build/libs/pearlrelay-1.1.0-rc.2.jar
```

非 sources 发布 JAR 共 54 个条目；其中没有
`PearlRelayClientGameTests`、`PearlStasisFixture`、
`ClientCommandDriver`、`RelayLogProbe`、`pearlrelay-gametest` 或测试包。

## 阶段边界

Checkpoint B 已替代“真实装置释放末影珍珠并完成传送”这一人工回归项目。
玩家可见的假玩家位置、朝向、瞬态截图，以及生产映射后的客户端 CI 门槛
仍属于阶段 3。
