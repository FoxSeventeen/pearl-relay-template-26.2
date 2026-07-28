# Pearl Relay / 末影珍珠中继

Pearl Relay 是一个面向 Minecraft 26.2 的 Fabric 服务端模组。玩家可以保存一个 Carpet 假人的出生位置和看向位置，然后用 `/pearlrelay fire <名称>` 安全触发珍珠滞留装置。

当前候选版本是 `1.2.0-rc.1`。RC 验证状态和发布身份记录在
[候选验证报告](tasks/test-results/v1.2.0-rc.1.md)；最新稳定版仍是
[`1.1.0`](tasks/test-results/v1.1.0.md)。
原 16 项机械式玩家回归已映射到自动化证据，项目所有者已批准
[修订后的验收边界](docs/testing/v1.1.0-player-acceptance.md)。本次发布不声明
指定整合包、代理、权限系统兼容性或主观体验承诺，因此不增加重复的真人
回归；后续若增加此类声明，再执行针对性的探索检查。

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

把 `build/libs/pearlrelay-1.2.0-rc.1.jar` 复制到测试服务端 `mods` 目录。
不要把 RC 部署到生产服，也不要部署 `-sources.jar`。

## 指令

```mcfunction
/pearlrelay test
/pearlrelay save <名称>
/pearlrelay save <名称> <维度> <假人出生坐标> <看向坐标>
/pearlrelay list
/pearlrelay fire <名称>
/pearlrelay remove <名称>
```

开发排查指令：

```mcfunction
/pearlrelay fireRaw <假人名> <维度> <假人出生坐标> <看向坐标>
```

`save <名称>` 是推荐方式：玩家站在未来假人的出生位置、看向需要点按的
方块后执行命令，模组会采集玩家当前维度、脚部精确位置和实际视线命中点。

完整参数形式继续用于脚本和精确调试。坐标使用 Minecraft 原生三维坐标
格式，支持绝对坐标和 `~ ~ ~`。`fire`、`remove` 支持名称补全；完整参数
`save` 和 `fireRaw` 支持维度补全。

示例：

```mcfunction
/pearlrelay save home
/pearlrelay save home minecraft:overworld 100.5 64 200.5 101.5 65 200.5
/pearlrelay list
/pearlrelay fire home
/pearlrelay remove home
```

两种 `save` 都会从未来假人的标准站立眼睛位置向 `lookAt` 射线检测。
出生空间、射线路径和目标区块必须已加载；出生点不能被方块阻挡；射线必须
在生存模式交互距离内命中目标方块。简写保存允许执行者本人站在出生位置，
但触发时任何玩家仍占据该位置都会被拒绝。保存成功后会记录目标方块坐标和
方块类型。

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
| `PLAYER_REQUIRED` | 简写保存只能由游戏内玩家执行 |
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
| `CONFIG_CORRUPT` | 玩家配置损坏，且没有有效的同玩家备份 |
| `CONFIG_RECOVERED_RETRY` | 已从同玩家备份恢复配置；本次没有执行操作，请重试 |
| `CONFIG_RECOVERY_FAILED` | 配置恢复失败；本次没有执行操作 |

点火立即拒绝消息以 `[错误码] Fire rejected:` 开头。保存目标拒绝消息以
`[错误码] Save rejected:` 开头。接受点火后会返回 `execution=<UUID>`；
完成或失败消息使用同一执行 ID。

## 配置与旧版迁移

配置按玩家 UUID 隔离：

```text
config/pearlrelay/players/<player-uuid>.json
```

`1.2` 继续写入 `schemaVersion: 2`，并保存目标方块坐标和注册表 ID，因此
`1.1` 配置无需迁移。旧版 schema v1 配置不会被删除，仍可在
`/pearlrelay list` 中看到，但 `/pearlrelay fire` 会返回
`RELAY_REQUIRES_RESAVE`。站在正确装置旁、看向点火方块，重新执行同名
`/pearlrelay save <名称>` 即可原地升级。

配置保存使用同目录临时文件、刷新和原子替换；最近一次成功主文件保留为
同玩家 `.bak`。主文件损坏且备份有效时，模组先保留 `.corrupt-N`，再恢复
主文件并返回 `CONFIG_RECOVERED_RETRY`；玩家重试后才会继续业务动作。没有
有效备份时不会覆盖损坏文件。

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

Pearl Relay is a server-only Fabric mod for Minecraft 26.2. In v1.2, a player can
stand where the Carpet fake player should spawn, look at the activation block, and
run `/pearlrelay save <name>`; the mod captures the current dimension, exact feet
position, and block hit. Before `/pearlrelay fire <name>` creates the fake player,
it checks the dimension, already-loaded chunks, spawn collision, saved target block
type and reachability, and at least one Ender Pearl owned by the invoking player in
the target block's chunk. It never loads chunks or selects/manipulates a pearl. A
valid execution performs exactly one saved `spawn -> lookAt -> use` action and then
removes the fake player.

Legacy v1 relay files remain listable but must be saved again before use. See the table above for stable failure codes and [the release acceptance boundary](docs/testing/v1.1.0-player-acceptance.md).
