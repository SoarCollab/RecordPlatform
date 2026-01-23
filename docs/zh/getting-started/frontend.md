# 前端开发指南

本指南介绍 RecordPlatform 前端应用的开发设置。

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | Svelte 5 + SvelteKit 2 | 5.46+ / 2.49+ |
| 语言 | TypeScript | 5.9+ |
| 样式 | Tailwind CSS | 4.1+ |
| 构建工具 | Vite | 6.0+ |
| 包管理器 | pnpm | 10.26+ |
| UI 组件 | Bits UI + Lucide Icons | - |
| 图表库 | ECharts | 6.0+ |

## 快速开始

### 前置条件

- Node.js 18+
- pnpm 10+

### 开发环境设置

```bash
cd platform-frontend

# 安装依赖
pnpm install

# 启动开发服务器
pnpm dev
```

开发服务器运行在 `http://localhost:5173`。

### 可用脚本

| 命令 | 说明 |
|------|------|
| `pnpm dev` | 启动开发服务器 |
| `pnpm build` | 构建生产版本 |
| `pnpm preview` | 预览生产构建 |
| `pnpm check` | TypeScript 类型检查 |
| `pnpm lint` | 运行 ESLint |
| `pnpm format` | 使用 Prettier 格式化代码 |
| `pnpm types:gen` | 从 OpenAPI 生成 API 类型 |

## 环境变量

在 `platform-frontend/` 目录下创建 `.env` 文件：

| 变量 | 说明 | 示例 |
|------|------|------|
| `PUBLIC_API_BASE_URL` | 后端 API 地址 | `http://localhost:8000/record-platform` |
| `PUBLIC_ENV` | 环境名称 | `development` |
| `PUBLIC_TENANT_ID` | 默认租户 ID | `1` |

## 项目结构

```
platform-frontend/
├── src/
│   ├── routes/              # SvelteKit 页面
│   │   ├── (app)/           # 需认证的路由
│   │   │   ├── dashboard/   # 仪表盘页面
│   │   │   ├── files/       # 文件管理
│   │   │   └── admin/       # 管理员页面
│   │   ├── (auth)/          # 认证相关路由
│   │   │   ├── login/
│   │   │   └── register/
│   │   └── share/           # 公开分享页面
│   ├── lib/
│   │   ├── api/             # API 客户端
│   │   │   ├── client.ts    # HTTP 客户端封装
│   │   │   ├── endpoints/   # API 端点函数
│   │   │   └── types/       # TypeScript 类型
│   │   ├── components/      # 可复用组件
│   │   │   └── ui/          # 基础 UI 组件
│   │   ├── stores/          # Svelte 5 runes 状态管理
│   │   └── utils/           # 工具函数
│   ├── app.css              # 全局样式
│   ├── app.html             # HTML 模板
│   └── app.d.ts             # 全局类型声明
├── static/                  # 静态资源
└── svelte.config.js         # SvelteKit 配置
```

## 核心 Stores

应用使用基于 Svelte 5 runes 的状态管理：

| Store | 文件 | 用途 |
|-------|------|------|
| Auth | `auth.svelte.ts` | 用户认证状态、JWT 管理 |
| SSE | `sse.svelte.ts` | 服务器推送事件连接 |
| SSE Leader | `sse-leader.svelte.ts` | 多标签页 Leader 选举 |
| Upload | `upload.svelte.ts` | 文件上传队列（支持分块）|
| Download | `download.svelte.ts` | 文件下载管理器 |
| Notifications | `notifications.svelte.ts` | Toast 通知 |
| Badges | `badges.svelte.ts` | UI 徽章计数 |

### Store 使用示例

```svelte
<script>
  import { auth } from '$lib/stores/auth.svelte';

  // 使用 Svelte 5 runes 进行响应式访问
  const user = $derived(auth.user);
  const isAuthenticated = $derived(auth.isAuthenticated);
</script>

{#if isAuthenticated}
  <p>欢迎，{user?.username}</p>
{/if}
```

## API 客户端

### 类型生成

从后端 OpenAPI 规范生成 TypeScript 类型：

```bash
# 确保后端运行在 localhost:8000
pnpm types:gen
```

这会生成 `src/lib/api/types/generated.ts`，包含完整的 API 类型。

### 调用 API

```typescript
import { filesApi } from '$lib/api/endpoints/files';

// 获取用户文件列表
const files = await filesApi.list({ page: 1, size: 20 });

// 上传文件
const result = await filesApi.upload(file, {
  onProgress: (progress) => console.log(`${progress}%`)
});
```

## 分块文件上传

上传系统使用动态分块大小：

| 文件大小 | 分块大小 |
|----------|----------|
| < 10MB   | 2MB      |
| < 100MB  | 5MB      |
| < 500MB  | 10MB     |
| < 2GB    | 20MB     |
| >= 2GB   | 50MB     |

上传流程：
1. 计算最优分块大小
2. 启动上传会话（`/file/upload/start`）
3. 上传分块并跟踪进度
4. 合并分块（`/file/upload/merge`）

## 生产构建

```bash
# 构建静态站点
pnpm build

# 本地预览构建结果
pnpm preview
```

构建输出在 `build/` 目录，可部署到静态托管服务。

## 代码风格

- 使用 TypeScript 严格模式
- 遵循现有组件模式
- 使用 Tailwind CSS 进行样式设计
- 保持组件小而专注
- 使用 stores 管理共享状态

### 组件模板

```svelte
<script lang="ts">
  import { type ComponentProps } from 'svelte';

  interface Props {
    title: string;
    variant?: 'default' | 'primary';
  }

  let { title, variant = 'default' }: Props = $props();
</script>

<div class="component {variant}">
  <h2>{title}</h2>
  {@render children?.()}
</div>
```
