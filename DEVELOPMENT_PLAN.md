# Pearl Relay 开发计划

## 当前进度

项目目前处在第八个功能检查点：底层 `fireRaw` 已经可以正常触发真实末影珍珠滞留器，命名中继配置版也已经实现，并且配置已经改为按玩家 UUID 隔离。当前需要测试 `save`、`list`、`fire`、`remove` 这组命令，以及不同玩家之间是否互相不可见。

已经完成的内容：

- Fabric 服务端模组入口类：`com.foxseventeen.pearlrelay.PearlRelayMod`
- 模组 ID：`pearlrelay`
- Gradle 和 `fabric.mod.json` 中已经声明 Carpet 依赖
- 已注册测试指令：

```mcfunction
/pearlrelay test
```

- 已注册开发期参数解析指令：

```mcfunction
/pearlrelay fireRaw <bot> <dimension> <spawnX> <spawnY> <spawnZ> <lookX> <lookY> <lookZ>
```

- `fireRaw` 现在会把 `dimension` 参数解析为真实的服务端维度。
- 如果维度不存在或未加载，会返回命令错误，而不是继续执行。
- 如果指定名称的假人不存在，`fireRaw` 会尝试调用 Carpet API 创建假人。
- 如果指定名称已经是 Carpet 假人，`fireRaw` 会复用该假人并传送到目标位置。
- 如果指定名称属于真实玩家，`fireRaw` 会拒绝执行。
- 创建或复用假人后，`fireRaw` 会让假人看向 `lookAt` 坐标。
- 为避免 Carpet 假人刚创建时的初始化覆盖朝向，`fireRaw` 会立即执行一次 `lookAt`，并在后续 tick 中延迟重放一次 `lookAt`。
- 朝向稳定后，`fireRaw` 会排队执行一次右键 `use once`。
- 右键执行后，假人会自动退出服务器，避免长期占用在线玩家列表。
- 现在可以把一组维度、spawn 坐标、lookAt 坐标保存为命名中继点。
- 保存时会根据玩家 UUID 和装置名自动生成 Carpet 假人名。
- 每个玩家只能看到和触发自己的中继点。
- 保存后的中继点可以通过 `/pearlrelay fire <relayName>` 触发。
- `/pearlrelay fire <relayName>` 和 `/pearlrelay remove <relayName>` 支持 Tab 补全当前玩家自己的中继点名称。

预期返回：

```text
Pearl Relay command works.
```

已经由开发环境验证的内容：

- `gradle build` 可以成功完成。
- `runServer` 可以成功启动。
- Fabric Loader 已经加载 `pearlrelay 1.0.0`。
- 服务端日志已经打印：

```text
Pearl Relay loaded.
```

已经由游戏内验证的内容：

- 在游戏内执行 `/pearlrelay test`，可以正常返回 `Pearl Relay command works.`。

仍需要手动确认的一点：

- 在游戏内执行 `/pearlrelay fireRaw ...`，确认参数解析和回显正常。

## 你现在需要测试什么

### 1. 启动开发服务器

在项目根目录执行：

```powershell
.\gradlew.bat runServer
```

如果 Gradle Wrapper 在下载或解析依赖时出问题，可以通过 IDE 的 Gradle 面板启动 `runServer`，或者等网络可用后重试。

### 2. 进入服务器

启动 Minecraft 26.2 Fabric 客户端，然后连接：

```text
localhost:25565
```

确保你的玩家是 OP。如果执行指令时提示权限不足，可以在服务端控制台执行：

```mcfunction
op <你的玩家名>
```

### 3. 执行测试指令

在游戏内执行：

```mcfunction
/pearlrelay test
```

预期结果：

```text
Pearl Relay command works.
```

### 4. 测试错误情况

也可以顺手测试：

```mcfunction
/pearlrelay
/pearlrelay unknown
```

预期结果：

- `/pearlrelay test` 正常成功。
- 未知子指令会被 Minecraft 的命令解析器正常拒绝。
- 服务端不会崩溃。

## 当前所处开发阶段

### 阶段 1：指令注册

目标：

- 确认模组入口类正常加载。
- 确认 Fabric 指令注册机制正常工作。
- 确认指令可以向执行者返回反馈文本。

当前状态：

- 代码已实现。
- 编译已验证。
- 服务端启动已验证。
- 游戏内指令测试已完成。

完成标准：

- 在游戏内执行 `/pearlrelay test` 后，返回 `Pearl Relay command works.`。

阶段状态：

- 已完成。

## 后续开发阶段

### 阶段 2：添加 `fireRaw` 参数解析

目标：

添加一个开发期原始触发指令，接收一次珍珠中继触发需要的全部参数，但暂时只解析并打印参数，不创建假人。

当前状态：

- 代码已实现。
- 编译已通过。
- 游戏内测试待完成。

计划指令：

```mcfunction
/pearlrelay fireRaw <bot> <dimension> <spawnX> <spawnY> <spawnZ> <lookX> <lookY> <lookZ>
```

示例：

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:overworld 100.5 64 200.5 101.5 64 200.5
```

预期返回：

```text
bot=relay_bot, dimension=minecraft:overworld, spawn=(100.5, 64.0, 200.5), lookAt=(101.5, 64.0, 200.5)
```

为什么要先做这个阶段：

- 可以验证 Brigadier 参数解析。
- 可以验证维度 ID 参数是否能正常输入。
- 可以先固定指令形状，再接入 Carpet 假人逻辑。

本阶段测试内容：

- 合法的主世界参数可以正确解析。
- 合法的下界参数可以正确解析。
- 非法维度 ID 能被清晰拒绝。
- 缺少坐标时，命令解析器能给出正常语法错误。

### 阶段 3：解析维度并校验服务端世界

目标：

把 `dimension` 参数转换为真实的 `ServerLevel`。

当前状态：

- 代码已实现。
- 编译已通过。
- 游戏内测试待完成。

需要实现的行为：

- 接受 `minecraft:overworld`、`minecraft:the_nether`、`minecraft:the_end`。
- 拒绝不存在或未加载的维度。
- 返回清晰错误，而不是让服务端崩溃。

测试指令：

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:overworld 100 64 100 101 64 100
/pearlrelay fireRaw relay_bot minecraft:missing 100 64 100 101 64 100
```

预期结果：

- 存在的维度可以通过。
- 不存在的维度返回可读的命令错误。

### 阶段 4：创建 Carpet 假人

目标：

调用 Carpet 的假人 API，在指定维度和指定位置创建假人。

当前状态：

- 代码已实现。
- 编译已通过。
- 游戏内测试待完成。

目标 Carpet API：

```java
EntityPlayerMPFake.createFake(
    botName,
    server,
    spawnPos,
    yaw,
    pitch,
    dimensionKey,
    GameType.SURVIVAL,
    false
);
```

需要实现的行为：

- 如果指定名称的假人不存在，就创建它。
- 如果指定名称的玩家已经存在，并且是 Carpet 假人，则复用或移动它。
- 如果指定名称属于真实玩家，则拒绝执行指令。

本阶段测试内容：

- 使用一个新 bot 名执行 `fireRaw`。
- 通过 `/list` 确认假人已出现。
- 传送到假人附近，确认位置正确。
- 使用相同 bot 名再次执行指令，确认不会异常。
- 使用真实在线玩家名作为 bot 名，确认指令被拒绝。

### 阶段 5：让假人看向目标位置

目标：

使用 Carpet 的 action pack，让假人转头看向指定目标点。

当前状态：

- 代码已实现。
- 编译已通过。
- 游戏内测试待完成。

目标 Carpet API：

```java
((ServerPlayerInterface) fakePlayer)
    .getActionPack()
    .lookAt(lookAtPos);
```

本阶段测试内容：

- 在目标位置放一个明显的方块、按钮或拉杆。
- 执行 `fireRaw`。
- 观察假人是否看向目标。
- 从假人周围不同方向设置 `lookAt` 目标，确认朝向都正确。

### 阶段 6：让假人执行一次右键

目标：

使用 Carpet 的 action pack，让假人执行一次右键 `use once`。

当前状态：

- 代码已实现。
- 编译已通过。
- 游戏内测试待完成。

目标 Carpet API：

```java
actionPack.start(
    EntityPlayerActionPack.ActionType.USE,
    EntityPlayerActionPack.Action.once()
);
```

本阶段测试内容：

- 在目标位置放按钮、拉杆、门、活板门等简单可交互方块。
- 执行 `fireRaw`。
- 确认假人能激活该方块。
- 重复执行指令，确认每次都是一次性右键，而不是持续右键。

建议先用按钮或拉杆测试，不要一上来就测试末影珍珠滞留器。简单方块能帮我们区分问题到底来自指令逻辑，还是来自红石结构和珍珠机制。

### 阶段 7：接入末影珍珠滞留器测试

目标：

让假人通过右键触发真实的末影珍珠滞留器。

测试前检查清单：

- 假人生成位置正确。
- 假人看向目标点正确。
- 滞留器所在区块已加载。
- 假人的右键射线能碰到目标方块或实体。
- 末影珍珠滞留器可以手动触发。
- Carpet 假人机制本身可用。

建议测试顺序：

1. 手动触发末影珍珠滞留器，确认装置本身可用。
2. 使用 Carpet 原生命令触发，确认假人可以触发装置。
3. 使用 `/pearlrelay fireRaw` 触发。
4. 对比 Carpet 原生命令和本模组指令的行为。

判断方式：

- 如果 Carpet 原生命令能成功，但 `fireRaw` 失败，问题大概率在我们的指令逻辑。
- 如果 Carpet 原生命令也失败，问题大概率在装置、区块加载、目标角度或游戏机制设置上。

### 阶段 8：添加命名中继配置

目标：

从原始坐标指令升级为命名装置指令。

当前状态：

- 代码已实现。
- 编译已通过。
- 游戏内测试待完成。

目标指令：

```mcfunction
/pearlrelay fire <relayName>
```

配套指令：

```mcfunction
/pearlrelay save <relayName> <dimension> <spawnX> <spawnY> <spawnZ> <lookX> <lookY> <lookZ>
/pearlrelay list
/pearlrelay remove <relayName>
```

配置存储方案：

- 把中继点位保存为 JSON。
- 保存位置建议放在服务端配置目录。
- 格式保持简单，方便手动编辑。
- 当前实现会按玩家 UUID 分文件保存到 `config/pearlrelay/players/<player-uuid>.json`。

示例 JSON：

```json
{
  "relays": {
    "home": {
      "bot": "relay_home_bot",
      "dimension": "minecraft:overworld",
      "spawn": [100.5, 64.0, 200.5],
      "lookAt": [101.5, 64.0, 200.5]
    }
  }
}
```

本阶段测试内容：

- 保存一个中继点。
- 列出中继点。
- 触发已保存的中继点。
- 重启服务器。
- 确认保存的中继点仍然能加载。
- 删除一个中继点。

建议测试顺序：

```mcfunction
/pearlrelay save home minecraft:overworld 100.5 64 200.5 101.5 64 200.5
/pearlrelay list
/pearlrelay fire home
/pearlrelay remove home
/pearlrelay list
```

### 阶段 9：面向真实服务器使用的完善

目标：

让模组更适合在真实服务器里长期使用。

可以考虑添加的功能：

- 权限等级检查。
- 每个中继点的冷却时间。
- 更清晰的成功和失败提示。
- 可选的假人自动清理。
- 可选的区块加载检查。
- 触发日志。
- 默认游戏模式配置。
- 假人名称前缀配置。

推荐默认权限：

- 要求权限等级 2 或更高，和常见 OP/命令方块级别的服务端指令保持一致。

## 推荐的立即下一步

先完成阶段 2 和阶段 3 的游戏内验证：

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:overworld 100.5 64 200.5 101.5 64 200.5
```

预期返回类似：

```text
bot=relay_bot, dimension=minecraft:overworld, spawn=(100.500, 64.000, 200.500), lookAt=(101.500, 64.000, 200.500)
```

然后测试非法维度：

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:missing 100.5 64 200.5 101.5 64 200.5
```

预期返回类似：

```text
Unknown or unloaded dimension: minecraft:missing
```

确认成功后，继续测试 Carpet 假人创建：

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:overworld 100.5 64 200.5 101.5 64 200.5
```

预期结果：

- 第一次执行时，返回中包含 `fakePlayer=created`。
- `/list` 中可以看到 `relay_bot`。
- 再次执行同一条指令时，返回中包含 `fakePlayer=reused`。
- 如果使用真实在线玩家名作为 `bot` 参数，会返回名称被真实玩家占用的错误。

确认成功后，继续测试假人朝向：

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:overworld 100.5 64 200.5 101.5 64 200.5
```

建议在 `lookAt` 坐标附近放一个明显方块、按钮或拉杆，然后观察 `relay_bot` 是否转头看向该目标。

如果是假人第一次进入世界，重点观察它是否不需要第二次执行指令就能自动转向正确方向。

确认成功后，继续测试右键交互：

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:overworld 100.5 64 200.5 101.5 64 200.5
```

建议先把 `lookAt` 目标设置到按钮、拉杆、门或活板门上，确认假人能完成一次右键互动。

确认成功后，再进入阶段 7：接入真实的末影珍珠滞留器测试。
