package cn.flying.service.attestation;

import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证证明签发使用的合约注册表结构与生命周期失败关闭规则。
 */
class ContractRegistryEntryValidatorTest {

    /**
     * 验证 FISCO 与非 FISCO 的合法群组语义，以及各生命周期允许范围。
     */
    @Test
    void validRegistriesShouldRespectChainAndLifecycleSemantics() {
        ContractRegistryEntryResponse activeFisco = new RegistryFixture().build();
        RegistryFixture besuFixture = new RegistryFixture();
        besuFixture.chainType = "BSN_BESU";
        besuFixture.groupId = null;
        ContractRegistryEntryResponse activeBesu = besuFixture.build();
        RegistryFixture deprecatedFixture = new RegistryFixture();
        deprecatedFixture.status = "DEPRECATED";
        ContractRegistryEntryResponse deprecated = deprecatedFixture.build();
        RegistryFixture revokedFixture = new RegistryFixture();
        revokedFixture.status = "REVOKED";
        ContractRegistryEntryResponse revoked = revokedFixture.build();

        assertThat(ContractRegistryEntryValidator.isValidActiveSharingRegistry(activeFisco)).isTrue();
        assertThat(ContractRegistryEntryValidator.isValidActiveSharingRegistry(activeBesu)).isTrue();
        assertThat(ContractRegistryEntryValidator.isValidIssuableSharingRegistry(deprecated)).isTrue();
        assertThat(ContractRegistryEntryValidator.isValidActiveSharingRegistry(deprecated)).isFalse();
        assertThat(ContractRegistryEntryValidator.isValidPersistedSharingRegistry(revoked)).isTrue();
        assertThat(ContractRegistryEntryValidator.isValidIssuableSharingRegistry(revoked)).isFalse();
    }

    /**
     * 验证任一关键身份、摘要、链证据或生效时间异常都会使 registry 失效。
     */
    @Test
    void malformedRegistryFieldsShouldFailClosed() {
        List<Consumer<RegistryFixture>> invalidMutations = List.of(
                fixture -> fixture.useCalculatedFingerprint = false,
                fixture -> fixture.contractName = "Other",
                fixture -> fixture.semanticVersion = null,
                fixture -> fixture.semanticVersion = "v2",
                fixture -> fixture.chainType = null,
                fixture -> fixture.chainType = "UNKNOWN",
                fixture -> fixture.chainId = " ",
                fixture -> fixture.groupId = null,
                fixture -> {
                    fixture.chainType = "BSN_BESU";
                    fixture.groupId = "unexpected-group";
                },
                fixture -> fixture.abiSha256 = "invalid",
                fixture -> fixture.artifactBytecodeSha256 = "invalid",
                fixture -> fixture.onChainCodeSha256 = "invalid",
                fixture -> fixture.deploymentBlockNumber = -1L,
                fixture -> fixture.status = null,
                fixture -> fixture.status = "UNKNOWN",
                fixture -> fixture.effectiveAt = null,
                fixture -> fixture.effectiveAt = "2026-02-30T00:00:00Z",
                fixture -> fixture.effectiveAt = "2999-01-01T00:00:00Z");

        assertThat(ContractRegistryEntryValidator.isValidActiveSharingRegistry(null)).isFalse();
        for (Consumer<RegistryFixture> mutation : invalidMutations) {
            RegistryFixture fixture = new RegistryFixture();
            mutation.accept(fixture);
            assertThat(ContractRegistryEntryValidator.isValidActiveSharingRegistry(fixture.build()))
                    .as("mutation %s must fail closed", mutation)
                    .isFalse();
        }
    }

    /**
     * 提供可按单字段变异且每次重新计算指纹的有效 registry 基线。
     */
    private static final class RegistryFixture {

        private String schemaVersion = "record-platform-contract-registry-entry.v1";
        private String contractName = "Sharing";
        private String semanticVersion = "2.0.0";
        private String chainType = "LOCAL_FISCO";
        private String chainId = "chain0";
        private String groupId = "group0";
        private String contractAddress = "0x1111111111111111111111111111111111111111";
        private String abiFingerprintAlgorithm = "ABI-CANONICAL-JSON-SHA256-V1";
        private String abiSha256 = "sha256:" + "2".repeat(64);
        private String artifactBytecodeSha256 = "sha256:" + "3".repeat(64);
        private String onChainCodeSha256 = "sha256:" + "4".repeat(64);
        private String deploymentTransactionHash = "0x" + "a".repeat(64);
        private Long deploymentBlockNumber = 42L;
        private String status = "ACTIVE";
        private String effectiveAt = "2026-07-14T00:00:00Z";
        private String upgradeStrategy = "REDEPLOY_ADDRESS";
        private boolean useCalculatedFingerprint = true;

        /**
         * 构造当前字段快照；默认写入自洽指纹，指纹异常用例可显式关闭。
         */
        private ContractRegistryEntryResponse build() {
            ContractRegistryEntryResponse raw = record(null);
            return useCalculatedFingerprint
                    ? raw.withCalculatedRegistryFingerprint()
                    : record("invalid-fingerprint");
        }

        /**
         * 按当前 fixture 字段和值写入指定 registry 指纹。
         */
        private ContractRegistryEntryResponse record(String registryFingerprint) {
            return new ContractRegistryEntryResponse(
                    schemaVersion,
                    registryFingerprint,
                    contractName,
                    semanticVersion,
                    chainType,
                    chainId,
                    groupId,
                    contractAddress,
                    abiFingerprintAlgorithm,
                    abiSha256,
                    artifactBytecodeSha256,
                    onChainCodeSha256,
                    deploymentTransactionHash,
                    deploymentBlockNumber,
                    status,
                    effectiveAt,
                    upgradeStrategy);
        }
    }
}
