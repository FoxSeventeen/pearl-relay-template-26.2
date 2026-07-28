# Pearl Relay / 末影珍珠中继

Pearl Relay 是一个面向 Minecraft 26.2 的 Fabric 服务端模组。玩家可以保存一个 Carpet 假人的出生位置和看向位置，然后用 `/pearlrelay fire <名称>` 安全触发珍珠滞留装置。

当前候选版本是 `1.1.0-rc.2`。它已经通过
[自动化与无客户端测试](tasks/test-results/v1.1.0-rc.2.md)，但必须完成
[玩家实机验收](docs/testing/v1.1.0-player-acceptance.md)后才能发布稳定版
`1.1.0`。

## 运行依赖

- Minecraft `26.2`
- Java `25`
- Fabric Loader `0.19.3` 或更高
- Fabric API `0.152.1+26.2` 或更高（本地构建验证 `0.152.2`）
- Carpet `26.2`

本模组只需安装在服务端。

## 安装与构建

在 Java 25 环境中执行：

```shell
./gradlew clean test build
```

Windows：

```powershell
.\gradlew.bat clean test build
```

把 `build/libs/pearlrelay-1.1.0-rc.2.jar` 复制到服务端 `mods` 目录。不要部署 `-sources.jar`。

## 指令

```mcfunction
/pearlrelay test
/pearlrelay save <名称> <维度> <假人出生坐标> <看向坐标>
/pearlrelay list
/pearlrelay fire <名称>
/pearlrelay remove <名称>
```

开发排查指令：

```mcfunction
/pearlrelay fireRaw <假人名> <维度> <假人出生坐标> <看向坐标>
```

坐标使用 Minecraft 原生三维坐标格式，支持绝对坐标和 `~ ~ ~`。`fire`、`remove` 支持名称补全；`save`、`fireRaw` 支持维度补全。

示例：

```mcfunction
/pearlrelay save home minecraft:overworld 100.5 64 200.5 101.5 65 200.5
/pearlrelay list
/pearlrelay fire home
/pearlrelay remove home
```

`save` 会从假人眼睛位置向 `lookAt` 射线检测。出生空间、射线路径和目标区块必须已加载；出生点不能被方块阻挡；射线必须在生存模式交互距离内命中 `lookAt` 所在方块。保存成功后会记录目标方块坐标和方块类型。

## `fire` 的安全检查

`/pearlrelay fire <名称>` 会在创建假人之前依次检查：

1. 配置是新版格式，并且保存的维度存在。
2. 目标、假人出生位置和射线路径涉及的区块已经加载。
3. 假人出生空间没有方块阻挡，也没有其他玩家占据。
4. 目标仍可从保存的出生点命中。
5. 目标方块类型与保存时相同。
6. 目标方块所在区块内至少有一颗属于命令执行玩家的末影珍珠。
7. 同名中继假人没有被占用，也没有正在执行的同名任务。

任一检查失败都会在创建假人之前拒绝。这里的“已加载”要求区块已达到实体可 tick 状态；仅作为其他票据的低状态邻区块缓存不算可用。模组不会自动加载或生成区块。

通过检查后，模组只执行保存的激活动作：

```text
spawn -> lookAt -> use once -> cleanup
```

珍珠只用于判断装置是否准备好。模组不会选择、移动、释放或直接操作某一颗珍珠。存在多颗属于执行者的珍珠时仍只点按目标一次。

保存的是方块类型，不是完整方块状态。例如同一个拉杆开关状态变化仍可触发；把拉杆换成按钮、其他方块或空气会拒绝。

## 常见拒绝原因

| 错误码 | 含义 |
|---|---|
| `RELAY_NOT_FOUND` | 当前玩家没有这个中继 |
| `RELAY_REQUIRES_RESAVE` | 旧版配置缺少目标指纹，需要用同名 `save` 重新保存 |
| `DIMENSION_UNAVAILABLE` | 保存的维度不存在 |
| `SPAWN_CHUNK_UNLOADED` | 假人出生区块未加载 |
| `TARGET_CHUNK_UNLOADED` | 目标或射线路径区块未加载；按设计视为没有可用珍珠 |
| `SPAWN_POSITION_BLOCKED` | 假人出生空间被方块阻挡或被玩家占据 |
| `TARGET_BLOCK_CHANGED` | 目标方块类型已改变 |
| `TARGET_UNREACHABLE` | 目标太远、被遮挡或不再命中保存的方块 |
| `OWNED_PEARL_NOT_FOUND` | 目标方块所在区块没有属于执行玩家的珍珠 |
| `EXECUTION_ALREADY_ACTIVE` | 同一假人已有执行中的任务 |
| `FAKE_PLAYER_NAME_IN_USE` | 生成的假人名已被真实玩家或其他玩家实体占用 |
| `FAKE_PLAYER_CREATE_FAILED` | Carpet 未能创建假人 |
| `FAKE_PLAYER_SPAWN_TIMEOUT` | 假人未在期限内完成生成 |
| `EXECUTION_INTERNAL_ERROR` | 执行阶段发生内部错误 |
| `EXECUTION_CLEANUP_TIMEOUT` | 无法在期限内确认假人已退出 |

立即拒绝消息以 `[错误码] Fire rejected:` 开头。接受后会返回 `execution=<UUID>`；完成或失败消息使用同一执行 ID。

## 配置与旧版迁移

配置按玩家 UUID 隔离：

```text
config/pearlrelay/players/<player-uuid>.json
```

`1.1` 写入 `schemaVersion: 2`，并保存目标方块坐标和注册表 ID。旧版配置不会被删除，仍可在 `/pearlrelay list` 中看到，但 `/pearlrelay fire` 会返回 `RELAY_REQUIRES_RESAVE`。站在正确装置旁重新执行同名 `save` 即可原地升级。

## 日志与排查

生命周期事件写入服务端标准日志：

```shell
grep 'event=relay_fire' logs/latest.log
```

每条记录包括 `action`、`execution_id`、`relay`、`player_uuid`、`bot`、`dimension`、`target_chunk`、`pearl_count`、`phase`、`result`、`failure_code` 和 `duration_ticks`。成功执行有一条 `accepted` 和一条同执行 ID 的 `terminal`；预检拒绝只有一条 `rejected`，且不会留下假人。

## 开发验证

完整本地检查：

```shell
./gradlew clean test build
```

真实客户端端到端检查（需要图形显示环境）：

```shell
./gradlew runClientGameTest
./gradlew runProductionClientGameTest
```

第一条从开发编译输出运行，第二条要求生产主类从实际发布 JAR 加载。两条
客户端任务默认各重复 20 次；排查时可用环境变量
`PEARLRELAY_CLIENT_TEST_REPETITIONS=1` 临时缩短。详细证据记录在
`tasks/test-results/`。

Fabric 服务端 GameTest 会验证目标解析、珍珠所有权、方块类型/状态、单次
右键和假人清理。GitHub Actions 在每次 push 和 pull request 中使用
Java 25 运行干净构建门槛，并在 XVFB 中运行生产客户端 GameTest；它会上传
候选 JAR、SHA-256、提交 SHA、测试报告、日志、截图和崩溃数据。工作流只
读取仓库和公开依赖，不会连接生产服或隔离测试服，也不包含服务器凭据。

## English summary

Pearl Relay is a server-only Fabric mod for Minecraft 26.2. A named relay stores a Carpet fake player's spawn and look target. Before `/pearlrelay fire <name>` creates the fake player, v1.1 checks the dimension, already-loaded chunks, spawn collision, saved target block type and reachability, and at least one Ender Pearl owned by the invoking player in the target block's chunk. It never loads chunks or selects/manipulates a pearl. A valid execution performs exactly one saved `spawn -> lookAt -> use` action and then removes the fake player.

Legacy v1 relay files remain listable but must be saved again before use. See the table above for stable failure codes and [the player acceptance guide](docs/testing/v1.1.0-player-acceptance.md) for release testing.
