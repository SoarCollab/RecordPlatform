# 贡献指南

## 开始贡献

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feat/your-feature`
3. 遵循代码规范进行修改
4. 确保测试通过：`mvn -f platform-backend/pom.xml test`
5. 向 `main` 分支发起 Pull Request

## 分支命名规范

| 前缀 | 用途 |
|------|------|
| `feat/` | 新功能 |
| `fix/` | Bug 修复 |
| `refactor/` | 重构（不改变行为） |
| `docs/` | 文档更新 |
| `chore/` | 构建、依赖等维护性变更 |

## 提交信息规范

格式：`<type>: <subject>`（英文，~80 字符）

```bash
# 正确
feat: add file version chain support
fix: resolve chunk decryption order issue
docs: add distributed storage architecture page

# 错误
feat: add feature          # 描述不够清晰
fix: some bug fixes        # 过于模糊
```

## CI 门禁

所有 PR 必须通过以下检查才能合并：

| 检查项 | 说明 |
|--------|------|
| **Backend Tests** | 单元测试 + 集成测试，含覆盖率阈值 |
| **Frontend Tests** | lint + type check + vitest 覆盖率 |
| **Contract Consistency** | OpenAPI ↔ `generated.ts` 无差异 |
| **Build Verification** | 后端构建 + 前端 `pnpm build` |

### 修改 REST 接口后必须更新契约

```bash
# 选项 1：后端本地运行时
cd platform-frontend && pnpm types:gen

# 选项 2：使用导出的 openapi.json
cd platform-frontend && OPENAPI_SOURCE=path/to/openapi.json pnpm types:gen
```

更新后提交 `platform-frontend/src/lib/api/types/generated.ts`。

## 覆盖率要求

**后端 JaCoCo：**

| 模块 | 最低行覆盖率 |
|------|------------|
| backend-web | 40% |
| backend-service | 45% |
| backend-common | 40% |

**前端 Vitest：**

| 路径 | 行/函数/分支/语句 |
|------|----------------|
| `src/lib/utils/**` | 70% / 70% / 60% / 70% |
| `src/lib/api/endpoints/**` | 90% / 90% / 85% / 90% |
| `src/lib/stores/**` | 90% / 90% / 80% / 90% |
| `src/lib/services/**` | 90% / 90% / 85% / 90% |

## 代码规范

- **DI 方式**：优先 `@RequiredArgsConstructor`；禁止生产代码使用 `@Autowired`
- **DTO/VO**：优先使用 Java Records
- **REST 路径**：kebab-case（`/upload-sessions`，非 `/uploadSessions`）
- **Controller**：必须加 `@OperationLog(module, operationType, description)`
- **SQL 参数**：MyBatis 使用 `#{}`，禁止 `${}` 接收用户输入
- **前端**：使用 Svelte 5 Runes，禁止 Svelte 4 stores
- **Lombok**：仅用 `@Slf4j`、`@Data`、`@Builder`、`@RequiredArgsConstructor`
