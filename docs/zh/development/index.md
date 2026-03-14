# 开发指南

本节面向希望为 RecordPlatform 贡献代码或在本地进行开发的工程师。

## 目录

- [本地开发环境](local-dev) — 搭建完整的本地开发栈
- [贡献指南](contributing) — 分支规范、PR 流程、CI 门禁
- [测试策略](testing) — 测试分层、覆盖率要求、运行命令

## 技术决策速查

| 问题 | 答案 |
|------|------|
| 依赖注入方式 | 优先 `@RequiredArgsConstructor`，禁止生产代码使用 `@Autowired` |
| DTO/VO 风格 | 优先使用 Java Records |
| REST 路径风格 | kebab-case（如 `/upload-sessions`，非 `/uploadSessions`） |
| 业务异常处理 | `GeneralException(ResultEnum)` 标准业务异常 |
| 跨租户操作 | `@TenantScope(ignoreIsolation = true)` |
| 前端状态管理 | Svelte 5 Runes（`$state`、`$derived`、`$effect`）；禁止 Svelte 4 stores |
| 审计追踪 | Controller 必须加 `@OperationLog(module, operationType, description)` |
| SQL 参数 | MyBatis 使用 `#{}`；禁止 `${}` 接收用户输入 |
