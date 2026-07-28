# Pearl Relay 无玩家阶段硬化清单

## 批准

- [x] 项目所有者确认 `tasks/plan-headless-hardening.md`

## Phase 1：冻结与自动构建

- [x] Task 1：发布 `v1.1.0-rc.1` annotated tag
- [x] Task 1：发布 GitHub prerelease 与已验证 JAR
- [x] Task 1：从冻结基线创建 `headless-hardening`
- [ ] Task 2：增加 Java 25 GitHub Actions
- [ ] Task 2：上传 JAR、测试报告和 SHA-256
- [ ] Checkpoint A：全新 CI 环境可复现构建

## Phase 2：命令级无客户端验证

- [ ] Task 3：以模拟玩家执行完整命令树
- [ ] Task 3：验证双 UUID 配置、补全和触发隔离
- [ ] Task 3：验证消息、日志、实体和交互次数
- [ ] Task 4：自动断言拒绝不新增区块票据
- [ ] Task 4：覆盖不可实体 tick 邻区块和负坐标
- [ ] Checkpoint B：命令合同与无副作用通过

## Phase 3：数据和生命周期硬化

- [ ] Task 5：实现配置原子写入
- [ ] Task 5：实现最近成功版本备份与安全恢复
- [ ] Task 5：覆盖损坏 JSON 和写入失败
- [ ] Task 6：运行 1,000 次确定性生命周期压力测试
- [ ] Task 6：覆盖每个阶段异常、清理重试和 shutdown 竞态
- [ ] Task 6：隔离服重复启停后保持 0 个孤儿假人
- [ ] Checkpoint C：完成代码审查与下一 RC 决策

## 玩家恢复实机后的发布门槛

- [ ] 重新冻结最新 RC 提交和 JAR SHA-256
- [ ] 全部自动化与隔离服测试通过
- [ ] 完成现有 16 组玩家实机验收
- [ ] 创建 annotated `v1.1.0`
- [ ] 发布正式 GitHub Release 并复核下载产物 SHA-256
