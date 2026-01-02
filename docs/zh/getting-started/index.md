# 快速开始

本指南将帮助您在本地搭建和运行 RecordPlatform。

## 目录

- [前置依赖](prerequisites) - 必需的服务和依赖
- [安装部署](installation) - 构建和运行平台
- [配置说明](configuration) - 环境变量和设置

## 快速概览

RecordPlatform 由四个主要组件组成：

| 组件 | 类型 | 端口 | 说明 |
|------|------|------|------|
| platform-storage | Dubbo Provider | 8092 | 分布式存储服务 |
| platform-fisco | Dubbo Provider | 8091 | 区块链集成服务 |
| platform-backend | Dubbo Consumer | 8000 | REST API 网关 |
| platform-frontend | SvelteKit | 5173 | Web 应用 |

## 启动顺序

由于 Dubbo RPC 依赖关系，服务必须按以下顺序启动：

```
1. platform-storage  →  2. platform-fisco  →  3. platform-backend  →  4. platform-frontend
```

## 下一步

1. 查看 [前置依赖](prerequisites) 确保所有依赖已就绪
2. 按照 [安装部署](installation) 构建和运行
3. 查阅 [配置说明](configuration) 进行环境配置

