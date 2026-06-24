# Pearl Relay / 末影珍珠中继

Pearl Relay is a server-side Fabric mod for Minecraft 26.2. It wraps Carpet fake-player commands into a compact relay workflow: spawn or reuse a fake player, make it look at a configured target, right-click once, then leave the server automatically.

Pearl Relay 是一个面向 Minecraft 26.2 的 Fabric 服务端模组。它把 Carpet 假人的召唤、转向、右键互动和自动退出封装成一组指令，用来触发已经搭建好的末影珍珠滞留器，实现远距离传送中继。

## 中文说明

### 功能

- 一键触发已保存的末影珍珠滞留装置：`/pearlrelay fire <装置名>`
- 按玩家 UUID 独立保存装置列表，不同玩家之间互不可见。
- 自动生成 Carpet 假人名，格式类似 `pr_<玩家短UUID>_<装置名>`，并控制在 Minecraft 玩家名长度限制内。
- 支持在指定维度、指定坐标召唤或复用假人。
- 让假人看向指定位置，并执行一次右键互动。
- 假人完成互动后会自动退出服务器。
- `fire`、`remove` 支持装置名 Tab 补全。
- `save`、`fireRaw` 的维度参数支持当前服务器维度 Tab 补全。
- 坐标参数使用 Minecraft 原生三维坐标解析，支持数字坐标、`~ ~ ~` 相对坐标和客户端坐标补全。

### 运行依赖

- Minecraft `26.2`
- Fabric Loader `0.19.3` 或更高
- Fabric API `0.152.2+26.2`
- Carpet `26.2`
- Java `25`

这是服务端模组，只需要放在服务器 `mods` 目录中。服务器也需要安装匹配版本的 Fabric API 和 Carpet。

### 安装

1. 执行构建：

```powershell
.\gradlew.bat clean build
```

2. 将下面这个文件复制到服务器的 `mods` 目录：

```text
build/libs/pearlrelay-1.0.0.jar
```

不要复制 `pearlrelay-1.0.0-sources.jar`，它只是源码包，不能作为服务端模组运行。

### 指令

测试模组是否加载：

```mcfunction
/pearlrelay test
```

保存一个属于当前玩家的装置：

```mcfunction
/pearlrelay save <装置名> <维度> <召唤坐标> <看向坐标>
```

示例：

```mcfunction
/pearlrelay save home minecraft:overworld 100.5 64 200.5 101.5 64 200.5
/pearlrelay save home minecraft:overworld ~ ~ ~ ~ ~ ~
```

查看当前玩家保存的装置：

```mcfunction
/pearlrelay list
```

触发当前玩家保存的装置：

```mcfunction
/pearlrelay fire <装置名>
```

删除当前玩家保存的装置：

```mcfunction
/pearlrelay remove <装置名>
```

开发和排查用原始触发指令：

```mcfunction
/pearlrelay fireRaw <假人名> <维度> <召唤坐标> <看向坐标>
```

示例：

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:overworld 100.5 64 200.5 101.5 64 200.5
```

### 配置文件

装置配置会按玩家 UUID 分文件保存：

```text
config/pearlrelay/players/<player-uuid>.json
```

这样每个玩家只会看到和触发自己的装置列表。

### 测试建议

1. 启动开发服务器：

```powershell
.\gradlew.bat runServer
```

2. 进入服务器后执行：

```mcfunction
/pearlrelay test
```

如果返回 `Pearl Relay command works.`，说明模组和指令注册正常。

3. 使用 `/pearlrelay save` 保存一个实际装置点位。
4. 使用 `/pearlrelay list` 确认装置只出现在当前玩家列表中。
5. 使用 `/pearlrelay fire <装置名>` 触发装置，观察假人是否正确出现、转向、右键并自动退出。
6. 换另一个玩家进入服务器，确认看不到第一个玩家保存的装置。

## English

### Features

- Fire a saved pearl stasis relay with one command: `/pearlrelay fire <relayName>`
- Store relays per player UUID, so each player has an isolated relay list.
- Generate Carpet fake-player names automatically, using a pattern like `pr_<shortUUID>_<relayName>` while staying within the Minecraft player-name length limit.
- Spawn or reuse a fake player in a specific dimension at a specific position.
- Make the fake player look at a target position and right-click once.
- Automatically remove the fake player after the interaction.
- Tab completion for saved relay names in `fire` and `remove`.
- Tab completion for loaded server dimensions in `save` and `fireRaw`.
- Native Minecraft 3D coordinate parsing for positions, including absolute coordinates, `~ ~ ~` relative coordinates, and client coordinate suggestions.

### Requirements

- Minecraft `26.2`
- Fabric Loader `0.19.3` or newer
- Fabric API `0.152.2+26.2`
- Carpet `26.2`
- Java `25`

This is a server-side mod. Install it on the server together with matching Fabric API and Carpet versions.

### Installation

1. Build the mod:

```powershell
.\gradlew.bat clean build
```

2. Copy this file into the server `mods` directory:

```text
build/libs/pearlrelay-1.0.0.jar
```

Do not copy `pearlrelay-1.0.0-sources.jar`; it is a source jar and cannot be used as the runtime server mod.

### Commands

Check that the mod is loaded:

```mcfunction
/pearlrelay test
```

Save a relay for the current player:

```mcfunction
/pearlrelay save <relayName> <dimension> <spawnPosition> <lookAtPosition>
```

Examples:

```mcfunction
/pearlrelay save home minecraft:overworld 100.5 64 200.5 101.5 64 200.5
/pearlrelay save home minecraft:overworld ~ ~ ~ ~ ~ ~
```

List relays saved by the current player:

```mcfunction
/pearlrelay list
```

Fire a relay saved by the current player:

```mcfunction
/pearlrelay fire <relayName>
```

Remove a relay saved by the current player:

```mcfunction
/pearlrelay remove <relayName>
```

Raw development/debug command:

```mcfunction
/pearlrelay fireRaw <botName> <dimension> <spawnPosition> <lookAtPosition>
```

Example:

```mcfunction
/pearlrelay fireRaw relay_bot minecraft:overworld 100.5 64 200.5 101.5 64 200.5
```

### Config Files

Relay definitions are stored per player UUID:

```text
config/pearlrelay/players/<player-uuid>.json
```

This keeps each player's relay list private to that player.

### Testing

1. Start the development server:

```powershell
.\gradlew.bat runServer
```

2. Join the server and run:

```mcfunction
/pearlrelay test
```

If it returns `Pearl Relay command works.`, the mod and command registration are working.

3. Use `/pearlrelay save` to save a real stasis relay location.
4. Use `/pearlrelay list` to verify that the relay appears only for the current player.
5. Use `/pearlrelay fire <relayName>` and check that the fake player appears, faces the target, right-clicks, and leaves automatically.
6. Join with another player and verify that the first player's relay is not visible.
