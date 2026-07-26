package cn.flying.service.key;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用固定版本 Vault Community 容器验证 Transit HTTP API 的真实密码操作合同。
 */
@Testcontainers(disabledWithoutDocker = false)
class VaultTransitKeyWrappingProviderIT {

    private static final String ROOT_TOKEN = "record-platform-vault-it-root";
    private static final String POLICY_NAME = "record-platform-file-key";
    private static final String DENY_POLICY_NAME = "record-platform-file-key-deny";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicInteger KEY_SEQUENCE = new AtomicInteger();

    @org.testcontainers.junit.jupiter.Container
    private static final GenericContainer<?> VAULT =
            new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.21.4"))
                    .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
                    .withEnv("VAULT_TOKEN", ROOT_TOKEN)
                    .withEnv("VAULT_ADDR", "http://127.0.0.1:8200")
                    .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
                    .withExposedPorts(8200)
                    .waitingFor(Wait.forHttp("/v1/sys/health")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(2)));

    private static String applicationToken;
    private static String deniedToken;

    /**
     * 启用 Transit、创建最小权限策略，并签发应用与拒绝场景 token。
     */
    @BeforeAll
    static void configureVault() throws Exception {
        executeVault("vault", "secrets", "enable", "transit");
        String policy = """
                path \"transit/encrypt/record-platform-file-*\" { capabilities = [\"update\"] }
                path \"transit/decrypt/record-platform-file-*\" { capabilities = [\"update\"] }
                path \"transit/rewrap/record-platform-file-*\" { capabilities = [\"update\"] }
                """;
        String denyPolicy = """
                path \"transit/encrypt/record-platform-file-*\" { capabilities = [\"deny\"] }
                path \"transit/decrypt/record-platform-file-*\" { capabilities = [\"deny\"] }
                path \"transit/rewrap/record-platform-file-*\" { capabilities = [\"deny\"] }
                """;
        executeVault("sh", "-ec", "printf '%s' \"$1\" | vault policy write \"$2\" -", "sh", policy, POLICY_NAME);
        executeVault(
                "sh", "-ec", "printf '%s' \"$1\" | vault policy write \"$2\" -",
                "sh", denyPolicy, DENY_POLICY_NAME);
        applicationToken = createToken("-policy=" + POLICY_NAME);
        deniedToken = createToken("-policy=" + DENY_POLICY_NAME, "-no-default-policy");
    }

    /**
     * 验证真实 Transit wrap/unwrap 往返恢复同一明文数据密钥。
     */
    @Test
    void shouldWrapAndUnwrapWithRealVaultTransit() throws Exception {
        String keyName = createDerivedKey();
        VaultTransitKeyWrappingProvider provider = provider(keyName, 1, applicationToken);
        WrappingContext context = context("sha256:round-trip", 1);

        WrappedDataKey wrapped = provider.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("serialized-file-data-key"),
                context,
                provider.activeKeyReference(1).requireValue(),
                1)).requireValue();
        PlaintextDataKey plaintext = provider.unwrap(new KeyUnwrapRequest(
                persisted(wrapped), context)).requireValue();

        assertThat(wrapped.encryptedDataKey()).startsWith("vault:v1:");
        assertThat(wrapped.wrappingIv()).isNull();
        assertThat(plaintext.reveal()).isEqualTo("serialized-file-data-key");
    }

    /**
     * 验证业务上下文被篡改后 Vault 拒绝解封且仅返回稳定分类。
     */
    @Test
    void shouldRejectTamperedContext() throws Exception {
        String keyName = createDerivedKey();
        VaultTransitKeyWrappingProvider provider = provider(keyName, 1, applicationToken);
        WrappingContext original = context("sha256:original", 1);
        WrappedDataKey wrapped = provider.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("context-bound-data-key"),
                original,
                provider.activeKeyReference(1).requireValue(),
                1)).requireValue();

        KeyWrappingResult<PlaintextDataKey> result = provider.unwrap(new KeyUnwrapRequest(
                persisted(wrapped), context("sha256:tampered", 1)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(result.failure().providerCode()).isNull();
    }

    /**
     * 验证 Vault named key 轮换后使用服务端原生 rewrap 迁移到显式版本。
     */
    @Test
    void shouldRotateAndNativeRewrapWithoutPlaintextRoundTrip() throws Exception {
        String keyName = createDerivedKey();
        VaultTransitKeyWrappingProvider versionOne = provider(keyName, 1, applicationToken);
        WrappingContext sourceContext = context("sha256:rewrap", 1);
        WrappedDataKey original = versionOne.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("rewrapped-data-key"),
                sourceContext,
                versionOne.activeKeyReference(1).requireValue(),
                1)).requireValue();

        executeVault("vault", "write", "-f", "transit/keys/" + keyName + "/rotate");
        VaultTransitKeyWrappingProvider versionTwo = provider(keyName, 2, applicationToken);
        WrappingContext targetContext = context("sha256:rewrap", 2);
        WrappedDataKey rewrapped = versionTwo.rewrap(new KeyRewrapRequest(
                persisted(original),
                sourceContext,
                versionTwo.activeKeyReference(2).requireValue(),
                targetContext,
                2)).requireValue();

        PlaintextDataKey plaintext = versionTwo.unwrap(new KeyUnwrapRequest(
                persisted(rewrapped), targetContext)).requireValue();
        assertThat(original.encryptedDataKey()).startsWith("vault:v1:");
        assertThat(rewrapped.encryptedDataKey()).startsWith("vault:v2:");
        assertThat(plaintext.reveal()).isEqualTo("rewrapped-data-key");
    }

    /**
     * 验证无 Transit 权限 token 被映射为固定权限拒绝分类。
     */
    @Test
    void shouldClassifyPermissionDenied() throws Exception {
        String keyName = createDerivedKey();
        VaultTransitKeyWrappingProvider provider = provider(keyName, 1, deniedToken);

        KeyWrappingResult<WrappedDataKey> result = provider.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("permission-data-key"),
                context("sha256:permission", 1),
                provider.activeKeyReference(1).requireValue(),
                1));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.PERMISSION_DENIED);
        assertThat(result.failure().retryable()).isFalse();
    }

    /**
     * 验证最小 update 权限访问不存在的 Transit key 时按 Vault 403 语义失败关闭。
     */
    @Test
    void shouldClassifyMissingKeyWithoutCreatePermission() {
        String keyName = "record-platform-file-missing-" + KEY_SEQUENCE.incrementAndGet();
        VaultTransitKeyWrappingProvider provider = provider(keyName, 1, applicationToken);

        KeyWrappingResult<WrappedDataKey> result = provider.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("missing-key-data-key"),
                context("sha256:missing", 1),
                provider.activeKeyReference(1).requireValue(),
                1));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.PERMISSION_DENIED);
        assertThat(result.failure().retryable()).isFalse();
        assertThat(result.failure().providerCode()).isNull();
    }

    /**
     * 创建新的 derived aes256-gcm96 Transit key。
     */
    private static String createDerivedKey() throws Exception {
        String keyName = "record-platform-file-" + KEY_SEQUENCE.incrementAndGet();
        executeVault(
                "vault", "write", "transit/keys/" + keyName,
                "type=aes256-gcm96", "derived=true");
        return keyName;
    }

    /**
     * 使用显式策略签发测试 token，避免业务 provider 继承 root 权限。
     */
    private static String createToken(String... policyArguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "vault", "token", "create", "-orphan", "-format=json"));
        command.addAll(List.of(policyArguments));
        Container.ExecResult result = executeVault(command.toArray(String[]::new));
        return OBJECT_MAPPER.readTree(result.getStdout()).path("auth").path("client_token").textValue();
    }

    /**
     * 执行 Vault 容器内管理命令，仅断言退出码而不输出 token 或原始错误体。
     */
    private static Container.ExecResult executeVault(String... command) throws Exception {
        Container.ExecResult result = VAULT.execInContainer(command);
        assertThat(result.getExitCode()).isZero();
        return result;
    }

    /**
     * 构造指向测试容器且允许本地 HTTP 的 Vault provider。
     */
    private static VaultTransitKeyWrappingProvider provider(
            String keyName,
            int keyVersion,
            String token
    ) {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        FileKeyEnvelopeProperties.VaultTransit vault = properties.getProviders().getVaultTransit();
        vault.setAddress("http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200));
        vault.setAllowHttp(true);
        vault.setToken(token);
        vault.setMount("transit");
        vault.setKeyName(keyName);
        vault.setKeyVersion(keyVersion);
        return new VaultTransitKeyWrappingProvider(
                properties,
                new JdkVaultTransitTransport(properties),
                OBJECT_MAPPER);
    }

    /**
     * 构造不向 Vault 发送业务原值的 v2 认证上下文。
     */
    private static WrappingContext context(String fileHash, int logicalVersion) {
        return new WrappingContext(
                7L,
                11L,
                fileHash,
                "OWNER",
                13L,
                logicalVersion,
                "RP-AES256-GCM-CHUNK-CHAIN-V1",
                WrappingContext.EXTERNAL_CONTEXT_V2);
    }

    /**
     * 将 provider 结果转换为模拟持久化后的中立密钥材料。
     */
    private static PersistedWrappedDataKey persisted(WrappedDataKey wrapped) {
        return new PersistedWrappedDataKey(
                wrapped.encryptedDataKey(),
                wrapped.wrappingIv(),
                wrapped.keyReference(),
                wrapped.logicalKeyVersion());
    }
}
