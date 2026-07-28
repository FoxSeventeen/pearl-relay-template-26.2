# 客户端 E2E 阶段 1 结果

## 结论

2026-07-28，Checkpoint A 通过。Minecraft 26.2 客户端可以无人值守地创建
并关闭受控世界；Fabric `TestInput` 的一次真实右键会在服务端产生恰好一颗
末影珍珠，且所有者 UUID 等于当前连接玩家 UUID。该场景在同一个干净测试
世界中连续执行 20 次通过。

## 环境

- macOS aarch64
- Eclipse Temurin `25.0.3+9-LTS`
- Gradle `9.5.1`
- Fabric Loom `1.17.0-alpha.7`
- Minecraft `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.152.2+26.2`
- Carpet `26.2+v260616`

## 执行结果

客户端检查：

```shell
./gradlew --no-daemon runClientGameTest
```

- 受控单人世界成功打开并关闭。
- 20 次循环均使用 `TestInput.pressMouse(1)`，没有直接构造
  `ThrownEnderpearl`。
- 每次输入前服务端珍珠数为 0；输入后总数和当前玩家拥有数均为 1；清理后
  均回到 0。
- 第一次成功输入生成
  `build/run/clientGameTest/screenshots/0000_pearlrelay-owned-ender-pearl.png`。

全量回归：

```shell
./gradlew --no-daemon clean test build
```

- `BUILD SUCCESSFUL`
- JUnit：47/47 通过，0 failure、0 error、0 skipped
- Fabric 服务端 GameTest：21/21 通过

发布隔离：

```shell
unzip -l build/libs/pearlrelay-1.1.0-rc.2.jar
```

发布 JAR 中没有 `PearlRelayClientGameTests`、`pearlrelay-gametest` 或测试包。

## 捕获并修复的抖动

最初实现只在输入后固定等待一个 tick。20 次压力循环在第 14 次按预期失败：
服务端当时尚未观察到珍珠。测试随后改为：

1. 输入前同时确认客户端和服务端的末影珍珠冷却已经解除；
2. 仍然只发送一次右键；
3. 在最多 10 tick 内轮询服务端终态；
4. 对珍珠总数和归属数保持严格的 `1/1` 断言。

修正后的完整 20 次循环通过。这里没有通过重发输入来掩盖失败。

## 阶段 2 约束

阶段 1 的集成服务器适合验证原版客户端输入边界，但客户端进程不会初始化
`"environment": "server"` 的 Pearl Relay 生产入口。Task 4 必须使用
`TestWorldBuilder.createServer()` 启动专用服务器，再由测试客户端连接，
才能证明实际发布 mod 的 `save` / `fire` 流程。
