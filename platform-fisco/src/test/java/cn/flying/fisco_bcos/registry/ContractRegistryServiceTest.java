package cn.flying.fisco_bcos.registry;

import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.Code;
import org.fisco.bcos.sdk.v3.model.CryptoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetCode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractRegistryServiceTest {

    private static final String SHARING_ADDRESS =
            "0x1111111111111111111111111111111111111111";
    private static final String STORAGE_ADDRESS =
            "0x2222222222222222222222222222222222222222";

    @Mock
    private Client client;

    @Mock
    private Code codeResponse;

    @Mock
    private ObjectProvider<Client> clientProvider;

    @Mock
    private ObjectProvider<Web3j> web3jProvider;

    @Mock
    private ContractIdentityProbe identityProbe;

    @Mock
    private Web3j web3j;

    @Mock
    private Request<?, EthChainId> chainIdRequest;

    @Mock
    private EthChainId chainIdResponse;

    @Mock
    private Request<?, EthGetCode> getCodeRequest;

    @Mock
    private EthGetCode getCodeResponse;

    private MockEnvironment environment;
    private ContractRegistryService service;

    @TempDir
    private Path tempDirectory;

    /**
     * 构造可重复的本地 FISCO 链身份和 runtime code 响应。
     */
    @BeforeEach
    void setUp() {
        environment = new MockEnvironment()
                .withProperty("blockchain.active", "local-fisco")
                .withProperty("system.chainId", "chain0")
                .withProperty("system.groupId", "group0")
                .withProperty("contract.sharingAddress", SHARING_ADDRESS)
                .withProperty("contract.storageAddress", STORAGE_ADDRESS);
        lenient().when(clientProvider.getIfAvailable()).thenReturn(client);
        lenient().when(client.getChainId()).thenReturn("chain0");
        lenient().when(client.getGroup()).thenReturn("group0");
        lenient().when(client.getCryptoType()).thenReturn(CryptoType.ECDSA_TYPE);
        lenient().when(client.getCode(anyString())).thenReturn(codeResponse);
        lenient().when(codeResponse.getCode()).thenReturn("0x60006000");
        lenient().when(identityProbe.inspectFisco(
                        eq(client),
                        eq(SHARING_ADDRESS),
                        anyString()))
                .thenReturn(new ContractIdentityProbe.ContractIdentity("Sharing", "2.0.0"));
        lenient().when(identityProbe.inspectFisco(
                        eq(client),
                        eq(STORAGE_ADDRESS),
                        anyString()))
                .thenReturn(new ContractIdentityProbe.ContractIdentity("Storage", "1.0.0"));
        lenient().when(identityProbe.inspectBesu(web3j, SHARING_ADDRESS))
                .thenReturn(new ContractIdentityProbe.ContractIdentity("Sharing", "2.0.0"));
        lenient().when(identityProbe.inspectBesu(web3j, STORAGE_ADDRESS))
                .thenReturn(new ContractIdentityProbe.ContractIdentity("Storage", "1.0.0"));
        service = newService();
    }

    /**
     * 验证启动后生成包含真实链、地址、ABI 和 runtime code 的不可变快照。
     */
    @Test
    void shouldBuildVerifiedActiveEntries() {
        service.initialize();

        ContractRegistryEntryResponse sharing = service.getActiveEntry("Sharing");
        assertThat(service.getActiveEntries()).hasSize(2);
        assertThat(sharing.schemaVersion()).isEqualTo(ContractRegistryService.ENTRY_SCHEMA);
        assertThat(sharing.contractAddress()).isEqualTo(SHARING_ADDRESS);
        assertThat(sharing.chainType()).isEqualTo("LOCAL_FISCO");
        assertThat(sharing.chainId()).isEqualTo("chain0");
        assertThat(sharing.groupId()).isEqualTo("group0");
        assertThat(sharing.abiSha256()).startsWith("sha256:");
        assertThat(sharing.onChainCodeSha256()).startsWith("sha256:");
        assertThat(sharing.registryFingerprint()).startsWith("sha256:");
        assertThat(sharing.effectiveAt()).isEqualTo("2026-07-13T00:00:00Z");
        service.requireActiveReference(sharing, "Sharing");
    }

    /**
     * 验证历史 DEPRECATED 制品可保留，而运行时只把唯一 ACTIVE 制品绑定到当前地址。
     */
    @Test
    void shouldRetainHistoricalArtifactsWithoutTreatingThemAsActivePackages() {
        ContractRegistryProperties properties = new ContractRegistryProperties();
        properties.setCatalogLocation(
                "classpath:contract-registry/artifacts-with-history.json");
        service = newService(properties);

        service.initialize();

        assertThat(service.getActiveEntry("Sharing").semanticVersion()).isEqualTo("2.0.0");
        assertThat(service.getActiveEntry("Sharing").status()).isEqualTo("ACTIVE");
    }

    /**
     * 验证两个文本写法指向同一文件时不能伪装成双份源码证据。
     */
    @Test
    void shouldRejectDuplicateNormalizedSourcePaths() throws IOException {
        Path catalogPath = writeCatalogWithReplacement(
                "platform-fisco/src/main/contracts/Sharing.sol",
                "./platform-fisco/contract/Sharing.sol");
        ContractRegistryProperties properties = new ContractRegistryProperties();
        properties.setCatalogLocation(catalogPath.toUri().toString());
        service = newService(properties);

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid contract artifact metadata");
    }

    /**
     * 验证 Python 可解析但 Java 不接受的空格分隔时间在运行时也明确失败。
     */
    @Test
    void shouldRejectNonRfc3339ArtifactEffectiveAt() throws IOException {
        Path catalogPath = writeCatalogWithReplacement(
                "2026-07-13T00:00:00Z",
                "2026-07-13 00:00:00+00:00");
        ContractRegistryProperties properties = new ContractRegistryProperties();
        properties.setCatalogLocation(catalogPath.toUri().toString());
        service = newService(properties);

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid effectiveAt");
    }

    /**
     * 验证节点地址没有部署代码时服务启动失败。
     */
    @Test
    void shouldRejectAddressWithoutRuntimeCode() {
        when(codeResponse.getCode()).thenReturn("0x");

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FISCO runtime code");
    }

    /**
     * 验证 FISCO getCode 返回 RPC error 时即使携带文本也不能继续身份探测。
     */
    @Test
    void shouldRejectFiscoRuntimeCodeRpcError() {
        when(codeResponse.hasError()).thenReturn(true);

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FISCO runtime code");
    }

    /**
     * 验证配置群组与节点实际群组不一致时服务启动失败。
     */
    @Test
    void shouldRejectGroupMismatch() {
        environment.setProperty("system.groupId", "group1");

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FISCO group mismatch");
    }

    /**
     * 验证本地 FISCO 配置 chain ID 与节点身份不一致时启动失败。
     */
    @Test
    void shouldRejectLocalFiscoChainIdMismatch() {
        environment.setProperty("system.chainId", "chain1");

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FISCO chainId mismatch");
    }

    /**
     * 验证本地 FISCO 未配置期望 chain ID 时在读取代码和身份前失败关闭。
     */
    @Test
    void shouldRejectMissingLocalFiscoChainIdBeforeContractInspection() {
        environment = new MockEnvironment()
                .withProperty("blockchain.active", "local-fisco")
                .withProperty("system.groupId", "group0")
                .withProperty("contract.sharingAddress", SHARING_ADDRESS)
                .withProperty("contract.storageAddress", STORAGE_ADDRESS);
        service = newService();

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured FISCO chainId");
        verify(client, never()).getCode(anyString());
        verify(identityProbe, never()).inspectFisco(any(), anyString(), anyString());
    }

    /**
     * 验证调用方不能用伪造或旧注册表指纹继续链写。
     */
    @Test
    void shouldRejectStaleRegistryReference() {
        service.initialize();
        ContractRegistryEntryResponse active = service.getActiveEntry("Sharing");
        ContractRegistryEntryResponse stale = new ContractRegistryEntryResponse(
                active.schemaVersion(),
                "sha256:" + "0".repeat(64),
                active.contractName(),
                active.semanticVersion(),
                active.chainType(),
                active.chainId(),
                active.groupId(),
                active.contractAddress(),
                active.abiFingerprintAlgorithm(),
                active.abiSha256(),
                active.artifactBytecodeSha256(),
                active.onChainCodeSha256(),
                active.deploymentTransactionHash(),
                active.deploymentBlockNumber(),
                active.status(),
                active.effectiveAt(),
                active.upgradeStrategy());

        assertThatThrownBy(() -> service.requireActiveReference(stale, "Sharing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot mismatch");
    }

    /**
     * 验证 BSN FISCO 模式只读取该模式命名空间中的地址，不会回退到本地地址。
     */
    @Test
    void shouldUseBsnFiscoAddressNamespace() {
        String bsnSharing = "0x5555555555555555555555555555555555555555";
        String bsnStorage = "0x6666666666666666666666666666666666666666";
        environment.setProperty("blockchain.active", "bsn-fisco");
        environment.setProperty("blockchain.bsn-fisco.chain-id", "chain0");
        environment.setProperty("blockchain.bsn-fisco.group-id", "group0");
        environment.setProperty("blockchain.bsn-fisco.contracts.sharing", bsnSharing);
        environment.setProperty("blockchain.bsn-fisco.contracts.storage", bsnStorage);
        when(identityProbe.inspectFisco(eq(client), eq(bsnSharing), anyString()))
                .thenReturn(new ContractIdentityProbe.ContractIdentity("Sharing", "2.0.0"));
        when(identityProbe.inspectFisco(eq(client), eq(bsnStorage), anyString()))
                .thenReturn(new ContractIdentityProbe.ContractIdentity("Storage", "1.0.0"));
        service = newService();

        service.initialize();

        assertThat(service.getActiveEntry("Sharing").contractAddress()).isEqualTo(bsnSharing);
        assertThat(service.getActiveEntry("Storage").contractAddress()).isEqualTo(bsnStorage);
        assertThat(service.getActiveEntry("Sharing").chainType()).isEqualTo("BSN_FISCO");
    }

    /**
     * 验证 BSN FISCO 未显式配置期望 chain ID 时启动失败。
     */
    @Test
    void shouldRejectMissingBsnFiscoChainId() {
        environment.setProperty("blockchain.active", "bsn-fisco");
        environment.setProperty("blockchain.bsn-fisco.group-id", "group0");
        environment.setProperty("blockchain.bsn-fisco.contracts.sharing", SHARING_ADDRESS);
        environment.setProperty("blockchain.bsn-fisco.contracts.storage", STORAGE_ADDRESS);
        service = newService();

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured FISCO chainId");
    }

    /**
     * 验证 BSN FISCO 期望 chain ID 与节点身份不一致时启动失败。
     */
    @Test
    void shouldRejectBsnFiscoChainIdMismatch() {
        environment.setProperty("blockchain.active", "bsn-fisco");
        environment.setProperty("blockchain.bsn-fisco.chain-id", "chain1");
        environment.setProperty("blockchain.bsn-fisco.group-id", "group0");
        environment.setProperty("blockchain.bsn-fisco.contracts.sharing", SHARING_ADDRESS);
        environment.setProperty("blockchain.bsn-fisco.contracts.storage", STORAGE_ADDRESS);
        service = newService();

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FISCO chainId mismatch");
    }

    /**
     * 验证任一地址返回的名称或版本与 catalog 不一致时不会发布任何 ACTIVE 条目。
     */
    @Test
    void shouldRejectIdentityMismatchWithoutPartialPublication() {
        when(identityProbe.inspectFisco(eq(client), eq(STORAGE_ADDRESS), anyString()))
                .thenReturn(new ContractIdentityProbe.ContractIdentity("Sharing", "2.0.0"));

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contract identity mismatch");
        assertThatThrownBy(() -> service.getActiveEntry("Sharing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ACTIVE contract registry entry");
    }

    /**
     * 验证身份只读调用错误会原样阻断 registry 初始化。
     */
    @Test
    void shouldRejectIdentityProbeFailure() {
        when(identityProbe.inspectFisco(eq(client), eq(SHARING_ADDRESS), anyString()))
                .thenThrow(new IllegalStateException("identity RPC failed"));

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity RPC failed");
    }

    /**
     * 验证身份探测器异常返回空结果时关闭启动，且不会发布部分注册表。
     */
    @Test
    void shouldRejectMissingIdentityWithoutPartialPublication() {
        when(identityProbe.inspectFisco(eq(client), eq(STORAGE_ADDRESS), anyString()))
                .thenReturn(null);

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contract identity probe returned no result");
        assertThatThrownBy(() -> service.getActiveEntry("Sharing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ACTIVE contract registry entry");
    }

    /**
     * 验证完整部署三元组写入 registry，并把时间规范为 UTC instant。
     */
    @Test
    void shouldUseCompleteDeploymentEvidenceEffectiveAt() {
        ContractRegistryProperties properties = completeDeploymentProperties(
                "2026-07-13T08:30:00+08:00");
        service = newService(properties);

        service.initialize();

        ContractRegistryEntryResponse sharing = service.getActiveEntry("Sharing");
        assertThat(sharing.deploymentTransactionHash())
                .isEqualTo("0x" + "a".repeat(64));
        assertThat(sharing.deploymentBlockNumber()).isEqualTo(101L);
        assertThat(sharing.effectiveAt()).isEqualTo("2026-07-13T00:30:00Z");
    }

    /**
     * 验证 tx、block、effectiveAt 任一缺失时部署证据不会被部分接受。
     */
    @Test
    void shouldRejectPartialDeploymentEvidence() {
        ContractRegistryProperties properties = new ContractRegistryProperties();
        properties.getDeployment().getSharing().setTransactionHash("0x" + "a".repeat(64));
        properties.getDeployment().getSharing().setBlockNumber(101L);
        service = newService(properties);

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be provided together");
    }

    /**
     * 验证部署时间不能早于对应 artifact 的生命周期起点。
     */
    @Test
    void shouldRejectDeploymentEffectiveAtBeforeArtifact() {
        ContractRegistryProperties properties = completeDeploymentProperties(
                "2026-07-12T23:59:59Z");
        service = newService(properties);

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predates artifact effectiveAt");
    }

    /**
     * 验证部署时间必须是可解析的 ISO-8601 offset date-time。
     */
    @Test
    void shouldRejectInvalidDeploymentEffectiveAt() {
        ContractRegistryProperties properties = completeDeploymentProperties("not-a-time");
        service = newService(properties);

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid deployment effectiveAt");
    }

    /**
     * 验证未来部署时间不会被声明为当前 ACTIVE registry。
     */
    @Test
    void shouldRejectFutureDeploymentEffectiveAt() {
        ContractRegistryProperties properties = completeDeploymentProperties(
                "2999-01-01T00:00:00Z");
        service = newService(properties);

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deployment effectiveAt is in the future");
    }

    /**
     * 验证配置地址格式错误或零地址时在任何链调用之前失败关闭。
     */
    @Test
    void shouldRejectInvalidConfiguredAddress() {
        environment.setProperty("contract.sharingAddress", "0x0");
        service = newService();

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid local-fisco Sharing contract address");
    }

    /**
     * 验证继承关系不会让 Sharing 与 Storage 误绑定到同一地址。
     */
    @Test
    void shouldRejectDuplicateContractAddresses() {
        environment.setProperty("contract.storageAddress", SHARING_ADDRESS);
        service = newService();

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use distinct contract addresses");
    }

    /**
     * 验证 Besu 模式会核对 eth_chainId、使用 Besu 地址并读取 runtime code。
     */
    @Test
    void shouldBuildVerifiedBesuEntries() throws IOException {
        configureBesu("1337", BigInteger.valueOf(1337));
        service = newService();

        service.initialize();

        ContractRegistryEntryResponse sharing = service.getActiveEntry("Sharing");
        assertThat(sharing.chainType()).isEqualTo("BSN_BESU");
        assertThat(sharing.chainId()).isEqualTo("1337");
        assertThat(sharing.groupId()).isNull();
        assertThat(sharing.contractAddress()).isEqualTo(SHARING_ADDRESS);
        assertThat(sharing.onChainCodeSha256()).startsWith("sha256:");
    }

    /**
     * 验证 Besu 配置 chain ID 与节点响应不一致时不会生成 ACTIVE registry。
     */
    @Test
    void shouldRejectBesuChainIdMismatch() throws IOException {
        configureBesu("1338", BigInteger.valueOf(1337));
        service = newService();

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Besu chainId mismatch");
    }

    /**
     * 验证 Besu eth_getCode 返回 JSON-RPC 错误时不会继续身份探测。
     */
    @Test
    void shouldRejectBesuRuntimeCodeRpcErrorBeforeIdentityProbe() throws IOException {
        configureBesu("1337", BigInteger.valueOf(1337));
        when(getCodeResponse.hasError()).thenReturn(true);
        service = newService();

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Besu runtime code");
        verify(identityProbe, never()).inspectBesu(any(), anyString());
    }

    /**
     * 验证 Besu 地址返回空 runtime code 时不会继续身份探测。
     */
    @Test
    void shouldRejectEmptyBesuRuntimeCodeBeforeIdentityProbe() throws IOException {
        configureBesu("1337", BigInteger.valueOf(1337));
        when(getCodeResponse.getCode()).thenReturn("0x");
        service = newService();

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Besu runtime code");
        verify(identityProbe, never()).inspectBesu(any(), anyString());
    }

    /**
     * 配置可重复的 Besu chainId、地址和 eth_getCode 响应。
     */
    private void configureBesu(String configuredChainId, BigInteger actualChainId)
            throws IOException {
        environment.setProperty("blockchain.active", "bsn-besu");
        environment.setProperty("blockchain.bsn-besu.chain-id", configuredChainId);
        environment.setProperty("blockchain.bsn-besu.contracts.sharing", SHARING_ADDRESS);
        environment.setProperty("blockchain.bsn-besu.contracts.storage", STORAGE_ADDRESS);
        when(web3jProvider.getIfAvailable()).thenReturn(web3j);
        doReturn(chainIdRequest).when(web3j).ethChainId();
        when(chainIdRequest.send()).thenReturn(chainIdResponse);
        when(chainIdResponse.getChainId()).thenReturn(actualChainId);
        lenient().doReturn(getCodeRequest).when(web3j).ethGetCode(anyString(), any());
        lenient().when(getCodeRequest.send()).thenReturn(getCodeResponse);
        lenient().when(getCodeResponse.getCode()).thenReturn("0x60006000");
    }

    /**
     * 从默认 catalog 生成只替换指定文本的临时测试资源。
     */
    private Path writeCatalogWithReplacement(String expected, String replacement)
            throws IOException {
        String catalog;
        try (InputStream inputStream = getClass().getResourceAsStream(
                "/contract-registry/artifacts.json")) {
            catalog = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String invalidCatalog = catalog.replace(expected, replacement);
        Path catalogPath = tempDirectory.resolve("invalid-catalog.json");
        Files.writeString(catalogPath, invalidCatalog, StandardCharsets.UTF_8);
        return catalogPath;
    }

    /**
     * 创建 Sharing、Storage 均包含完整部署三元组的配置。
     */
    private ContractRegistryProperties completeDeploymentProperties(String effectiveAt) {
        ContractRegistryProperties properties = new ContractRegistryProperties();
        configureDeployment(
                properties.getDeployment().getSharing(),
                "0x" + "A".repeat(64),
                101L,
                effectiveAt);
        configureDeployment(
                properties.getDeployment().getStorage(),
                "0x" + "B".repeat(64),
                102L,
                effectiveAt);
        return properties;
    }

    /**
     * 填充单个合约的完整部署证据。
     */
    private void configureDeployment(
            ContractRegistryProperties.DeploymentEvidence evidence,
            String transactionHash,
            long blockNumber,
            String effectiveAt
    ) {
        evidence.setTransactionHash(transactionHash);
        evidence.setBlockNumber(blockNumber);
        evidence.setEffectiveAt(effectiveAt);
    }

    /**
     * 创建使用 classpath catalog 的被测服务。
     */
    private ContractRegistryService newService() {
        return newService(new ContractRegistryProperties());
    }

    /**
     * 使用指定 catalog 配置创建被测服务。
     */
    private ContractRegistryService newService(ContractRegistryProperties properties) {
        return new ContractRegistryService(
                properties,
                new ContractFingerprintService(),
                identityProbe,
                new DefaultResourceLoader(),
                environment,
                clientProvider,
                web3jProvider);
    }
}
