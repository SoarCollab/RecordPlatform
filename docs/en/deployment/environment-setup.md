# Environment Setup

Complete environment setup guide for RecordPlatform from scratch.

## Prerequisites

### Host Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 4 cores | 8 cores |
| Memory | 8 GB | 16 GB |
| Disk | 40 GB | 100 GB SSD |
| OS | Ubuntu 20.04+ / CentOS 8+ / macOS 12+ | Ubuntu 22.04 LTS |

### Software Requirements

| Software | Version | Purpose |
|----------|---------|---------|
| Docker | 20.10+ | Infrastructure containerization |
| Docker Compose | 2.0+ | Container orchestration |
| Java | 21 (LTS) | Backend services |
| Maven | 3.8+ | Java build |
| Node.js | 20+ | Frontend build |
| pnpm | 10+ | Frontend package manager |
| Git | 2.30+ | Version control |

## Step 1: Configure Environment Variables

```bash
# Clone the repository
git clone https://github.com/SoarCollab/RecordPlatform.git
cd RecordPlatform

# Copy the environment template
cp .env.example .env
```

Edit `.env` and update key settings:

```bash
# Must change before starting infrastructure
JWT_KEY=<random-string-at-least-32-chars>
DB_PASSWORD=<database-password>
REDIS_PASSWORD=<redis-password>
RABBITMQ_USERNAME=<rabbitmq-username>
RABBITMQ_PASSWORD=<rabbitmq-password>
NACOS_USERNAME=<nacos-username>
NACOS_PASSWORD=<nacos-password>
NACOS_AUTH_TOKEN=<base64-random-token-at-least-32-bytes>
NACOS_AUTH_IDENTITY_KEY=<nacos-identity-key-name>
NACOS_AUTH_IDENTITY_VALUE=<nacos-identity-secret>
S3_ACCESS_KEY=<minio-access-key>
S3_SECRET_KEY=<minio-secret-key>

# Optional: adjust for your environment
SPRING_PROFILES_ACTIVE=local   # local / dev / prod
```

::: warning
Do not use `.env.example` placeholder values on any shared or server host. The infrastructure compose file fails fast when required secrets are missing, and all passwords/tokens above must be replaced with strong environment-specific values.
:::

`docker-compose.infra.yml` binds published service ports to `127.0.0.1` by default through `INFRA_BIND_ADDRESS` and `OBSERVABILITY_BIND_ADDRESS`. Keep this default on servers and expose only the application-facing endpoints through a firewall, VPN, SSH tunnel, or authenticated reverse proxy. Do not set these bind addresses to `0.0.0.0` unless the host firewall restricts every infrastructure port.

## Step 2: Start Infrastructure

Use `docker-compose.infra.yml` to start all containerizable infrastructure:

```bash
# Start all infrastructure services
docker compose -f docker-compose.infra.yml up -d

# Wait until all services are healthy
docker compose -f docker-compose.infra.yml up -d --wait
```

Included services:

| Service | Port | Management UI |
|---------|------|---------------|
| Nacos | 127.0.0.1:8848 | http://localhost:8848/nacos |
| MySQL | 127.0.0.1:3306 | — |
| Redis | 127.0.0.1:6379 | — |
| RabbitMQ | 127.0.0.1:5672 | http://localhost:15672 |
| MinIO-A | 127.0.0.1:9000 | http://localhost:9001 |
| MinIO-B | 127.0.0.1:9010 | http://localhost:9011 |
| OTel Collector | 127.0.0.1:4317/4318/8889 | — |
| Jaeger | 127.0.0.1:16686 | http://localhost:16686 |

Verify status:

```bash
docker compose -f docker-compose.infra.yml ps
```

### Provision the migration account before first startup

Creating the database does not complete Flyway permission setup. On the observed Flyway 11.7.2 / Druid 1.2.28 / MySQL 8.4 path, the migration account also needs SELECT on exactly `performance_schema.user_variables_by_thread`; a denial may surface later as `connection disabled` while reading `foreign_key_checks`.

Follow the canonical [MySQL bootstrap permissions](../../operations/flyway-release-compatibility.md#mysql-bootstrap-permissions) procedure for the precise grant, effective-grant/probe verification, session-variable exposure warning, and migration/runtime credential separation and retirement. Restrict the account's source host and keep credentials private. Neither infrastructure health nor `env-check.sh --fix` establishes this grant; do not use broad grants or disable Flyway to make startup pass. Existing databases still follow the [normal migration path](../../operations/flyway-release-compatibility.md#_6-normal-fresh-and-v0-0-2-upgrade-path) with validation enabled.

## Step 3: Configure Nacos

Nacos serves as the configuration center. Application configs must be imported.

1. Open Nacos console: http://localhost:8848/nacos and sign in with the credentials configured for this environment. If the Nacos image initializes a built-in default account, rotate it before exposing the host beyond your workstation.
2. Create configurations:

| Data ID | Group | Description |
|---------|-------|-------------|
| `backend-web.yaml` | DEFAULT_GROUP | Backend main config (DB, Redis, RabbitMQ connections) |
| `platform-storage.yaml` | DEFAULT_GROUP | Storage service config (S3 node list, encryption params) |

::: warning Important
Sensitive credentials (DB password, Redis password, etc.) are stored in Nacos configurations, not in `.env`. The infrastructure credentials in `.env` are only used by docker-compose. `platform-fisco` reads blockchain node, contract, signer, and nonce-state settings from deployment environment variables (`FISCO_*` or `BSN_*`), not from a Nacos Data ID. Supply `BSN_BESU_PRIVATE_KEY` through the deployment secret manager rather than committing it to `.env`.
:::

## Step 4: FISCO BCOS Node

The FISCO BCOS blockchain node **cannot be started via docker-compose** and requires manual deployment on the server.

### Quick Setup (Single Group, 4 Nodes)

```bash
# Download build_chain script
curl -#LO https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v3.8.0/build_chain.sh
chmod +x build_chain.sh

# Generate 4-node chain (Air version)
bash build_chain.sh -l 127.0.0.1:4 -p 30300,20200

# Start all nodes
bash nodes/127.0.0.1/start_all.sh
```

### Copy SDK Certificates

```bash
# Copy certificates to the FISCO service resource directory
cp nodes/127.0.0.1/sdk/* platform-fisco/src/main/resources/conf/
```

### Deploy Smart Contracts

Use the guarded deployment script instead of activating addresses manually. Before any Console command, the script resolves one safe interactive launcher: it prefers the official Console 3.7 `start.sh` and uses `console.sh` only as a documented legacy fallback when `start.sh` is absent. An optional `--console-launcher` or `FISCO_CONSOLE_LAUNCHER` override must select the same executable regular non-symlink file directly inside the Console directory; conflicting or external selections fail before chain access. The script verifies the version-controlled artifact catalog, reconciles the `getGroupInfo` chain/group/crypto/VM tuple with the explicit EVM deployment target, and accepts current boolean `isWasm` or legacy boolean `wasm` metadata only when present, correctly typed, and mutually consistent. It rebuilds both contracts' ECC/SM creation and deployed runtime artifacts with fixed FISCO solc `0.8.11+commit.6b4cc280` keccak256/sm3 compilers. Before each deployment it compares canonical ABI and decoded bytecode and rechecks chain/group. After deployment it fetches `getGroupInfo` and the structured transaction receipt in one Console session through that same launcher, requires explicit success status `0`, and cross-checks transaction hash and contract address before using that receipt's block number. It then verifies the complete `getCode` runtime bytes before `contractIdentity()`, publishes a structured audit receipt, and atomically updates `.env`.

This script is intentionally limited to `BLOCKCHAIN_ACTIVE=local-fisco`. It rejects BSN FISCO/Besu activation before any Console query; those networks require their own reviewed provider deployment process.

The write boundary is the project-built `platform-fisco-0.0.2-SNAPSHOT-deploy-helper.jar`, packaged by `mvn -f platform-fisco/pom.xml package` with the same pinned Java SDK **3.8.0** as the provider. Build/install `platform-api` first. `FISCO_DEPLOY_HELPER_JAR` may select a previously built copy of that release artifact. Deployment never discovers SDK jars inside Console and never sends ordinary `deploy Name`: Console recompiles Solidity with an optimizer profile that does not reproduce the signed artifacts.

`FISCO_PRIVATE_KEY` is mandatory for both deployment and local provider startup. Configure one private 32-byte hexadecimal key locally, together with explicit `FISCO_CERT_PATH` and `FISCO_PEER_ADDRESS`. The helper installs that exact key before signing; the same key must remain configured when the provider writes to `Sharing`, whose constructor records the deployer as operator. Missing/invalid keys and unsupported signing modes fail closed; random SDK accounts and account-file/password fallbacks are not supported. Never commit these local values.

Python captures the catalog, raw ABI and selected creation artifact through no-follow directory/file descriptors, checks regular-file type and a 5 MiB per-artifact bound, and passes three big-endian length-prefixed frames through a private stdin handle. Java rejects truncated, oversized or trailing input, validates the raw catalog/ABI capture hashes plus the canonical ABI and complete decoded creation digest, and submits those same in-memory creation bytes with no constructor arguments. Raw ABI capture SHA-256 is not the canonical ABI fingerprint. The SDK client rechecks chain/group/crypto/EVM immediately before signing. Console remains an independent read-only receipt/runtime/identity probe; SDK-refilled receipt input/hash fields are never independent chain evidence.

Helper output is one bounded non-secret schema record. A timeout, malformed output or failure after possible submission is **uncertain**, not permission to retry: stop and inspect chain receipts before any new deployment. The wrapper never retries automatically. Runtime output accepts anchored official `[group]: /path> 0x...` framing, ANSI colors and CRLF, but rejects mixed errors, malformed/empty/odd code and multiple candidates. Full runtime equality and audit-before-activation remain mandatory.

Set the target identity and durable audit location in `.env`:

```dotenv
FISCO_CHAIN_ID=chain0
FISCO_GROUP_ID=group0
CONTRACT_DEPLOYMENT_RECEIPT_DIR=/var/lib/record-platform/contract-deployments
```

```bash
# Build the pinned helper (after installing platform-api); no chain access occurs here
mvn -f platform-fisco/pom.xml package

# Preview every phase without changing the console, chain, or .env
./scripts/contract-deploy.sh --dry-run --console-dir /opt/fisco/console

# Deploy and atomically activate Storage and Sharing
./scripts/contract-deploy.sh \
  --console-dir /opt/fisco/console \
  --env-file .env \
  --receipt-dir /var/lib/record-platform/contract-deployments

# Recheck the signed catalog independently
python3 tools/contracts/contract_fingerprint.py verify \
  --project-root . \
  --catalog platform-fisco/src/main/resources/contract-registry/artifacts.json
```

Successful activation writes `FISCO_STORAGE_CONTRACT`, `FISCO_SHARING_CONTRACT`, and each contract's complete `FISCO_*_DEPLOYMENT_TX`, `FISCO_*_DEPLOYMENT_BLOCK`, and `FISCO_*_DEPLOYMENT_EFFECTIVE_AT` triplet with one shared UTC effective time. Before activation it atomically publishes a `record-platform-contract-deployment-receipt.v2` JSON receipt containing the catalog SHA-256, `LOCAL_FISCO` chain/group identity, per-contract `receiptStatus=SUCCESS`, and both contracts' public name/version/address/transaction/block evidence. Every tx/address/block value comes from one successful receipt. It never records RPC URLs, certificates, private keys, or tokens; historical `v1` receipts remain audit records only.

Do not copy only one address, leave a triplet empty, or provide only part of it. Wrong chain/group, unsupported WASM/crypto combinations, missing/failed/ambiguous receipts, transaction/address/block mismatch, catalog identity mismatch, solc output drift, missing runtime code, runtime bytes that do not match the signed artifact for the actual ECC/SM variant, non-zero/reverted identity calls, response parsing errors, or receipt write failures leave the previous `.env` unchanged. Dry-run performs no Console call, does not generate an effective time or receipt, and does not modify files or chain state. Provision the exact ECC/GM pair explicitly with `tools/contracts/provision_fisco_solc.py`, set `FISCO_SOLC_CACHE_DIR`, and verify the cache offline before deployment. The deploy command never downloads compiler code and rejects Console's shared compiler cache; any provenance, ABI, creation, or runtime mismatch is blocked before the first deployment transaction.

`./scripts/env-check.sh --service contracts` verifies only the required field shapes. Restart `platform-fisco` for the authoritative check: startup queries each receipt through the selected active-chain client, requires FISCO status `0` or Besu status `1`, compares tx/address/block, and publishes no `ACTIVE` registry if RPC or any field fails. Existing environments with empty evidence must obtain the real receipts or redeploy; do not invent placeholders. For rollback, restore the prior reviewed catalog, both addresses, and both real triplets together, then restart. If either receipt is no longer provable on the configured chain/group, keep the service stopped and redeploy rather than bypassing validation.

Compiler execution must use the provisioner's `run` command, for example `python3 tools/contracts/provision_fisco_solc.py --cache-dir /var/lib/record-platform/fisco-solc run --variant ecc -- --version`. `verify` and `resolve` report cache locations, not executable authorization: never execute their returned paths directly. Every run captures bounded regular-file bytes through no-follow directory/file descriptors, validates provenance and the copied executable hash, and executes version checking and compilation from that same private snapshot. Replacing or rewriting the cache cannot change the snapshot. Symlinks in cache ancestors, release directories, variant directories, metadata or compilers are rejected; only the root-owned macOS `/tmp` and `/var` OS aliases are normalized for local fixtures.

Verification and execution require writable, executable-mounted executor temporary storage outside the cache (`TMPDIR` must not point inside it or use a `noexec` mount); read-only caches remain usable. Provisioning additionally requires write access to the cache's parent, where it creates private sibling build/staging state on the same filesystem. Both staged variants and their provenance are fully validated before an atomic no-replace publication. An existing competitor is never overwritten or deleted. Failed builds, copies, metadata writes, version checks or publication leave no newly published invalid release and may be retried. The supported real builder remains Linux x86_64 with `renameat2(RENAME_NOREPLACE)` support; unsupported filesystems fail closed without a replacement fallback. The threat model is a writer to the mutable compiler cache, not an unrestricted same-UID attacker able to alter executor private state, trusted manifest/source, system tools or process environment.

Artifact upgrades are reviewed changes: update both Solidity source copies, signed ABI, ECC/SM creation/runtime bytecode, semantic version, lifecycle status, and catalog fingerprints together. Retain deprecated/revoked artifacts and deployment receipts needed by historical proofs and audits. See [Blockchain Integration](../architecture/blockchain-integration.md#contract-registry-and-artifact-fingerprints) for upgrade and rollback rules and `scripts/README.md` for all script options.

::: info
For detailed node and console setup, see the [FISCO BCOS Documentation](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/). RecordPlatform contract activation must still use the guarded repository script above.
:::

## Step 5: Verify Environment

Run the environment pre-check script to validate all infrastructure at once:

```bash
./scripts/env-check.sh
```

The script checks 8 items:

| # | Check | Validates |
|---|-------|-----------|
| 1 | Nacos | Connectivity + config existence |
| 2 | MySQL | Connection + database existence |
| 3 | Redis | Authentication + PING |
| 4 | RabbitMQ | AMQP port + management API |
| 5 | FISCO BCOS | Node port connectivity |
| 6 | S3/MinIO | Health check + bucket existence |
| 7 | TLS Certificates | File existence + expiry |
| 8 | Contract Addresses | Format validation |

Auto-fix mode (creates database, buckets, etc.):

```bash
./scripts/env-check.sh --fix
```

Check a single service:

```bash
./scripts/env-check.sh --service mysql
```

## Step 6: Build and Start

### Build

```bash
# 1. Install shared interfaces (first time or when dependencies change)
mvn -f platform-api/pom.xml clean install

# 2. Install the shared proof/verifier SDK required by backend-service
mvn -f platform-verifier/pom.xml -pl sdk -am clean install -DskipTests

# 3. Build backend
mvn -f platform-backend/pom.xml clean package -DskipTests

# 4. Build FISCO service
mvn -f platform-fisco/pom.xml clean package -DskipTests

# 5. Build storage service
mvn -f platform-storage/pom.xml clean package -DskipTests

# 6. Frontend
cd platform-frontend && pnpm install && pnpm build
```

### Start

```bash
# Start all services with the management script
./scripts/start.sh start all

# Check service status
./scripts/start.sh status
```

Startup order: `platform-storage` → `platform-fisco` → `platform-backend` → frontend

### Verify

```bash
# Backend health check
curl http://localhost:8000/record-platform/actuator/health

# Frontend dev server
cd platform-frontend && pnpm dev
```

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| Nacos fails to start | Insufficient memory | Ensure Docker has ≥ 4GB memory allocated |
| MySQL connection refused | Container not ready | `docker compose -f docker-compose.infra.yml up -d --wait` |
| Redis AUTH failed | Password mismatch | Check `REDIS_PASSWORD` in `.env` matches Nacos config |
| FISCO service hangs on startup | Node unreachable | `SdkBeanConfig` connects on init — ensure node is running |
| MinIO inaccessible | Port conflict | Check if ports 9000/9001 are already in use |
| Dubbo service discovery fails | Wrong DUBBO_HOST | Set `DUBBO_HOST` to host IP in Docker environments |
| env-check.sh deep checks skipped | CLI tools missing | Install `mysql-client`, `redis-cli`, `aws`, etc. |
