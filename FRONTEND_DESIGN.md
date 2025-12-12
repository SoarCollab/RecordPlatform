# Svelte 5 + SvelteKit 前端详细设计方案

> 基于 FRONTEND_SCAFFOLD_PLAN.md 推荐方案的可行性分析与详细设计

---

## 1. 可行性分析

### 1.1 技术选型评估

| 维度 | 评估结果 | 说明 |
|------|---------|------|
| **Svelte 5 + SvelteKit 2** | ✅ 可行 | 编译时框架，~2KB 运行时，满意度最高 |
| **TailwindCSS 4** | ✅ 可行 | 原子化 CSS，按需生成 |
| **Shadcn-Svelte** | ✅ 可行 | Radix UI 移植版，高质量组件 |
| **原生 fetch** | ✅ 可行 | 零依赖，后端 API 规范统一 |
| **静态 SPA 部署** | ✅ 可行 | Nginx 直接托管，无需 Node.js |

### 1.2 后端集成兼容性

| 后端特性 | 前端适配方案 | 复杂度 |
|---------|-------------|--------|
| JWT 认证 (72h) | localStorage + 过期检查 | 低 |
| CORS 白名单 | 开发代理 + 生产配置 | 低 |
| 分布式限流 (50/3s) | 429 自动重试 + 指数退避 | 中 |
| ID 混淆 (UUID↔Snowflake) | 透明处理，无需前端关心 | 低 |
| SSE 实时推送 | **需后端修改**（见下文） | 中 |
| 分片上传/断点续传 | 完整实现 | 高 |
| 统一响应格式 Result<T> | 统一拦截处理 | 低 |

### 1.3 需要后端配合的改动

#### SSE 认证支持 (必须)

**问题**：EventSource API 不支持自定义 Header，但 SSE 端点需要 JWT 认证

**当前实现** (`JwtAuthenticationFilter.java:49`)：
```java
String authorization = request.getHeader("Authorization");
```

**需要修改**：支持从 URL 参数获取 token
```java
String authorization = request.getHeader("Authorization");
// SSE 场景：从 URL 参数获取 token
if (authorization == null || authorization.isEmpty()) {
    String tokenParam = request.getParameter("token");
    if (tokenParam != null && !tokenParam.isEmpty()) {
        authorization = "Bearer " + tokenParam;
    }
}
```

**文件路径**：`platform-backend/backend-web/src/main/java/cn/flying/filter/JwtAuthenticationFilter.java`

---

## 2. 项目结构设计

```
platform-frontend/
├── package.json                  # 项目配置
├── svelte.config.js              # SvelteKit 配置 (静态适配器)
├── vite.config.ts                # Vite 配置 (开发代理)
├── tailwind.config.ts            # TailwindCSS 4 配置
├── tsconfig.json                 # TypeScript 配置
├── components.json               # Shadcn-Svelte 配置
├── .env.example                  # 环境变量模板
├── static/
│   ├── favicon.ico
│   └── robots.txt
└── src/
    ├── app.html                  # HTML 模板
    ├── app.css                   # 全局样式 (Tailwind 导入)
    ├── app.d.ts                  # SvelteKit 类型增强
    │
    ├── lib/
    │   ├── index.ts              # $lib 入口
    │   │
    │   ├── api/                  # API 层
    │   │   ├── client.ts         # HTTP 客户端 (拦截器、重试、错误处理)
    │   │   ├── endpoints/        # 按模块划分的 API 调用
    │   │   │   ├── auth.ts       # /api/v1/auth/*
    │   │   │   ├── users.ts      # /api/v1/users/*
    │   │   │   ├── files.ts      # /api/v1/files/*
    │   │   │   ├── upload.ts     # /api/v1/files/upload/*
    │   │   │   ├── images.ts     # /api/v1/images/*
    │   │   │   ├── messages.ts   # /api/v1/messages/*
    │   │   │   ├── conversations.ts
    │   │   │   ├── announcements.ts
    │   │   │   ├── tickets.ts    # /api/v1/tickets/*
    │   │   │   ├── sse.ts        # /api/v1/sse/*
    │   │   │   └── system.ts     # /api/v1/system/*
    │   │   └── types/            # TypeScript 类型定义
    │   │       ├── index.ts      # 类型导出
    │   │       ├── common.ts     # Result<T>, Page<T>, 错误码
    │   │       ├── auth.ts       # AuthorizeVO, AccountVO
    │   │       ├── files.ts      # FileVO, StartUploadVO, ProgressVO
    │   │       ├── messages.ts   # MessageVO, ConversationVO
    │   │       ├── tickets.ts    # TicketVO, TicketReplyVO
    │   │       └── system.ts     # SysPermission, AuditLogVO
    │   │
    │   ├── stores/               # Svelte 5 Runes 状态管理
    │   │   ├── auth.svelte.ts    # 认证状态 ($state, $derived)
    │   │   ├── user.svelte.ts    # 用户信息
    │   │   ├── notifications.svelte.ts  # Toast 通知
    │   │   ├── sse.svelte.ts     # SSE 连接状态
    │   │   └── upload.svelte.ts  # 上传队列状态
    │   │
    │   ├── services/             # 业务服务层
    │   │   ├── auth.service.ts   # 登录/登出逻辑
    │   │   ├── sse.service.ts    # SSE 连接管理 (重连、事件分发)
    │   │   ├── upload.service.ts # 分片上传管理 (暂停/恢复/取消)
    │   │   └── download.service.ts # 文件下载
    │   │
    │   ├── components/
    │   │   ├── ui/               # Shadcn-Svelte 组件
    │   │   │   ├── button/
    │   │   │   ├── input/
    │   │   │   ├── card/
    │   │   │   ├── dialog/
    │   │   │   ├── table/
    │   │   │   ├── toast/
    │   │   │   └── ...
    │   │   ├── layout/           # 布局组件
    │   │   │   ├── Navbar.svelte
    │   │   │   ├── Sidebar.svelte
    │   │   │   └── Footer.svelte
    │   │   └── features/         # 业务组件
    │   │       ├── FileUploader.svelte     # 分片上传 + 进度
    │   │       ├── FileList.svelte         # 文件列表
    │   │       ├── ShareDialog.svelte      # 分享对话框
    │   │       ├── ConversationList.svelte
    │   │       ├── ChatWindow.svelte
    │   │       ├── TicketForm.svelte
    │   │       └── SSEStatus.svelte        # 连接状态指示
    │   │
    │   └── utils/
    │       ├── format.ts         # 日期、文件大小格式化
    │       ├── validation.ts     # 表单验证
    │       └── storage.ts        # localStorage 封装
    │
    └── routes/                   # SvelteKit 路由
        ├── +layout.svelte        # 根布局 (SSE Provider, Toast)
        ├── +layout.ts            # 根数据加载
        ├── +page.svelte          # 首页/落地页
        ├── +error.svelte         # 全局错误页
        │
        ├── (auth)/               # 公开认证路由 (无侧边栏)
        │   ├── +layout.svelte
        │   ├── login/+page.svelte
        │   ├── register/+page.svelte
        │   └── reset-password/+page.svelte
        │
        ├── (app)/                # 受保护的应用路由
        │   ├── +layout.svelte    # 应用 Shell (侧边栏, 认证守卫)
        │   ├── +layout.ts        # 认证检查
        │   ├── dashboard/+page.svelte
        │   ├── files/
        │   │   ├── +page.svelte  # 文件列表
        │   │   └── [hash]/+page.svelte  # 文件详情
        │   ├── upload/+page.svelte
        │   ├── share/[code]/+page.svelte
        │   ├── messages/
        │   │   ├── +page.svelte  # 会话列表
        │   │   └── [id]/+page.svelte  # 聊天窗口
        │   ├── announcements/
        │   │   ├── +page.svelte
        │   │   └── [id]/+page.svelte
        │   ├── tickets/
        │   │   ├── +page.svelte
        │   │   ├── new/+page.svelte
        │   │   └── [id]/+page.svelte
        │   └── settings/
        │       ├── +page.svelte
        │       └── security/+page.svelte
        │
        └── (admin)/              # 管理员路由
            ├── +layout.svelte    # 角色守卫
            ├── announcements/+page.svelte
            ├── tickets/+page.svelte
            ├── audit/+page.svelte
            └── permissions/+page.svelte
```

---

## 3. 核心模块设计

### 3.1 API 客户端 (`src/lib/api/client.ts`)

**核心特性**：
- 统一请求拦截 (添加 Authorization Header)
- 统一响应处理 (解析 Result<T> 格式)
- 错误码映射 (ResultEnum → 前端错误提示)
- 自动重试 (网络错误、429 限流)
- Token 过期自动跳转登录

```typescript
// 核心接口设计
interface ApiClient {
  get<T>(url: string, config?: RequestConfig): Promise<T>;
  post<T>(url: string, body?: unknown, config?: RequestConfig): Promise<T>;
  put<T>(url: string, body?: unknown, config?: RequestConfig): Promise<T>;
  delete<T>(url: string, config?: RequestConfig): Promise<T>;
  upload<T>(url: string, formData: FormData, config?: RequestConfig): Promise<T>;
}

// 错误类型
class ApiError extends Error {
  code: number;      // ResultEnum 错误码
  message: string;   // 错误消息
  isUnauthorized: boolean;  // 70001, 70004, 70006
  isRateLimited: boolean;   // 40004, 70005
}
```

**错误码处理映射** (基于 `ResultEnum.java`)：

| 错误码范围 | 类型 | 前端处理 |
|-----------|------|---------|
| 200 | 成功 | 返回 data |
| 10000-19999 | 参数错误 | Toast 提示 |
| 20000-29999 | 用户/认证错误 | Toast + 特殊处理 |
| 30000-39999 | 外部服务错误 | Toast + 可重试 |
| 40000-49999 | 系统错误 | Toast + 限流重试 |
| 50000-59999 | 数据错误 | Toast 提示 |
| 60000-69999 | 消息服务错误 | Toast 提示 |
| 70001, 70004, 70006 | 认证失效 | 清除 Token + 跳转登录 |
| 70002, 70005, 70007 | 权限/限制 | Toast 提示 |

### 3.2 认证状态管理 (`src/lib/stores/auth.svelte.ts`)

**Svelte 5 Runes 语法**：

```typescript
// 响应式状态
let user = $state<AccountVO | null>(null);
let isLoading = $state(false);
let error = $state<string | null>(null);

// 派生状态
let isAuthenticated = $derived(!!user && !!getToken());
let isAdmin = $derived(user?.role === 'admin');

// 导出 Hook
export function useAuth() {
  return {
    get user() { return user; },
    get isAuthenticated() { return isAuthenticated; },
    login, logout, register, fetchUser, ...
  };
}
```

### 3.3 SSE 服务 (`src/lib/services/sse.service.ts`)

**连接管理**：
```typescript
// 连接 (需后端支持 URL token 参数)
const url = `/record-platform/api/v1/sse/connect?token=${token}`;
const eventSource = new EventSource(url);

// 事件类型 (基于 SseEventType.java)
type SSEEventType =
  | 'notification'      // 系统通知
  | 'message-received'  // 新消息
  | 'file-processed';   // 文件处理完成

// 重连策略
const RECONNECT_BASE = 1000;      // 1秒
const RECONNECT_MAX = 30000;      // 30秒
const MAX_ATTEMPTS = 10;
```

### 3.4 分片上传服务 (`src/lib/services/upload.service.ts`)

**上传流程** (对应 `FileUploadController.java`)：

```
1. startUpload() → POST /upload/start
   参数: fileName, fileSize, contentType, chunkSize, totalChunks
   返回: clientId, processedChunks (已上传的分片)

2. uploadChunk() → POST /upload/chunk (循环)
   参数: clientId, chunkNumber, file (Blob)
   支持: 断点续传 (跳过已上传分片)

3. completeUpload() → POST /upload/complete
   参数: clientId
   触发: 后端加密、区块链存证、MinIO 存储

控制操作:
- pauseUpload()   → POST /upload/pause
- resumeUpload()  → POST /upload/resume (返回已上传分片列表)
- cancelUpload()  → POST /upload/cancel
- checkStatus()   → GET /upload/check
- getProgress()   → GET /upload/progress
```

**上传队列状态**：
```typescript
interface UploadTask {
  id: string;
  file: File;
  clientId: string | null;
  status: 'pending' | 'uploading' | 'paused' | 'completed' | 'failed' | 'cancelled';
  progress: number;           // 0-100
  uploadedChunks: number[];   // 已上传分片索引
  totalChunks: number;
  speed: number;              // 字节/秒
  error: string | null;
}
```

---

## 4. 路由与页面设计

### 4.1 路由映射表

| 路由 | 页面 | 后端 API | 权限 |
|------|------|---------|------|
| `/` | 落地页 | - | 公开 |
| `/login` | 登录 | `POST /auth/login` | 公开 |
| `/register` | 注册 | `POST /auth/register` | 公开 |
| `/reset-password` | 重置密码 | `POST /auth/reset-*` | 公开 |
| `/dashboard` | 仪表盘 | 多个统计 API | 登录 |
| `/files` | 文件列表 | `GET /files/page` | 登录 |
| `/files/[hash]` | 文件详情 | `GET /files/address` | 登录 |
| `/upload` | 上传 | `/files/upload/*` | 登录 |
| `/share/[code]` | 分享查看 | `GET /files/getSharingFiles` | 公开 |
| `/messages` | 会话列表 | `GET /conversations` | 登录 |
| `/messages/[id]` | 聊天 | `GET /conversations/{id}` | 登录 |
| `/announcements` | 公告列表 | `GET /announcements` | 登录 |
| `/tickets` | 工单列表 | `GET /tickets` | 登录 |
| `/tickets/new` | 创建工单 | `POST /tickets` | 登录 |
| `/settings` | 设置 | `GET /users/info` | 登录 |
| `/admin/audit` | 审计日志 | `GET /system/audit/*` | Admin/Monitor |
| `/admin/permissions` | 权限管理 | `GET /system/permissions/*` | Admin |

### 4.2 布局层级

```
Root Layout (+layout.svelte)
├── SSE Provider (全局连接)
├── Toast Provider (通知)
└── Theme Provider (主题)

Auth Layout ((auth)/+layout.svelte)
└── 最小化布局，无侧边栏

App Layout ((app)/+layout.svelte)
├── Navbar (顶部导航)
│   ├── Logo
│   ├── SSE 状态指示
│   ├── 通知徽章
│   └── 用户下拉菜单
├── Sidebar (侧边栏，可折叠)
│   ├── 文件管理
│   ├── 消息中心
│   ├── 工单系统
│   └── 管理功能 (角色显示)
└── Main Content

Admin Layout ((admin)/+layout.svelte)
└── 角色守卫 + 管理专用导航
```

---

## 5. 配置与部署

### 5.1 SvelteKit 配置 (静态 SPA)

```javascript
// svelte.config.js
import adapter from '@sveltejs/adapter-static';

export default {
  kit: {
    adapter: adapter({
      pages: 'dist',
      assets: 'dist',
      fallback: 'index.html',  // SPA 路由
      precompress: true,        // 预压缩 .gz/.br
    }),
  },
};
```

### 5.2 Vite 开发代理

```typescript
// vite.config.ts
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/record-platform': {
        target: 'http://localhost:8000',
        changeOrigin: true,
      },
    },
  },
});
```

### 5.3 Nginx 生产配置

```nginx
server {
    listen 443 ssl http2;
    server_name example.com;

    root /var/www/platform-frontend/dist;
    index index.html;

    # SPA 路由
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 静态资源缓存
    location ~* \.(js|css|png|jpg|ico|svg|woff2?)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # API 反向代理
    location /record-platform/api {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        # SSE 支持
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400s;
    }
}
```

---

## 6. 类型定义摘要

### 6.1 通用类型

```typescript
// Result<T> - 后端统一响应格式
interface Result<T> {
  code: number;
  message: string;
  data: T;
}

// Page<T> - 分页响应
interface Page<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}
```

### 6.2 关键业务类型

```typescript
// 认证
interface AuthorizeVO {
  username: string;
  role: string;
  token: string;
  expire: string;  // ISO 时间
}

// 文件
interface FileVO {
  id: string;
  fileName: string;
  fileHash: string;
  transactionHash?: string;
  status: number;
  createTime: string;
}

// 上传
interface StartUploadVO {
  clientId: string;
  chunkSize: number;
  totalChunks: number;
  processedChunks: number[];  // 已上传分片 (断点续传)
  resumed: boolean;
}

// 消息
interface ConversationVO {
  id: string;
  otherUsername: string;
  lastMessageContent: string;
  unreadCount: number;
}

// 工单
interface TicketVO {
  id: string;
  ticketNo: string;
  title: string;
  priority: number;
  status: number;
  replyCount: number;
}
```

---

## 7. 开发工作流

### 7.1 初始化命令

```bash
# 创建项目
npx sv create platform-frontend
# 选择: SvelteKit minimal, TypeScript, TailwindCSS

cd platform-frontend

# 安装 Shadcn-Svelte
npx shadcn-svelte@latest init
npx shadcn-svelte@latest add button card input table dialog toast

# 安装 OpenAPI 类型生成
npm install -D openapi-typescript
```

### 7.2 开发命令

```bash
npm run dev          # 启动开发服务器 (5173)
npm run build        # 构建生产版本
npm run preview      # 预览生产构建
npm run check        # TypeScript 类型检查
npm run types:gen    # 从 OpenAPI 生成类型
```

### 7.3 类型生成

```bash
# package.json scripts
"types:gen": "openapi-typescript http://localhost:8000/record-platform/v3/api-docs -o src/lib/api/types/generated.ts"
```

---

## 8. 打包体积预估

| 组件 | 大小 (gzip) |
|------|-------------|
| Svelte 运行时 | ~2 KB |
| SvelteKit 路由 | ~3 KB |
| TailwindCSS (按需) | ~10-15 KB |
| Shadcn-Svelte 组件 | ~5-10 KB |
| 业务代码 | ~15-25 KB |
| **总计** | **~35-55 KB** |

对比：React 项目通常 150KB+

---

## 9. 后端改动清单

### 必须修改

| 文件 | 改动 | 原因 |
|------|------|------|
| `JwtAuthenticationFilter.java` | 支持 URL token 参数 | SSE EventSource 不支持自定义 Header |

### 建议修改

| 文件 | 改动 | 原因 |
|------|------|------|
| `application.yml` CORS | 添加 `http://localhost:5173` | Vite 默认端口 |

---

## 10. 关键文件引用

后端集成时需要参考的文件：

- `platform-backend/backend-common/src/main/java/cn/flying/common/constant/ResultEnum.java` - 错误码定义
- `platform-backend/backend-web/src/main/java/cn/flying/controller/FileUploadController.java` - 上传 API
- `platform-backend/backend-web/src/main/java/cn/flying/controller/SseController.java` - SSE 端点
- `platform-backend/backend-web/src/main/java/cn/flying/filter/JwtAuthenticationFilter.java` - 认证过滤器
- `platform-backend/backend-web/src/main/resources/application.yml` - CORS、限流配置
