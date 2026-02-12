## 变更摘要

- 说明本次变更目标与范围（模块、接口、行为变化）。

## 风险评估

- [ ] 已评估兼容性风险（接口/数据库/配置）。
- [ ] 已说明回滚方案或降级方案。

## 测试与验证

- [ ] 后端测试：`mvn -f platform-backend/pom.xml test -pl backend-service,backend-web -am`
- [ ] 前端质量：`pnpm --dir platform-frontend lint && pnpm --dir platform-frontend check`
- [ ] 前端测试：`pnpm --dir platform-frontend test`

## 契约一致性（必填）

- [ ] 本次是否涉及 OpenAPI 变更：`是 / 否`
- [ ] 已执行：`OPENAPI_SOURCE=../backend-openapi/openapi.json pnpm --dir platform-frontend types:gen`
- [ ] `platform-frontend/src/lib/api/types/generated.ts` 已同步提交（如有差异）

## 证据与附录

- CI 结果链接：
- 文档更新路径（如有）：
- 截图/日志（如有）：
