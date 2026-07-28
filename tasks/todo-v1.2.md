# Pearl Relay v1.2.0 玩家快照保存清单

## 审批

- [x] 项目所有者确认玩家快照保存功能意图
- [x] 项目所有者审查并批准 `tasks/plan-v1.2.md`
- [x] 项目所有者确认配置损坏后的自动恢复策略

## Phase 1：配置可靠性

- [x] Task 1：使用同目录临时文件和原子替换保存配置
- [x] Task 1：保留一个最近成功版本且不备份半写内容
- [x] Task 1：注入写入、刷新、备份和替换失败
- [x] Task 2：对整个玩家配置执行严格校验
- [x] Task 2：保留损坏主文件并验证同 UUID 备份
- [x] Task 2：增加稳定配置错误和安全恢复
- [x] Checkpoint A：原子写入、恢复、UUID 隔离和 clean build 通过

## Phase 2：玩家快照保存

- [x] Task 3：让 `/pearlrelay save <名称>` 直接从玩家采集数据
- [x] Task 3：保存当前维度、脚部精确位置和视线命中点
- [x] Task 3：复用假人标准几何和现有目标解析器
- [x] Task 3：保留完整参数保存形式和 schema v2
- [x] Task 3：控制台、无目标和未加载路径准确拒绝
- [x] Task 4：命令级验证同名覆盖、双 UUID 和完整参数兼容
- [x] Task 4：保存时允许创建者占位
- [x] Task 4：触发时仍有人占位则在生成前拒绝
- [x] Task 4：玩家移开并准备珍珠后只触发一次

## Phase 3：客户端证据与压力验证

- [x] Task 5：真实客户端站位、转向并发送简写保存命令
- [x] Task 5：快照保存后完成真实装置传送和假人清理
- [x] Task 5：开发客户端三个场景各 20/20
- [x] Task 5：生产 JAR 客户端三个场景各 20/20
- [x] Task 5：上传日志、截图、提交 SHA 和候选 JAR SHA
- [x] Task 6：运行至少 1,000 次固定种子生命周期压力测试
- [x] Task 6：覆盖每个阶段异常和 shutdown/cleanup 竞态
- [x] Task 6：每轮保持一次使用、一个终态、零 active execution
- [x] Checkpoint B：功能、兼容、安全副作用和代码审查通过

## Phase 4：RC 与发布

- [x] Task 7：更新版本、README、CHANGELOG 和迁移说明
- [x] Task 7：创建并推送 annotated `v1.2.0-rc.1`
- [x] Task 7：发布 prerelease、JAR、SHA256SUMS 和验证报告
- [x] Task 7：只在隔离服验证配置恢复、重启和孤儿状态
- [x] Task 7：验证结束后保持生产服和隔离服停止
- [x] 项目所有者审查 RC 证据并批准稳定版
- [x] Task 8：在最终候选提交上重跑完整自动化
- [ ] Task 8：创建并推送 annotated `v1.2.0`
- [ ] Task 8：发布正式 GitHub Release 并复核资产 digest
- [ ] Task 8：仅在项目所有者明确授权后部署生产服务器

## 明确暂缓

- 后续独立计划：`/pearlrelay check <名称>`
- 后续独立计划：`/pearlrelay status` 与最近执行历史
- 后续独立计划：管理员诊断导出
- 后续独立计划：权限、冷却和批量触发

## 完成

- [x] 普通玩家无需手工输入维度或坐标
- [x] 快照保存与完整参数保存结果兼容
- [x] 配置失败不会丢失最近成功状态
- [x] 保存和触发失败都没有假人、交互或区块加载副作用
- [x] 发布 JAR 不包含测试代码或夹具
- [x] 提交、tag、Release、JAR 和服务器文件哈希一致
