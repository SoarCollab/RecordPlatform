# Configuration

This guide covers environment variables and configuration options for RecordPlatform.

::: tip Configuration Migration
Since v2.0, sensitive configurations (database credentials, Redis, email SMTP, RabbitMQ) have been migrated to Nacos configuration center. Only Nacos connection details and security keys (JWT_KEY) remain in environment variables. See [Nacos Configuration Template](/nacos-config-template.yaml) for the complete configuration structure.
:::

## Environment Variables

Copy the example file and customize:

```bash
cp .env.example .env
vim .env
```

### Core Configuration

| Category | Variable | Description | Default |
|----------|----------|-------------|---------|
| **Nacos** | `NACOS_HOST` | Nacos server | localhost |
| | `NACOS_PORT` | Nacos port | 8848 |
| | `NACOS_USERNAME` | Nacos username | required, no default |
| | `NACOS_PASSWORD` | Nacos password | required, no default |
| **Profile** | `SPRING_PROFILES_ACTIVE` | Spring profile | local |

### Security Configuration

| Variable | Description | Requirement |
|----------|-------------|-------------|
| `JWT_KEY` | JWT signing key + ID encryption derivation | Min 32 characters, high entropy |
| `PUBLIC_REGISTRATION_TENANT_ID` | Server-side tenant for public registration | Set explicitly; request headers do not choose registration tenant |
| `RATE_LIMIT_TRUSTED_PROXY_CIDRS` | Numeric trusted proxy IPs/CIDRs for public-proof client-IP rate limiting | Empty by default; configure only platform-controlled proxies |
| `BLOCKCHAIN_RPC_TOKEN` | Shared token for backend-to-FISCO Dubbo calls | Required on both backend and fisco; no default |
| `RECORD_PLATFORM_UID_SALT` | Salt for UID obfuscation | Recommended 8–16 random chars |
| `RECORD_PLATFORM_CLIENT_KEY` | Client key for UID obfuscation | Recommended 16–32 random chars |

`RATE_LIMIT_TRUSTED_PROXY_CIDRS` is a comma-separated list of at most 64 numeric IPv4/IPv6 addresses or CIDRs and at most 4096 characters in total. Direct deployments and deployments with an unverified proxy topology must leave it empty; the backend then ignores `X-Forwarded-For`, `X-Real-IP`, and `Forwarded` and uses the canonical direct socket peer. Behind a proxy, an empty allowlist safely places all callers in the proxy peer's shared 120/60 bucket and can reject traffic earlier. Hostnames, URLs, ports, zone IDs, empty entries, invalid prefixes, `0.0.0.0/0`, and `::/0` fail application startup. This configuration is an immutable startup trust boundary and requires a restart.

When the immediate socket peer matches the allowlist, the backend parses one XFF line of at most 1024 characters and 16 hops from right to left and skips trusted hops; `X-Real-IP` is considered only when XFF is absent. Duplicate, invalid, overlong, or over-hop headers fall back to the immediate peer. Every controlled proxy must overwrite or safely append socket-derived forwarding data. Spring/container forwarding rewrites remain disabled with `server.forward-headers-strategy=none`; configuring `server.tomcat.remoteip.remote-ip-header` or `server.tomcat.remoteip.protocol-header` fails startup. Do not install a `ForwardedHeaderFilter`, external Tomcat `RemoteIpValve`, or equivalent second parser.

### Storage Configuration

S3-compatible storage is configured via Nacos. Basic environment variables:

| Variable | Description |
|----------|-------------|
| `S3_ENDPOINT` | S3 endpoint URL |
| `S3_ACCESS_KEY` | Access key |
| `S3_SECRET_KEY` | Secret key |
| `S3_BUCKET_NAME` | Bucket name |

Fault domain configuration is managed through Nacos and supports runtime refresh.

### Blockchain Configuration

| Variable | Description | Example |
|----------|-------------|---------|
| `BLOCKCHAIN_ACTIVE` | Active chain type | `local-fisco`, `bsn-fisco`, `bsn-besu` |
| `FISCO_PEER_ADDRESS` | FISCO node address | `127.0.0.1:20200` |
| `FISCO_CHAIN_ID` | Expected local FISCO chain ID | `chain0` |
| `FISCO_GROUP_ID` | Expected local FISCO group ID | `group0` |
| `BSN_FISCO_CHAIN_ID` | Expected BSN FISCO chain ID; required with no default when `BLOCKCHAIN_ACTIVE=bsn-fisco` | Provider-assigned value |
| `BSN_BESU_RPC_URL` | BSN Besu JSON-RPC endpoint; required when `BLOCKCHAIN_ACTIVE=bsn-besu` | Provider-assigned HTTPS URL |
| `BSN_BESU_CHAIN_ID` | Expected BSN Besu numeric chain ID | Provider-assigned value |
| `BSN_BESU_PRIVATE_KEY` | Local Besu signer private key | Deployment secret; never commit a value |
| `BSN_BESU_CONTRACT_STORAGE` | Verified BSN Besu Storage contract address | `0x...` |
| `BSN_BESU_CONTRACT_SHARING` | Verified BSN Besu Sharing contract address | `0x...` |
| `BSN_BESU_NONCE_STATE_DIRECTORY` | Durable nonce journal and signer ownership-lock directory | `/var/lib/record-platform/besu-nonce` |
| `FISCO_STORAGE_CONTRACT` | Storage contract address | `0x...` |
| `FISCO_SHARING_CONTRACT` | Sharing contract address | `0x...` |
| `FISCO_{STORAGE,SHARING}_DEPLOYMENT_TX` | Required deployment transaction hash; the active-chain receipt must exist and match the configured address/block | `0x` + 64 hex |
| `FISCO_{STORAGE,SHARING}_DEPLOYMENT_BLOCK` | Required deployment block number from the same successful receipt | Non-negative decimal |
| `FISCO_{STORAGE,SHARING}_DEPLOYMENT_EFFECTIVE_AT` | Required actual activation time, configured with transaction/block | UTC `YYYY-MM-DDTHH:MM:SSZ` |
| `CONTRACT_DEPLOYMENT_RECEIPT_DIR` | Durable restricted directory for public deployment audit receipts | `log/contract-deployments` for local development |

`scripts/contract-deploy.sh` requires explicit local chain/group values and compares them with Console `getGroupInfo` before compilation and every deployment. It fetches each transaction receipt together with `getGroupInfo` in one Console session, requires explicit FISCO success status `0`, and derives the final transaction/address/block fields from that one receipt. All three deployment evidence fields are mandatory for both contracts; an entirely empty legacy triplet now fails startup. The same variable names carry reviewed BSN deployment evidence, where startup revalidates the receipt through the selected BSN FISCO or Besu client (Besu requires explicit status `1`). The guarded local script writes both triplets with one shared effective time and first publishes a credential-free structured receipt; production should place that receipt directory outside ephemeral application storage. `env-check.sh` validates shape only; restart `platform-fisco` to perform the authoritative active-chain receipt, runtime-code, and identity checks.

BSN Besu raw writes reserve nonces per canonical signer from the node's `PENDING` count and a durable local high-watermark. `BSN_BESU_NONCE_STATE_DIRECTORY` has no runtime default: it must support reliable Java/POSIX file locks and atomic replacement, survive process/container restarts, and be shared by every supported process that could use the same `(chainId, signer)`. Startup holds an exclusive signer lock for the JVM lifetime, so a second writer on that shared directory fails closed. The configured signer key is exclusive to this coordinator and must not be used by an external wallet or uncoordinated process. Do not place the directory on ephemeral container storage, delete its state files to clear an incident, or run the same signer active-active on independent hosts. A cold standby may take over only after the old writer is externally fenced and its lock/state volume is available.

### SSL Configuration (Production)

| Variable | Description |
|----------|-------------|
| `SSL_KEY_STORE` | Keystore path |
| `SSL_KEY_STORE_PASSWORD` | Keystore password |
| `REQUIRE_SSL` | Force HTTPS (true/false) |
| `HTTP_REDIRECT_PORT` | HTTP redirect port |

### Service Port Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | Backend REST API port | 8000 (recommended for local/dev; prod profile defaults to 8080 if unset) |
| `DUBBO_FISCO_PORT` | FISCO Dubbo service port | 8091 |
| `DUBBO_STORAGE_PORT` | Storage Dubbo service port | 8092 |
| `DUBBO_HOST` | Service registration IP (for Docker) | Required for provider services; no runtime default |
| `QOS_BACKEND_PORT` | Backend QoS management port | 22330 |
| `QOS_FISCO_PORT` | FISCO QoS management port | 22331 |
| `QOS_STORAGE_PORT` | Storage QoS management port | 22332 |

> **Note**: `DUBBO_HOST` is critical in Docker environments to ensure services register with accessible IPs rather than Docker bridge network IPs. Set it explicitly in `.env` or deployment secrets.

### Logging Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `LOG_LEVEL` | Application log level | INFO |
| `LOG_PATH` | Log file output directory | /var/log/record-platform |

### CORS Configuration

| Variable | Description | Example |
|----------|-------------|---------|
| `CORS_ALLOWED_ORIGINS` | Allowed frontend origins (comma-separated) | `http://localhost:3000,http://localhost:5173` |

### API Documentation Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `KNIFE4J_USERNAME` | Knife4j/Swagger UI username | required, no default |
| `KNIFE4J_PASSWORD` | Knife4j/Swagger UI password | required, no default |

### APM Configuration (Optional)

SkyWalking integration for distributed tracing:

| Variable | Description | Default |
|----------|-------------|---------|
| `SW_AGENT_COLLECTOR_BACKEND_SERVICES` | SkyWalking OAP collector | localhost:11800 |
| `SW_AGENT_NAME` | Service name in SkyWalking | record-platform |
| `SW_JDBC_TRACE_SQL_PARAMETERS` | Trace SQL parameters | true |

## Profile Configuration

Available profiles: `local`, `dev`, `prod`

```bash
# Run with specific profile
java -jar app.jar --spring.profiles.active=prod
```

### Profile Differences

| Feature | local | dev | prod |
|---------|-------|-----|------|
| Swagger UI | Enabled | Enabled | Disabled |
| Druid Monitor | Enabled | Enabled | Disabled |
| Debug Logging | Enabled | Partial | Disabled |
| SSL Required | No | No | Yes |

## Nacos Configuration

Dynamic configurations are managed in Nacos. Template: `docs/public/nacos-config-template.yaml`

### Key Nacos Configs

```yaml
# Storage nodes with fault domains
storage:
  # Required: List of active domains
  active-domains:
    - domain-a
    - domain-b

  # Optional: External endpoint (v3.2.0+)
  # Used for generating presigned URLs, solves cross-network (e.g., VPN) access issues
  # Format: https://host[:port] (no trailing slash)
  external-endpoint: https://s3-secondary.example.com

  # Optional: Standby domain (for failover)
  standby-domain: standby

  # Replication configuration (v3.1.0+)
  replication:
    factor: 2                     # Replica count, default = active domain count
    quorum: auto                  # Quorum strategy: auto|majority|all|number

  # Degraded write configuration (v3.1.0+)
  degraded-write:
    enabled: true                 # Allow degraded writes
    min-replicas: 1               # Minimum replicas in degraded mode
    track-for-sync: true          # Track degraded writes for later sync

  virtualNodesPerNode: 150

  # Optional: Domain detailed configuration
  domains:
    - name: domain-a
      minNodes: 1
      acceptsWrites: true
    - name: domain-b
      minNodes: 1
      acceptsWrites: true
    - name: standby
      minNodes: 0
      acceptsWrites: false

  nodes:
    - name: node-a1
      endpoint: http://minio-a:9000
      faultDomain: domain-a
      weight: 100
    - name: node-b1
      endpoint: http://minio-b:9000
      faultDomain: domain-b
      weight: 100

  # Replica consistency repair configuration
  consistency:
    repair:
      enabled: true               # Enable scheduled repair
      cron: "0 */15 * * * ?"      # Every 15 minutes
      batch-size: 100
      lock-timeout-seconds: 600

  # Data rebalancing configuration
  rebalance:
    enabled: true                 # Enable automatic rebalancing
    rate-limit-per-second: 10     # Max objects copied per second
    cleanup-source: false         # Delete source data after rebalance
```

> **Note**: `active-domains` is required and validated at startup. For single-domain development mode, configure only one domain.

### Quota Governance

Per-user and per-tenant storage quota enforcement:

| Property | Description | Default |
|----------|-------------|---------|
| `quota.enforcement-mode` | Enforcement mode | `SHADOW` (log only, no rejection) |
| `quota.rollout.strategy` | Rollout strategy | `TENANT_WHITELIST` |
| `quota.rollout.enforce-tenant-whitelist` | Tenant IDs to enforce (comma-separated) | _(empty = all tenants when global mode is `ENFORCE`)_ |
| `quota.rollout.force-shadow` | Force SHADOW mode for all tenants | `false` |

> **Tip**: Start with global `SHADOW` mode to observe quota usage without rejecting uploads.
> With global `ENFORCE`, an empty whitelist means all tenants are enforced; use `force-shadow=true` or a non-empty whitelist for a controlled rollout.

### Signed-Proof Issuer

Signed proof ZIPs use a dedicated Ed25519 key. Issuance is disabled and fail-closed by default, with no fallback to `JWT_KEY`, the file-envelope master key, or a blockchain RPC token.

| Environment variable | Description | Default |
|----------|-------------|---------|
| `PROOF_SIGNING_ENABLED` | Allow new issuance and historical rebuild | `false` |
| `PROOF_SIGNING_KEY_ID` | Stable key identifier using letters, digits, dot, underscore, or hyphen; max 64 characters | empty |
| `PROOF_SIGNING_KEY_VERSION` | Positive key version; increment on rotation | `1` |
| `PROOF_SIGNING_KEY_STATUS` | Must be `ACTIVE` for new issuance | `DISABLED` |
| `PROOF_SIGNING_PRIVATE_KEY_PKCS8` | Base64 or PEM-wrapped Ed25519 PKCS#8 private key | empty |
| `PROOF_SIGNING_PUBLIC_KEY_SPKI` | Matching Base64 or PEM X.509 SPKI public key | empty |

Before enabling issuance, configure a matching PKCS#8/SPKI pair, non-empty key ID, positive version, and `ACTIVE` status. Configuration errors do not print key material. First issuance atomically registers `(keyId, keyVersion)` in the global key table. For rotation, retain old public material and provision the new key under a higher version; never reuse the same ID/version for another SPKI. Inject the private key through deployment secret management, never Git, logs, or exception messages.

## Scheduled Tasks Configuration

### Share Cleanup

Automatically marks expired shares as inactive:

```yaml
share:
  cleanup:
    interval: 300000  # Check every 5 minutes (milliseconds)
```

Uses distributed lock to prevent duplicate execution in multi-instance deployments.

### File Cleanup

Cleans up soft-deleted files after retention period:

```yaml
file:
  cleanup:
    retention-days: 30      # Days to retain soft-deleted files
    batch-size: 100         # Files processed per batch
    cron: "0 0 3 * * ?"     # Daily at 3:00 AM
```

## Frontend Configuration

Frontend environment variables (`platform-frontend/.env`):

| Variable | Description |
|----------|-------------|
| `PUBLIC_API_BASE_URL` | Backend API URL |
| `PUBLIC_ENV` | Environment name |
| `PUBLIC_TENANT_ID` | Default tenant ID |
