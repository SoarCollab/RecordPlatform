<div align="center">

# RecordPlatform

**Enterprise-grade file attestation platform powered by blockchain and distributed storage**

[![Build](https://github.com/SoarCollab/RecordPlatform/actions/workflows/test.yml/badge.svg)](https://github.com/SoarCollab/RecordPlatform/actions/workflows/test.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Svelte](https://img.shields.io/badge/Svelte-5-FF3E00?logo=svelte&logoColor=white)](https://svelte.dev)

[中文文档](README_CN.md) · [Documentation](https://soarcollab.github.io/RecordPlatform/) · [API Reference](docs/en/api/index.md)

</div>

---

## What is RecordPlatform?

RecordPlatform is an open-source, enterprise-grade file attestation platform that combines **blockchain immutability** with **fault-domain-aware distributed storage**. Upload files, have their metadata recorded on-chain via FISCO BCOS, and share them securely — with cryptographic proof of origin, integrity, and full access audit.

Built for teams that need:
- 📜 **Auditable provenance** — every upload, share, and download tracked and verifiable on-chain
- 🏢 **Multi-tenant isolation** — separate storage, cache, and data paths per tenant
- 🔒 **End-to-end encryption** — AES-GCM/ChaCha20-Poly1305 with per-chunk key chains

---

## ✨ Features

<table>
<tr>
<td width="50%" valign="top">

### 🔐 Attestation & Security
- **Blockchain Attestation** — file metadata stored on FISCO BCOS, immutable and traceable
- **File Encryption** — AES-GCM / ChaCha20-Poly1305, per-chunk independent key chains
- **RBAC + Ownership** — fine-grained resource-level access control
- **ID Obfuscation** — AES-256-CTR external↔internal ID mapping

</td>
<td width="50%" valign="top">

### 📦 Storage & Transfer
- **Distributed Storage** — 1~N active fault domains, quorum writes, N-1 fault tolerance, auto-promotion from standby
- **Chunked Upload** — resumable, concurrent, dynamic chunk sizing
- **Streaming Download** — StreamSaver.js for large files; auto strategy selection
- **File Version Chain** — track history, derive new versions from existing files

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 👥 Collaboration & Sharing
- **File Sharing** — generate share codes with access limits, expiry, and password protection
- **Share Audit & Provenance** — multi-level chain tracking (A→B→C), full access logs
- **Friend System** — direct file sharing with friends, real-time SSE notifications
- **Support Tickets** — built-in ticket system with categories, priorities, admin management

</td>
<td width="50%" valign="top">

### 📊 Governance & Observability
- **Quota Governance** — per-user and per-tenant limits, SHADOW/ENFORCE modes, gradual rollout
- **Real-time Notifications** — SSE push for file attestation, tickets, friend events, announcements
- **Storage Capacity API** — cluster/domain/node capacity aggregates with `degraded`+`source` semantics
- **Multi-tenancy** — DB, cache, and storage path isolation per tenant

</td>
</tr>
</table>

---

## 🏗 Architecture

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
         │                    ▼                    │
         │          ┌─────────────────┐            │
         │          │ platform-backend│            │
         │          │ REST API :8000  │            │
         │          └─────────────────┘            │
         │                                         │
         ▼                                         ▼
  ┌─────────────┐                        ┌────────────────┐
  │ FISCO BCOS  │                        │   S3 Cluster   │
  │    Node     │                        │ (MinIO / S3)   │
  └─────────────┘                        └────────────────┘
```

> For detailed architecture with Mermaid diagrams and data flow sequences, see [Architecture Guide](docs/en/architecture/index.md)

---

## ⚡ Quick Start

### 1. Prerequisites

Ensure the following services are running before starting:

| Service               | Port  | Purpose                    |
| --------------------- | ----- | -------------------------- |
| Nacos                 | 8848  | Service discovery & config |
| MySQL                 | 3306  | Database                   |
| Redis                 | 6379  | Cache & distributed locks  |
| RabbitMQ              | 5672  | Message queue              |
| S3-compatible storage | 9000  | Object storage             |
| FISCO BCOS            | 20200 | Blockchain node            |

Start infrastructure with Docker Compose:

```bash
docker compose -f docker-compose.infra.yml up -d
```

Copy `.env.example` to `.env` and configure `JWT_KEY`, `S3_*`, and `FISCO_*` before starting services.

### 2. Build

```bash
# Install shared interfaces (required first)
mvn -f platform-api/pom.xml clean install

# Build all backend services
mvn -f platform-backend/pom.xml clean package -DskipTests
mvn -f platform-fisco/pom.xml clean package -DskipTests
mvn -f platform-storage/pom.xml clean package -DskipTests
```

### 3. Run

```bash
# Start in order: providers before consumer
java -jar "$(ls platform-storage/target/platform-storage-*.jar)" --spring.profiles.active=local
java -jar "$(ls platform-fisco/target/platform-fisco-*.jar)" --spring.profiles.active=local
java -jar "$(ls platform-backend/backend-web/target/backend-web-*.jar)" --spring.profiles.active=local

# Frontend dev server
cd platform-frontend && pnpm install && pnpm dev
```

Or use the unified start script:

```bash
./scripts/start.sh start all
```

Verify the installation at:
- **Swagger UI**: http://localhost:8000/record-platform/swagger-ui.html
- **Health check**: http://localhost:8000/record-platform/actuator/health
- **Frontend**: http://localhost:5173

> For detailed setup, see [Getting Started Guide](docs/en/getting-started/index.md)

---

## 🧱 Tech Stack

| Category      | Technology                                              | Version               |
| ------------- | ------------------------------------------------------- | --------------------- |
| Backend       | Java + Spring Boot + Virtual Threads                    | 21, 3.5.11            |
| Microservices | Apache Dubbo (Triple protocol), Nacos                   | 3.3.6                 |
| Blockchain    | FISCO BCOS, Solidity                                    | 3.8.0, ^0.8.11        |
| Storage       | S3-compatible (AWS SDK v2), MySQL, Redis (Redisson)     | 2.x, 8.0+, 7.0+       |
| Frontend      | Svelte 5 (Runes), SvelteKit, Tailwind CSS 4, bits-ui   | 5.53+, 2.53+, 4.2+    |
| Resilience    | Resilience4j (circuit breaker, retry)                   | 2.3.0                 |
| Monitoring    | Micrometer, Prometheus                                  | —                     |
| Testing       | JUnit 5, Mockito, Testcontainers, Vitest                | —                     |

---

## 📚 Documentation

| Guide | Description |
| ----- | ----------- |
| [Getting Started](docs/en/getting-started/index.md) | Prerequisites, installation, configuration |
| [Architecture](docs/en/architecture/index.md) | System overview, distributed storage, blockchain, security |
| [Deployment](docs/en/deployment/index.md) | Docker Compose, production setup, monitoring |
| [API Reference](docs/en/api/index.md) | REST endpoints, authentication, error codes |
| [Development](docs/en/development/index.md) | Contributing, local dev, testing strategy |
| [Troubleshooting](docs/en/troubleshooting/index.md) | Common issues and solutions |

---

## 🗂 Project Structure

```
RecordPlatform/
├── platform-api/          # Shared Dubbo interface definitions
├── platform-backend/      # REST API service (Dubbo Consumer, :8000)
│   ├── backend-web/       # Controllers, JWT filters, rate limiting
│   ├── backend-service/   # Business logic, Saga orchestration, Outbox
│   ├── backend-dao/       # MyBatis Plus mappers and entities
│   ├── backend-api/       # Internal interface definitions
│   └── backend-common/    # Shared utilities and constants
├── platform-fisco/        # Blockchain service (Dubbo Provider, :8091)
├── platform-storage/      # Storage service (Dubbo Provider, :8092)
├── platform-frontend/     # Svelte 5 + SvelteKit frontend
├── scripts/               # Start/stop scripts, env-check
├── tools/                 # k6 load tests, security PoC, doc consistency
├── docs/                  # VitePress documentation site (en/zh)
└── docker-compose.infra.yml  # Infrastructure services (Nacos, MySQL, Redis, RabbitMQ, MinIO)
```

---

## 🛠 Contributing

We welcome contributions! Please read the [Contributing Guide](docs/en/development/contributing.md) before getting started.

```bash
# 1. Fork and clone the repository
git clone https://github.com/<your-fork>/RecordPlatform.git

# 2. Create a feature branch
git checkout -b feat/your-feature

# 3. Make changes, run tests
mvn -f platform-backend/pom.xml test

# 4. Open a Pull Request against main
```

**Branch naming:** `feat/`, `fix/`, `refactor/`, `docs/`, `chore/`

All PRs must pass CI gates: backend tests, frontend tests, contract consistency check, and build verification. See [CI Gates](docs/en/development/contributing.md#ci-gates) for details.

---

## 📄 License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.
