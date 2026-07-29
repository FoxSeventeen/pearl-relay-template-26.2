# Pearl Relay v1.2.1 热修复清单

## 生产故障与修复

- [x] 只读保留生产主配置、`.bak`、`.corrupt-1` 和 `.corrupt-2` 的哈希与时间线
- [x] 确认玩家 UUID 未变化，旧点和两次失败保存均未丢失
- [x] 用两个旧点加一个新点复现 `CONFIG_RECOVERED_RETRY`
- [x] 用多个旧点逐个重存复现相同恢复循环
- [x] 允许已升级点与待升级旧点在 schema v2 文件中共存
- [x] 保持缺少目标指纹的旧点返回 `RELAY_REQUIRES_RESAVE`
- [x] 保持无效 JSON、schema、坐标、维度、假人名和目标指纹整文件拒绝

## 自动化门槛

- [x] Java 25 clean build
- [x] JUnit 59/59
- [x] Fabric 服务端 GameTest 26/26
- [x] 开发客户端三个场景各 20/20
- [x] 生产发布 JAR 客户端三个场景各 20/20
- [x] 生产主类 `CodeSource` 来自普通发布 JAR
- [x] JAR 元数据、归档内容、SHA-256 和 crash report 检查
- [x] GitHub Actions 分支、tag 和 main 门槛

## 发布与部署

- [x] 版本升级为 `1.2.1`
- [x] 更新 README、CHANGELOG 和迁移说明
- [x] 五轴代码审查通过
- [x] 合入 `main`
- [x] 创建 annotated `v1.2.1` tag
- [x] 创建正式 GitHub Release、JAR 和 `SHA256SUMS`
- [x] 停服前确认在线玩家并执行 `save-all flush`
- [x] 备份当前生产 JAR 和四份配置证据
- [x] 部署与 Release 资产字节一致的 v1.2.1 JAR
- [x] 验证启动、端口、版本、命令、配置不变和无新增异常

## 数据边界

- [x] 不自动选择或合并 v1.2.0 留下的 `.corrupt-N`
- [x] 不修改世界、玩家数据、服务端属性、Carpet 配置或其他模组
- [x] 玩家实机装置触发继续按已批准边界暂缓
