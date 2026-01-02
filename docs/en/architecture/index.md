# Architecture

Technical architecture documentation for RecordPlatform.

## Contents

- [System Overview](system-overview) - High-level architecture, components, and data flow
- [Distributed Storage](distributed-storage) - Fault domains, consistent hashing, rebalancing
- [Blockchain Integration](blockchain-integration) - Smart contracts, multi-chain adapters
- [Security](security) - Authentication, authorization, encryption

## Architecture Principles

### 1. Microservices with Dubbo

- Services communicate via Dubbo Triple protocol (gRPC-compatible)
- Nacos for service discovery and configuration
- Clear separation: providers (storage, blockchain) and consumer (backend)

### 2. Distributed Transaction

- Saga pattern for cross-service consistency
- Outbox pattern for reliable event publishing
- Automatic compensation with exponential backoff

### 3. High Availability

- Fault domain isolation (A/B active + STANDBY pool)
- Consistent hashing for data distribution
- Automatic failover and rebalancing

### 4. Security by Design

- JWT authentication with HMAC512
- ID obfuscation (AES-256-CTR + HMAC)
- RBAC with resource ownership verification
- Multi-tenant isolation

## Quick Reference

| Component | Technology | Purpose |
|-----------|------------|---------|
| Service Communication | Dubbo Triple | High-performance RPC |
| Service Discovery | Nacos | Registration, config |
| Distributed Transaction | Saga + Outbox | Cross-service consistency |
| Storage | S3-compatible | Object storage |
| Blockchain | FISCO BCOS | Immutable attestation |
| Caching | Caffeine + Redis | Multi-level caching |
| Resilience | Resilience4j | Circuit breaker, retry |

