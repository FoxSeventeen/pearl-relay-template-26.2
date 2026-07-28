# ADR-0002：用发布 JAR 执行生产客户端 GameTest

## 状态

Accepted

## 日期

2026-07-28

## 背景

开发态 `runClientGameTest` 从 Gradle 编译输出目录加载生产类。它能证明真实
客户端输入、原版装置和 Pearl Relay 行为，却不能证明最终发布 JAR 本身可
运行。

Pearl Relay 的 `fabric.mod.json` 声明为服务端环境。生产客户端启动时，
Fabric Loader 会过滤这个 mod；而 Fabric Client GameTest 创建的专用服务端
与客户端处于同一 JVM，也不会再次执行服务端 mod 发现和入口点初始化。

测试代码必须继续留在独立 source set，不能为了生产测试而打入发布 JAR。

## 决策

- 使用 Loom `ClientProductionRunTask` 注册
  `runProductionClientGameTest`。
- 生产任务显式加载与项目版本一致的 Fabric API 和 Carpet。
- 构建独立的 `pearlrelay-gametest-<version>.jar`，只承载测试入口、夹具
  和断言。
- 把 `pearlrelay-<version>.jar` 放到生产启动类路径；测试专用桥接在进程内
  专用服务器启动前调用该 JAR 中的生产初始化器。
- 生产任务设置专用系统属性。测试在启动时检查 `PearlRelayMod` 的
  `CodeSource` 必须是普通 `pearlrelay-*.jar`，不能是目录、sources JAR 或
  gametest JAR。
- 每次运行使用全新的隔离目录，并只预写 `eula=true`；CI 环境使用 XVFB。
- CI 在测试前记录发布 JAR 的 SHA-256，测试后再次校验同一文件，并把提交
  SHA、JAR 名称、校验和、日志、截图和崩溃报告作为证据上传。

## 考虑过的替代方案

### 只运行开发态客户端 GameTest

拒绝。它不能发现发布打包、生产类路径或生产运行依赖问题。

### 把主代码复制进 gametest JAR

拒绝。它会形成第二份生产实现，被测代码不再等同于上传的发布产物。

### 把客户端测试代码打入发布 JAR

拒绝。测试入口和夹具会污染服务端发行包，也破坏现有发布隔离门槛。

### 让生产客户端把服务端 JAR 当作普通客户端 mod 加载

拒绝。项目有意声明为服务端环境；为测试改变生产环境元数据会扩大客户端
表面，而不是验证真实发布配置。

## 后果

- 开发态和生产态客户端任务使用同一组行为断言，但生产态主类只能来自实际
  发布 JAR。
- 测试支持 JAR 与发布 JAR 分离，发布 JAR 的测试代码隔离检查保持有效。
- 生产任务必须显式维护运行依赖和隔离目录 EULA。
- GitHub Actions 首次绿色运行后，证据产物可以用 `COMMIT_SHA` 和
  `SHA256SUMS` 同时关联到源提交及被测 JAR。
