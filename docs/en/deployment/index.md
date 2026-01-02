# Deployment

Deployment and operations guide for RecordPlatform.

## Contents

- [Docker Compose](docker-compose) - Container-based deployment
- [Production](production.md) - Production environment setup
- [Monitoring](monitoring) - Metrics, alerts, and health checks

## Deployment Options

| Option | Use Case | Complexity |
|--------|----------|------------|
| Manual JAR | Development, testing | Low |
| Docker Compose | Single-host production | Medium |
| Kubernetes | Multi-node production | High |

## Quick Reference

### Service Ports

| Service | Port | Protocol |
|---------|------|----------|
| platform-backend | 8000 | HTTP/REST |
| platform-fisco | 8091 | Dubbo Triple |
| platform-storage | 8092 | Dubbo Triple |
| platform-frontend | 5173 (dev) / 80 (prod) | HTTP |

### Startup Order

```
1. Infrastructure (Nacos, MySQL, Redis, RabbitMQ, S3, FISCO)
2. platform-storage (Dubbo Provider)
3. platform-fisco (Dubbo Provider)
4. platform-backend (Dubbo Consumer)
5. platform-frontend (optional, can deploy separately)
```

### Profiles

| Profile | Use Case | Features |
|---------|----------|----------|
| `local` | Local development | Debug logging, Swagger enabled |
| `dev` | Development server | Partial logging |
| `prod` | Production | Swagger disabled, SSL required |

