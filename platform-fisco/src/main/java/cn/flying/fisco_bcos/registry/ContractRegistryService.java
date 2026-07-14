package cn.flying.fisco_bcos.registry;

import cn.flying.fisco_bcos.constants.ContractConstants;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.Code;
import org.fisco.bcos.sdk.v3.model.CryptoType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetCode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 启动时把签入 artifact、当前链身份、配置地址和链上 runtime code 组合成不可变注册表。
 */
@Slf4j
@Service
public class ContractRegistryService {

    public static final String ENTRY_SCHEMA = "record-platform-contract-registry-entry.v1";

    private static final String CATALOG_SCHEMA = "record-platform-contract-artifacts.v1";
    private static final int MAX_CATALOG_BYTES = 5 * 1024 * 1024;
    private static final Pattern CONTRACT_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern SEMANTIC_VERSION_PATTERN = Pattern.compile(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?");
    private static final Pattern EFFECTIVE_AT_PATTERN = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                    + "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})");
    private static final Pattern HASH_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("(?i)0x[0-9a-f]{40}");
    private static final Pattern TRANSACTION_HASH_PATTERN = Pattern.compile("(?i)0x[0-9a-f]{64}");
    private static final Pattern CHAIN_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Set<String> ALLOWED_STATUS = Set.of("ACTIVE", "DEPRECATED", "REVOKED");
    private static final Set<String> REQUIRED_CONTRACTS = Set.of("Sharing", "Storage");

    private final ContractRegistryProperties properties;
    private final ContractFingerprintService fingerprintService;
    private final ContractIdentityProbe identityProbe;
    private final ResourceLoader resourceLoader;
    private final Environment environment;
    private final ObjectProvider<Client> clientProvider;
    private final ObjectProvider<Web3j> web3jProvider;

    private Map<String, ContractRegistryEntryResponse> activeEntries = Map.of();

    /**
     * 创建合约注册表服务。
     */
    public ContractRegistryService(
            ContractRegistryProperties properties,
            ContractFingerprintService fingerprintService,
            ContractIdentityProbe identityProbe,
            ResourceLoader resourceLoader,
            Environment environment,
            ObjectProvider<Client> clientProvider,
            ObjectProvider<Web3j> web3jProvider
    ) {
        this.properties = properties;
        this.fingerprintService = fingerprintService;
        this.identityProbe = identityProbe;
        this.resourceLoader = resourceLoader;
        this.environment = environment;
        this.clientProvider = clientProvider;
        this.web3jProvider = web3jProvider;
    }

    /**
     * 启动时完成 catalog、地址、链身份和 runtime code 的 fail-closed 核验。
     */
    @PostConstruct
    public void initialize() {
        ContractArtifactCatalog catalog = loadCatalog();
        validateCatalogHeader(catalog);
        Map<String, ContractArtifactCatalog.ContractArtifact> activeArtifacts =
                selectActiveArtifacts(catalog);
        validatePackagedArtifacts(activeArtifacts.values().stream().toList());

        RuntimeContext runtime = inspectRuntimeContext();
        Map<String, ContractRegistryEntryResponse> resolved = new LinkedHashMap<>();
        Set<String> resolvedAddresses = new LinkedHashSet<>();
        for (String contractName : List.of("Sharing", "Storage")) {
            ContractArtifactCatalog.ContractArtifact artifact = activeArtifacts.get(contractName);
            String address = resolveContractAddress(runtime.mode(), contractName);
            if (!resolvedAddresses.add(address)) {
                throw new IllegalStateException(
                        "Sharing and Storage must use distinct contract addresses");
            }
            String runtimeCode = readRuntimeCode(runtime, address);
            String runtimeCodeSha256 = fingerprintService.fingerprintBytecode(runtimeCode);
            validateContractIdentity(runtime, artifact, address);
            ContractRegistryEntryResponse entry = buildEntry(
                    runtime, artifact, address, runtimeCodeSha256);
            resolved.put(contractName, entry);
            log.info(
                    "合约注册表核验通过: contract={}, version={}, chainType={}, chainId={}, "
                            + "groupId={}, address={}, abiSha256={}, onChainCodeSha256={}",
                    contractName,
                    entry.semanticVersion(),
                    entry.chainType(),
                    entry.chainId(),
                    entry.groupId(),
                    entry.contractAddress(),
                    entry.abiSha256(),
                    entry.onChainCodeSha256());
        }
        this.activeEntries = Map.copyOf(resolved);
    }

    /**
     * 返回指定名称的 ACTIVE 注册表快照。
     *
     * @param contractName 合约名称
     * @return 启动时核验后的不可变条目
     */
    public ContractRegistryEntryResponse getActiveEntry(String contractName) {
        ContractRegistryEntryResponse entry = activeEntries.get(contractName);
        if (entry == null) {
            throw new IllegalStateException("No ACTIVE contract registry entry: " + contractName);
        }
        return entry;
    }

    /**
     * 返回全部 ACTIVE 注册表条目，顺序固定为 Sharing、Storage。
     */
    public List<ContractRegistryEntryResponse> getActiveEntries() {
        return List.of(getActiveEntry("Sharing"), getActiveEntry("Storage"));
    }

    /**
     * 校验调用方快照与当前 ACTIVE 合约完全一致，防止静默切换地址或 ABI。
     *
     * @param expected 调用方持久化的快照
     * @param contractName 预期合约名称
     */
    public void requireActiveReference(
            ContractRegistryEntryResponse expected,
            String contractName
    ) {
        ContractRegistryEntryResponse actual = getActiveEntry(contractName);
        if (expected == null) {
            throw new IllegalArgumentException("Contract registry snapshot is required");
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Contract registry snapshot mismatch for " + contractName
                            + ": expected fingerprint=" + expected.registryFingerprint()
                            + ", active fingerprint=" + actual.registryFingerprint());
        }
    }

    /**
     * 从大小受限的资源读取严格 catalog。
     */
    private ContractArtifactCatalog loadCatalog() {
        String location = properties.getCatalogLocation();
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("contract-registry.catalog-location must be configured");
        }
        Resource resource = resourceLoader.getResource(location);
        try (InputStream inputStream = resource.getInputStream()) {
            byte[] bytes = inputStream.readNBytes(MAX_CATALOG_BYTES + 1);
            if (bytes.length == 0) {
                throw new IllegalStateException("Contract artifact catalog is empty: " + location);
            }
            if (bytes.length > MAX_CATALOG_BYTES) {
                throw new IllegalStateException("Contract artifact catalog exceeds size limit");
            }
            return fingerprintService.readCatalog(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read contract artifact catalog: " + location, e);
        }
    }

    /**
     * 校验 catalog schema、算法声明和条目基础字段。
     */
    private void validateCatalogHeader(ContractArtifactCatalog catalog) {
        if (catalog == null
                || !CATALOG_SCHEMA.equals(catalog.schemaVersion())
                || !ContractFingerprintService.ABI_ALGORITHM.equals(
                        catalog.abiFingerprintAlgorithm())
                || !ContractFingerprintService.BYTECODE_ALGORITHM.equals(
                        catalog.bytecodeFingerprintAlgorithm())
                || !ContractFingerprintService.SOURCE_ALGORITHM.equals(
                        catalog.sourceFingerprintAlgorithm())
                || catalog.contracts() == null
                || catalog.contracts().isEmpty()) {
            throw new IllegalStateException("Unsupported or empty contract artifact catalog");
        }
        Set<String> identities = new LinkedHashSet<>();
        for (ContractArtifactCatalog.ContractArtifact artifact : catalog.contracts()) {
            validateArtifactMetadata(artifact);
            String identity = artifact.contractName() + "\u0000" + artifact.semanticVersion();
            if (!identities.add(identity)) {
                throw new IllegalStateException(
                        "Duplicate catalog contract entry: "
                                + artifact.contractName() + "@" + artifact.semanticVersion());
            }
        }
    }

    /**
     * 校验每个 artifact 的稳定身份与生命周期元数据。
     */
    private void validateArtifactMetadata(ContractArtifactCatalog.ContractArtifact artifact) {
        if (artifact == null
                || artifact.contractName() == null
                || !CONTRACT_NAME_PATTERN.matcher(artifact.contractName()).matches()
                || artifact.semanticVersion() == null
                || !SEMANTIC_VERSION_PATTERN.matcher(artifact.semanticVersion()).matches()
                || !ALLOWED_STATUS.contains(artifact.status())
                || !"REDEPLOY_ADDRESS".equals(artifact.upgradeStrategy())
                || !hasValidArtifactPaths(artifact)
                || artifact.bytecodeSha256() == null
                || !artifact.bytecodeSha256().keySet().equals(Set.of("ecc", "sm"))) {
            throw new IllegalStateException("Invalid contract artifact metadata");
        }
        validateHash(artifact.sourceSha256(), artifact.contractName() + ".sourceSha256");
        validateHash(artifact.abiSha256(), artifact.contractName() + ".abiSha256");
        validateHash(
                artifact.bytecodeSha256().get("ecc"),
                artifact.contractName() + ".bytecodeSha256.ecc");
        validateHash(
                artifact.bytecodeSha256().get("sm"),
                artifact.contractName() + ".bytecodeSha256.sm");
        if (artifact.effectiveAt() == null
                || !EFFECTIVE_AT_PATTERN.matcher(artifact.effectiveAt()).matches()) {
            throw new IllegalStateException(
                    "Invalid effectiveAt for " + artifact.contractName());
        }
        try {
            OffsetDateTime effectiveAt = OffsetDateTime.parse(artifact.effectiveAt());
            if ("ACTIVE".equals(artifact.status()) && effectiveAt.isAfter(OffsetDateTime.now())) {
                throw new IllegalStateException(
                        "ACTIVE artifact is not yet effective: " + artifact.contractName());
            }
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalStateException(
                    "Invalid effectiveAt for " + artifact.contractName(), e);
        }
    }

    /**
     * 校验源码、ABI 和 ECC/SM bytecode 路径均为安全相对路径，且源码副本互不重复。
     */
    private boolean hasValidArtifactPaths(ContractArtifactCatalog.ContractArtifact artifact) {
        if (artifact.sourcePaths() == null || artifact.sourcePaths().size() < 2) {
            return false;
        }
        Set<String> normalizedSourcePaths = new LinkedHashSet<>();
        for (String sourcePath : artifact.sourcePaths()) {
            String normalized = normalizeArtifactPath(sourcePath);
            if (normalized == null || !normalizedSourcePaths.add(normalized)) {
                return false;
            }
        }
        if (normalizeArtifactPath(artifact.abiPath()) == null
                || artifact.bytecodePaths() == null
                || !artifact.bytecodePaths().keySet().equals(Set.of("ecc", "sm"))) {
            return false;
        }
        return artifact.bytecodePaths().values().stream()
                .allMatch(path -> normalizeArtifactPath(path) != null);
    }

    /**
     * 规范化 catalog 相对路径，并拒绝绝对路径、空路径和目录穿越。
     */
    private String normalizeArtifactPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(value).normalize();
            if (path.isAbsolute() || path.toString().isBlank() || path.startsWith("..")) {
                return null;
            }
            return path.toString();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 用 Java 重算当前 ACTIVE ABI 和 ECC/SM bytecode；历史制品由 catalog CI 校验并保留。
     */
    private void validatePackagedArtifacts(
            List<ContractArtifactCatalog.ContractArtifact> activeArtifacts
    ) {
        for (ContractArtifactCatalog.ContractArtifact artifact : activeArtifacts) {
            String contractName = artifact.contractName();
            String abi = packagedAbi(contractName);
            String eccBytecode = packagedBytecode(contractName, "ecc");
            String smBytecode = packagedBytecode(contractName, "sm");
            requireEqualFingerprint(
                    artifact.abiSha256(),
                    fingerprintService.fingerprintAbi(abi),
                    contractName + " ABI");
            requireEqualFingerprint(
                    artifact.bytecodeSha256().get("ecc"),
                    fingerprintService.fingerprintBytecode(eccBytecode),
                    contractName + " ECC bytecode");
            requireEqualFingerprint(
                    artifact.bytecodeSha256().get("sm"),
                    fingerprintService.fingerprintBytecode(smBytecode),
                    contractName + " SM bytecode");
        }
    }

    /**
     * 为每个必需合约选择且仅选择一个 ACTIVE artifact。
     */
    private Map<String, ContractArtifactCatalog.ContractArtifact> selectActiveArtifacts(
            ContractArtifactCatalog catalog
    ) {
        Map<String, ContractArtifactCatalog.ContractArtifact> selected = new LinkedHashMap<>();
        for (ContractArtifactCatalog.ContractArtifact artifact : catalog.contracts()) {
            if (!"ACTIVE".equals(artifact.status())) {
                continue;
            }
            ContractArtifactCatalog.ContractArtifact previous =
                    selected.putIfAbsent(artifact.contractName(), artifact);
            if (previous != null) {
                throw new IllegalStateException(
                        "Multiple ACTIVE artifacts for " + artifact.contractName());
            }
        }
        if (!selected.keySet().containsAll(REQUIRED_CONTRACTS)) {
            throw new IllegalStateException("Sharing and Storage must each have one ACTIVE artifact");
        }
        return selected;
    }

    /**
     * 从当前激活链客户端读取真实 chain/group/crypto 身份。
     */
    private RuntimeContext inspectRuntimeContext() {
        String mode = environment.getProperty("blockchain.active", "local-fisco");
        return switch (mode) {
            case "local-fisco", "bsn-fisco" -> inspectFiscoRuntime(mode);
            case "bsn-besu" -> inspectBesuRuntime(mode);
            default -> throw new IllegalStateException("Unsupported blockchain.active: " + mode);
        };
    }

    /**
     * 读取 FISCO 客户端链身份并核对配置群组。
     */
    private RuntimeContext inspectFiscoRuntime(String mode) {
        Client client = requireBean(clientProvider, "FISCO Client");
        String chainId = requireChainIdentifier(client.getChainId(), "FISCO chainId");
        String groupId = requireChainIdentifier(client.getGroup(), "FISCO groupId");
        String configuredChainId = "bsn-fisco".equals(mode)
                ? environment.getProperty("blockchain.bsn-fisco.chain-id")
                : firstConfiguredProperty("system.chainId", "system.chain-id", null);
        configuredChainId = requireChainIdentifier(
                configuredChainId,
                "configured FISCO chainId");
        if (!chainId.equals(configuredChainId)) {
            throw new IllegalStateException(
                    "FISCO chainId mismatch: configured=" + configuredChainId
                            + ", actual=" + chainId);
        }
        String configuredGroup = "bsn-fisco".equals(mode)
                ? environment.getProperty("blockchain.bsn-fisco.group-id", "group0")
                : firstConfiguredProperty("system.groupId", "system.group-id", "group0");
        if (!groupId.equals(configuredGroup)) {
            throw new IllegalStateException(
                    "FISCO group mismatch: configured=" + configuredGroup + ", actual=" + groupId);
        }
        String cryptoType = client.getCryptoType() == CryptoType.SM_TYPE ? "sm" : "ecc";
        String chainType = "bsn-fisco".equals(mode) ? "BSN_FISCO" : "LOCAL_FISCO";
        return new RuntimeContext(mode, chainType, chainId, groupId, cryptoType, client, null);
    }

    /**
     * 读取 Besu 节点链 ID 并核对配置链 ID。
     */
    private RuntimeContext inspectBesuRuntime(String mode) {
        Web3j web3j = requireBean(web3jProvider, "Web3j");
        try {
            EthChainId response = web3j.ethChainId().send();
            if (response == null || response.hasError() || response.getChainId() == null) {
                throw new IllegalStateException("Cannot read Besu chainId");
            }
            String chainId = requireChainIdentifier(response.getChainId().toString(), "Besu chainId");
            String configuredChainId = environment.getProperty("blockchain.bsn-besu.chain-id");
            if (configuredChainId == null || configuredChainId.isBlank()) {
                throw new IllegalStateException("blockchain.bsn-besu.chain-id must be configured");
            }
            BigInteger configured = new BigInteger(configuredChainId);
            if (!response.getChainId().equals(configured)) {
                throw new IllegalStateException(
                        "Besu chainId mismatch: configured=" + configured + ", actual=" + chainId);
            }
            return new RuntimeContext(mode, "BSN_BESU", chainId, null, "ecc", null, web3j);
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("Cannot verify Besu chain identity", e);
        }
    }

    /**
     * 从当前模式唯一允许的配置命名空间解析合约地址。
     */
    private String resolveContractAddress(String mode, String contractName) {
        String suffix = contractName.toLowerCase(Locale.ROOT);
        String address = switch (mode) {
            case "local-fisco" -> firstConfiguredProperty(
                    "contract." + suffix + "Address",
                    "contract." + suffix + "-address",
                    null);
            case "bsn-fisco" -> environment.getProperty(
                    "blockchain.bsn-fisco.contracts." + suffix);
            case "bsn-besu" -> environment.getProperty(
                    "blockchain.bsn-besu.contracts." + suffix);
            default -> null;
        };
        if (address == null || !ADDRESS_PATTERN.matcher(address.trim()).matches()) {
            throw new IllegalStateException(
                    "Missing or invalid " + mode + " " + contractName + " contract address");
        }
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        if ("0x0000000000000000000000000000000000000000".equals(normalized)) {
            throw new IllegalStateException(contractName + " contract address must not be zero");
        }
        return normalized;
    }

    /**
     * 从节点读取目标地址的 runtime code；空代码或 RPC 错误立即失败。
     */
    private String readRuntimeCode(RuntimeContext runtime, String address) {
        if (runtime.client() != null) {
            Code response = runtime.client().getCode(address);
            if (response == null
                    || response.hasError()
                    || response.getCode() == null
                    || isEmptyCode(response.getCode())) {
                throw new IllegalStateException("No FISCO runtime code at " + address);
            }
            return response.getCode();
        }
        try {
            EthGetCode response = runtime.web3j()
                    .ethGetCode(address, DefaultBlockParameterName.LATEST)
                    .send();
            if (response == null || response.hasError() || isEmptyCode(response.getCode())) {
                throw new IllegalStateException("No Besu runtime code at " + address);
            }
            return response.getCode();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Besu runtime code at " + address, e);
        }
    }

    /**
     * 只读调用链上 contractIdentity，并与 catalog 名称和版本严格对账。
     */
    private void validateContractIdentity(
            RuntimeContext runtime,
            ContractArtifactCatalog.ContractArtifact artifact,
            String address
    ) {
        ContractIdentityProbe.ContractIdentity identity = runtime.client() != null
                ? identityProbe.inspectFisco(
                        runtime.client(),
                        address,
                        packagedAbi(artifact.contractName()))
                : identityProbe.inspectBesu(runtime.web3j(), address);
        if (identity == null) {
            throw new IllegalStateException(
                    "Contract identity probe returned no result at " + address);
        }
        if (!artifact.contractName().equals(identity.contractName())
                || !artifact.semanticVersion().equals(identity.semanticVersion())) {
            throw new IllegalStateException(
                    "Contract identity mismatch at " + address
                            + ": expected=" + artifact.contractName()
                            + "/" + artifact.semanticVersion()
                            + ", actual=" + identity.contractName()
                            + "/" + identity.semanticVersion());
        }
    }

    /**
     * 组合 artifact 与实际链证据，并生成注册表条目指纹。
     */
    private ContractRegistryEntryResponse buildEntry(
            RuntimeContext runtime,
            ContractArtifactCatalog.ContractArtifact artifact,
            String address,
            String runtimeCodeSha256
    ) {
        ContractRegistryProperties.DeploymentEvidence deployment =
                deploymentEvidence(artifact.contractName());
        ResolvedDeploymentEvidence resolvedDeployment = validateDeploymentEvidence(
                deployment,
                artifact);
        String artifactBytecode = artifact.bytecodeSha256().get(runtime.cryptoType());
        return new ContractRegistryEntryResponse(
                ENTRY_SCHEMA,
                null,
                artifact.contractName(),
                artifact.semanticVersion(),
                runtime.chainType(),
                runtime.chainId(),
                runtime.groupId(),
                address,
                ContractFingerprintService.ABI_ALGORITHM,
                artifact.abiSha256(),
                artifactBytecode,
                runtimeCodeSha256,
                resolvedDeployment.transactionHash(),
                resolvedDeployment.blockNumber(),
                artifact.status(),
                resolvedDeployment.effectiveAt(),
                artifact.upgradeStrategy())
                .withCalculatedRegistryFingerprint();
    }

    /**
     * 根据合约名返回可选部署交易证据。
     */
    private ContractRegistryProperties.DeploymentEvidence deploymentEvidence(String contractName) {
        if (properties.getDeployment() == null) {
            throw new IllegalStateException("Missing contract deployment evidence configuration");
        }
        return switch (contractName) {
            case "Sharing" -> properties.getDeployment().getSharing();
            case "Storage" -> properties.getDeployment().getStorage();
            default -> throw new IllegalStateException("Unsupported contract: " + contractName);
        };
    }

    /**
     * 部署交易哈希、区块号和生效时间必须同时提供；
     * legacy 三项均缺省时回退 artifact 时间。
     */
    private ResolvedDeploymentEvidence validateDeploymentEvidence(
            ContractRegistryProperties.DeploymentEvidence evidence,
            ContractArtifactCatalog.ContractArtifact artifact
    ) {
        String contractName = artifact.contractName();
        if (evidence == null) {
            throw new IllegalStateException("Missing deployment evidence holder for " + contractName);
        }
        String transactionHash = emptyToNull(evidence.getTransactionHash());
        Long blockNumber = evidence.getBlockNumber();
        String effectiveAt = emptyToNull(evidence.getEffectiveAt());
        boolean transactionPresent = transactionHash != null;
        boolean blockPresent = blockNumber != null;
        boolean effectiveAtPresent = effectiveAt != null;
        if (transactionPresent != blockPresent || transactionPresent != effectiveAtPresent) {
            throw new IllegalStateException(
                    contractName
                            + " deployment transaction hash, block number and effectiveAt"
                            + " must be provided together");
        }
        if (transactionHash != null
                && !TRANSACTION_HASH_PATTERN.matcher(transactionHash).matches()) {
            throw new IllegalStateException("Invalid deployment transaction hash for " + contractName);
        }
        if (blockNumber != null && blockNumber < 0) {
            throw new IllegalStateException("Invalid deployment block number for " + contractName);
        }
        if (!transactionPresent) {
            return new ResolvedDeploymentEvidence(null, null, artifact.effectiveAt());
        }
        try {
            OffsetDateTime artifactTime = OffsetDateTime.parse(artifact.effectiveAt());
            OffsetDateTime deploymentTime = OffsetDateTime.parse(effectiveAt);
            if (deploymentTime.toInstant().isBefore(artifactTime.toInstant())) {
                throw new IllegalStateException(
                        contractName + " deployment effectiveAt predates artifact effectiveAt");
            }
            if (deploymentTime.toInstant().isAfter(OffsetDateTime.now().toInstant())) {
                throw new IllegalStateException(
                        contractName + " deployment effectiveAt is in the future");
            }
            return new ResolvedDeploymentEvidence(
                    transactionHash.toLowerCase(Locale.ROOT),
                    blockNumber,
                    deploymentTime.toInstant().toString());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "Invalid deployment effectiveAt for " + contractName,
                    e);
        }
    }

    /**
     * 返回打包 ABI 文本。
     */
    private String packagedAbi(String contractName) {
        return switch (contractName) {
            case "Sharing" -> ContractConstants.SharingAbi;
            case "Storage" -> ContractConstants.StorageAbi;
            default -> throw new IllegalStateException("Unsupported packaged ABI: " + contractName);
        };
    }

    /**
     * 返回打包的 ECC 或 SM creation bytecode。
     */
    private String packagedBytecode(String contractName, String cryptoType) {
        return switch (contractName + ":" + cryptoType) {
            case "Sharing:ecc" -> ContractConstants.SharingBinary;
            case "Sharing:sm" -> ContractConstants.SharingGmBinary;
            case "Storage:ecc" -> ContractConstants.StorageBinary;
            case "Storage:sm" -> ContractConstants.StorageGmBinary;
            default -> throw new IllegalStateException(
                    "Unsupported packaged bytecode: " + contractName + "/" + cryptoType);
        };
    }

    /**
     * 校验 SHA-256 字段文本格式。
     */
    private void validateHash(String hash, String fieldName) {
        if (hash == null || !HASH_PATTERN.matcher(hash).matches()) {
            throw new IllegalStateException("Invalid " + fieldName);
        }
    }

    /**
     * 比较签入指纹与运行时重算值。
     */
    private void requireEqualFingerprint(String expected, String actual, String artifactName) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                    artifactName + " fingerprint drift: expected=" + expected + ", actual=" + actual);
        }
    }

    /**
     * 获取必须存在的单个运行时 bean。
     */
    private <T> T requireBean(ObjectProvider<T> provider, String beanName) {
        T bean = provider.getIfAvailable();
        if (bean == null) {
            throw new IllegalStateException(beanName + " is unavailable for active blockchain mode");
        }
        return bean;
    }

    /**
     * 校验链或群组标识不含控制字符并限制长度。
     */
    private String requireChainIdentifier(String value, String fieldName) {
        if (value == null || !CHAIN_IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException("Invalid " + fieldName);
        }
        return value;
    }

    /**
     * 按顺序读取 relaxed-binding 兼容的配置 key。
     */
    private String firstConfiguredProperty(String first, String second, String defaultValue) {
        String value = environment.getProperty(first);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(second);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 判断 RPC 返回是否表示无合约代码。
     */
    private boolean isEmptyCode(String code) {
        if (code == null) {
            return true;
        }
        String compact = code.replaceAll("\\s+", "");
        return compact.isEmpty() || "0x".equalsIgnoreCase(compact) || "0x0".equalsIgnoreCase(compact);
    }

    /**
     * 把空白文本规范为 null。
     */
    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 把可空文本规范为用于稳定 payload 的空串。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 当前链连接与指纹选择所需的运行时上下文。
     */
    private record RuntimeContext(
            String mode,
            String chainType,
            String chainId,
            String groupId,
            String cryptoType,
            Client client,
            Web3j web3j
    ) {
    }

    /**
     * 表示规范化后的可选部署证据和 registry 生效时间。
     */
    private record ResolvedDeploymentEvidence(
            String transactionHash,
            Long blockNumber,
            String effectiveAt
    ) {
    }
}
