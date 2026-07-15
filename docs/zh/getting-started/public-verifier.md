# 公开验证器

`platform-verifier` 是与 RecordPlatform 业务后端隔离的公开验真组件。它不读取平台数据库、不依赖租户或登录令牌，也不会把上传内容写入业务存储。SDK、CLI 和 Web 服务共享同一套签名证明 ZIP v2 验证逻辑。

## 判断边界

验证报告有四种稳定结果：

| 结果 | 含义 |
| --- | --- |
| `VALID` | 文件哈希、chunk、Merkle 路径、可信 Ed25519 签名、当前 `ACTIVE` 状态和实时链上根全部通过 |
| `INVALID` | 存在确定性篡改、不匹配、无效签名、撤销/替代/无效状态或链上根不一致 |
| `INDETERMINATE` | 本地证据可以自洽，但可信密钥、当前状态或链查询未配置、未知或暂时不可用；不等同于有效 |
| `ERROR` | ZIP/JSON 畸形、资源超限、I/O 失败或验证器无法安全完成处理 |

`VALID` 必须同时具备可信签名、当前状态和实时链证据。离线运行永远不会仅凭证明包内嵌的密钥元数据给出 `VALID`。

## 构建

在仓库根目录执行：

```bash
mvn -f platform-verifier/pom.xml clean verify
```

产物：

- `platform-verifier/sdk/target/platform-verifier-sdk-0.0.2-SNAPSHOT.jar`
- `platform-verifier/cli-verifier/target/record-platform-verifier-exec.jar`
- `platform-verifier/web-verifier/target/platform-verifier-web.jar`
- `platform-verifier/sdk/target/verifier-fixtures/`：CI 生成的原文件、证明包、可信公钥和期望报告样例

SDK 的行覆盖率门禁为 70%。CLI 和 Web 仅负责输入输出与资源治理，不重复实现哈希、Merkle、签名、状态或链判断。

## CLI

### 本地信任、离线状态/链查询

```bash
java -jar platform-verifier/cli-verifier/target/record-platform-verifier-exec.jar verify \
  --file ./original.pdf \
  --proof ./record-proof-file-1.zip \
  --trusted-key ./trusted-key.json \
  --format json
```

可信公钥文件采用严格 JSON，未知字段、重复字段和尾随内容会被拒绝：

```json
{
  "algorithm": "EdDSA",
  "keyId": "record-platform-proof-2026",
  "keyVersion": 1,
  "publicKeyFingerprint": "sha256:<64 位小写十六进制>",
  "publicKeySpki": "<Base64 编码的 X.509 SPKI>"
}
```

提供本地公钥只解决签名信任。若没有当前状态和实时链解析器，最终结果仍为 `INDETERMINATE`。

### 显式在线验证

```bash
java -jar platform-verifier/cli-verifier/target/record-platform-verifier-exec.jar verify \
  --file ./original.pdf \
  --proof ./record-proof-file-1.zip \
  --online \
  --issuer-base-url https://record.example/record-platform \
  --chain-url-template 'https://chain.example/api/v1/roots/{chainType}/{chainId}/{groupId}/{contractAddress}/{batchNo}' \
  --allow-host record.example \
  --allow-host chain.example \
  --format text
```

在线模式不会使用 ZIP 中的 URL 作为网络目标。签发方地址、链网关模板和精确主机白名单必须由调用方显式提供。默认要求 HTTPS、拒绝重定向和私有/回环地址，并限制连接时间、响应时间与响应大小。

仅在本地测试时可额外使用 `--allow-http --allow-private-addresses`，生产环境不应开启。

### 在线解析器合同

`--issuer-base-url` 指向 RecordPlatform 部署上下文，不需要 JWT 或租户头。验证器只会在该基地址下访问证明中声明的精确 key/proof 身份：

- `GET /api/v1/public/proof-keys/{keyId}/versions/{keyVersion}`；
- `GET /api/v1/public/proofs/{proofId}/status`。

两个响应都必须返回 `application/json` 或 `application/*+json`，并使用 `code = 200` 的严格平台响应封套。公钥响应示例：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "keyId": "record-platform-proof-2026",
    "keyVersion": 1,
    "algorithm": "EdDSA",
    "publicKeySpki": "<Base64 编码的 X.509 SPKI>",
    "publicKeyFingerprint": "sha256:<64 位小写十六进制>"
  }
}
```

状态响应示例：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "proofId": "rp-proof-<64 位小写十六进制>",
    "status": "ACTIVE",
    "statusVersion": "1",
    "issuedStatus": "ACTIVE",
    "keyId": "record-platform-proof-2026",
    "keyVersion": 1,
    "reason": null,
    "issuedAt": "2026-07-15T01:02:03Z",
    "updatedAt": "2026-07-15T01:02:03Z"
  }
}
```

`statusVersion` 可由后端以 JSON 字符串或整数返回，但必须表示正整数。时间可为 RFC 3339 字符串或 epoch 毫秒整数。`status` 仅接受 `ACTIVE`、`REVOKED`、`SUPERSEDED`、`INVALID`；响应中的 proof/key 身份必须与签名 manifest 完全一致。状态响应不缓存，公钥最多缓存配置的 5 分钟。

`--chain-url-template` 是运维方提供的固定链网关模板，可使用 `{chainType}`、`{chainId}`、`{groupId}`、`{contractAddress}`、`{batchNo}`。网关返回的是直接 JSON 对象，不使用平台响应封套：

```json
{
  "schemaVersion": "record-platform-chain-root-resolution.v1",
  "chainType": "LOCAL_FISCO",
  "chainId": "chain0",
  "groupId": "group0",
  "contractAddress": "0x1111111111111111111111111111111111111111",
  "batchNo": "MB-900",
  "merkleRoot": "<64 位十六进制批次根>",
  "transactionHash": "0x<64 位十六进制交易哈希>",
  "blockNumber": 100
}
```

链响应的 schema、链/组/合约/批次身份、Merkle 根、等价交易哈希和非负区块号必须与签名证据一致。查询型回执没有交易哈希时，响应也必须为 `null` 或缺省。404 表示未知，429/5xx、超时或连接失败表示依赖不可用；这些情况都得到 `INDETERMINATE`，不会降级为 `VALID`。

### 退出码

| 退出码 | 结果 |
| --- | --- |
| `0` | `VALID` |
| `2` | `INVALID` |
| `3` | `INDETERMINATE` |
| `4` | `ERROR` |
| `64` | 命令参数错误 |

自动化脚本应同时检查退出码和 `record-platform-verification-report.v1` 报告，不要把 `INDETERMINATE` 当作成功。

## Web 服务

### 直接运行

```bash
java -jar platform-verifier/web-verifier/target/platform-verifier-web.jar
```

默认监听 `8093`：

- `GET /`：本地静态验证页面；
- `POST /api/v1/verify`：`multipart/form-data`，必填 `original`、`proof`，可选 `trustedKey`；
- `GET /actuator/health`：不披露依赖细节的健康检查。

### 容器

```bash
docker build -f platform-verifier/web-verifier/Dockerfile \
  -t recordplatform-verifier-web .
docker run --rm -p 8093:8093 recordplatform-verifier-web
```

镜像使用非 root 用户。生产运行时应保持根文件系统只读，并为 `/tmp/record-platform-verifier` 提供有容量上限的临时卷。

### 关键配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VERIFIER_PORT` | `8093` | HTTP 端口 |
| `VERIFIER_MAX_ORIGINAL_FILE_BYTES` | `1073741824` | SDK 流式读取的原文件上限 |
| `VERIFIER_MULTIPART_MAX_FILE_SIZE` | `1GB` | Servlet 单 part 上限；应与原文件业务上限保持一致 |
| `VERIFIER_MULTIPART_MAX_REQUEST_SIZE` | `1100MB` | 整个 multipart 请求上限；提高文件上限时必须显式同步提高 |
| `VERIFIER_MAX_CONCURRENT` | `4` | 进程内并发验证许可数 |
| `VERIFIER_ACQUIRE_TIMEOUT` | `100ms` | 等待验证许可的最长时间 |
| `VERIFIER_RATE_LIMIT_REQUESTS` | `20` | 每个直连来源在窗口内的请求数 |
| `VERIFIER_RATE_LIMIT_WINDOW` | `1m` | 限流窗口 |
| `VERIFIER_RATE_LIMIT_MAX_CLIENTS` | `10000` | 内存中保留的直连来源计数器上限 |
| `VERIFIER_ONLINE_ENABLED` | `false` | 是否启用受控在线解析 |
| `VERIFIER_ISSUER_BASE_URI` | 空 | 可信签发方公开 API 基地址 |
| `VERIFIER_CHAIN_URL_TEMPLATE` | 空 | 可信链根网关路径模板 |
| `VERIFIER_ALLOWED_HOSTS` | 空 | 逗号分隔的精确主机白名单 |
| `VERIFIER_ALLOW_HTTP` | `false` | 仅测试环境允许明文 HTTP |
| `VERIFIER_ALLOW_PRIVATE_ADDRESSES` | `false` | 仅测试环境允许私有/回环目标 |
| `VERIFIER_CONNECT_TIMEOUT` | `3s` | 在线解析器建立连接的超时时间 |
| `VERIFIER_REQUEST_TIMEOUT` | `5s` | 在线解析器完整请求的超时时间 |

启用在线模式时，签发方地址、链模板和白名单缺一都会阻止服务启动，避免静默降级为不完整的在线验证。

## 安全和隐私

- ZIP 不解压到文件系统，只读取固定顺序的八个根条目；拒绝压缩、ZIP 注释、扩展字段、前置/尾随字节、间隙、重叠、嵌套、遍历、符号链接和超限条目。
- JSON 开启重复字段、未知字段、尾随 token、文档大小、嵌套深度、字符串和数字长度限制。
- 原文件流式哈希，不整体载入 Java 堆；Web 在延迟解析 multipart 之前先执行限流和并发准入，获准请求才会落临时文件，并在请求结束后清理。
- Web 按直连 socket 地址限流，不信任调用方可伪造的 `X-Forwarded-For`。在反向代理后部署时，应由代理本身实施外层限流。
- 日志和错误响应不包含原文件内容、可信公钥原文、解析后的私钥（验证器不接收私钥）、远端响应体或本地临时路径。
- 签发密钥最多缓存 5 分钟，链根短时缓存；当前撤销状态不缓存。

Servlet multipart 落盘和 SDK 私有副本在单次验证期间可能同时存在，因此临时卷应至少按“最大请求大小 × 并发数 × 2”预留，再增加文件系统与日志余量。临时卷必须设置独立容量上限和宿主机清理策略。

解析器会在请求前拒绝私有/回环/保留地址，但 JVM HTTP 客户端无法把预解析 IP 原子固定到后续连接。生产环境除精确白名单和 HTTPS 外，还应使用容器/主机出口 ACL 阻断元数据地址、内网和其他非预期网段，以覆盖 DNS 重绑定残余风险。

## 回滚

公开验证器没有数据库迁移或持久数据转换。回滚时停止 Web 实例、移除 CLI/SDK 产物并回退对应提交即可；已签发的 v2 证明 ZIP、公开密钥与状态接口保持不变。
