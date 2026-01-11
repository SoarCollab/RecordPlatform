# RecordPlatform

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green.svg)](https://spring.io/projects/spring-boot)

**Enterprise-grade file attestation platform** powered by blockchain and distributed storage.

[中文文档](README_CN.md)

---

## Features

- **Blockchain Attestation** - File metadata stored on FISCO BCOS, ensuring immutability and traceability
- **Distributed Storage** - Multi-replica with fault domain isolation, consistent hashing, quorum-based writes, degraded write support, standby pool auto-promotion, and N-1 fault tolerance
- **Chunked Upload** - Resumable uploads with AES-GCM/ChaCha20-Poly1305 encryption
- **File Sharing** - Generate share codes with access limits and expiration
- **Share Audit & Provenance** - Track access, downloads, and saves with multi-level provenance chain (A→B→C), full share access logging
- **Real-time Notifications** - SSE push for file status changes and messages, multi-device support
- **RBAC Permissions** - Fine-grained access control with resource ownership verification
- **Multi-tenancy** - Database, cache, and storage path isolation per tenant
- **Support Tickets** - Built-in ticket system with categories, priorities, and admin management
- **Friend System** - Direct file sharing with friends, friend requests with real-time SSE notifications

## Quick Start

### 1. Prerequisites

Ensure the following services are running:

| Service               | Port  | Purpose                    |
| --------------------- | ----- | -------------------------- |
| Nacos                 | 8848  | Service discovery & config |
| MySQL                 | 3306  | Database                   |
| Redis                 | 6379  | Cache & distributed locks  |
| RabbitMQ              | 5672  | Message queue              |
| S3-compatible storage | 9000  | Object storage             |
| FISCO BCOS            | 20200 | Blockchain node            |

Optional but recommended: copy `.env.example` to `.env` and set at least `JWT_KEY`, `S3_*`, and (for dev) `KNIFE4J_*` before starting services.

### 2. Build

```bash
# Install shared interfaces (required first)
mvn -f platform-api/pom.xml clean install

# Build all modules
mvn -f platform-backend/pom.xml clean package -DskipTests
mvn -f platform-fisco/pom.xml clean package -DskipTests
mvn -f platform-storage/pom.xml clean package -DskipTests
```

### 2.5 Test

See `TESTING.md` for the recommended test matrix (unit + integration).

### 3. Run

```bash
# Start services in order (providers before consumer)
java -jar "$(ls platform-storage/target/platform-storage-*.jar | head -n 1)" --spring.profiles.active=local
java -jar "$(ls platform-fisco/target/platform-fisco-*.jar | head -n 1)" --spring.profiles.active=local
java -jar "$(ls platform-backend/backend-web/target/backend-web-*.jar | head -n 1)" --spring.profiles.active=local
```

> For detailed setup, see [Getting Started Guide](docs/en/getting-started/index.md)

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       Infrastructure                            │
│  Nacos    MySQL    RabbitMQ    Redis    S3 Storage Cluster     │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              │         platform-api          │
              │    (Shared Dubbo Interfaces)  │
              └───────────────┬───────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         │                    │                    │
         ▼                    │                    ▼
┌─────────────────┐           │           ┌─────────────────┐
│ platform-fisco  │           │           │ platform-storage│
│ Blockchain Svc  │           │           │ Storage Service │
│ (Port 8091)     │           │           │ (Port 8092)     │
└────────┬────────┘           │           └────────┬────────┘
         │         Dubbo RPC  │  Dubbo RPC         │
         └────────────────────┼────────────────────┘
                              ▼
                    ┌─────────────────┐
                    │ platform-backend│
                    │ REST API :8000  │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ FISCO BCOS Node │
                    └─────────────────┘
```

> For detailed architecture, see [Architecture Guide](docs/en/architecture/index.md)

## Documentation

| Topic                                               | Description                                                |
| --------------------------------------------------- | ---------------------------------------------------------- |
| [Getting Started](docs/en/getting-started/index.md) | Prerequisites, installation, configuration                 |
| [Architecture](docs/en/architecture/index.md)       | System overview, distributed storage, blockchain, security |
| [Deployment](docs/en/deployment/index.md)           | Docker, production setup, monitoring                       |
| [Troubleshooting](docs/en/troubleshooting/index.md) | Common issues and solutions                                |
| [API Reference](docs/en/api/index.md)               | REST API endpoints                                         |

## Tech Stack

| Category      | Technology                        | Version                  |
| ------------- | --------------------------------- | ------------------------ |
| Backend       | Java, Spring Boot                 | 21, 3.2.11               |
| Microservices | Apache Dubbo (Triple), Nacos      | 3.3.3                    |
| Blockchain    | FISCO BCOS, Solidity              | 3.8.0, ^0.8.11           |
| Storage       | S3-compatible, MySQL, Redis       | AWS SDK 2.29, 8.0+, 7.0+ |
| Frontend      | Svelte 5, SvelteKit, Tailwind CSS, Vite | 5.46+, 2.49+, 4.1+, 6.0+ |
| Resilience    | Resilience4j                      | 2.2.0                    |
| Monitoring    | Micrometer, Prometheus            | -                        |

## Project Structure

```
RecordPlatform/
├── platform-api/          # Shared Dubbo interface definitions
├── platform-backend/      # Backend service (Dubbo Consumer, REST API)
│   ├── backend-web/       # Controllers, filters, security
│   ├── backend-service/   # Business logic, Saga, Outbox
│   ├── backend-dao/       # MyBatis Plus mappers, entities
│   ├── backend-api/       # Internal API interfaces
│   └── backend-common/    # Utilities, constants
├── platform-fisco/        # Blockchain service (Dubbo Provider)
├── platform-storage/      # Storage service (Dubbo Provider)
│   ├── config/            # Node & fault domain configuration
│   ├── core/              # Consistent hashing, fault domain manager
│   ├── service/           # Storage, rebalance, standby pool
│   ├── event/             # Topology change events
│   ├── health/            # Health check endpoints
│   └── tenant/            # Multi-tenant support
├── platform-frontend/     # Svelte 5 + SvelteKit frontend
├── scripts/               # Deployment scripts
└── docs/                  # Documentation (en/zh)
```

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'feat: add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
