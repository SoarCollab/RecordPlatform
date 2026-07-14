package cn.flying.fisco_bcos;

import cn.flying.fisco_bcos.constants.ContractConstants;
import cn.flying.fisco_bcos.registry.ContractArtifactCatalog;
import cn.flying.fisco_bcos.registry.ContractFingerprintService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationBatchContractArtifactTest {

    /**
     * 验证两份合约源同步且运行时 ABI 已包含用于响应丢失恢复的 getter。
     */
    @Test
    void sharingContractSourcesAndAbi_shouldExposeAttestationBatchGetter() throws Exception {
        String runtimeSource = Files.readString(modulePath("src/main/contracts/Sharing.sol"));
        String deploySource = Files.readString(modulePath("contract/Sharing.sol"));

        assertThat(runtimeSource).isEqualTo(deploySource);
        assertThat(runtimeSource).contains("function getAttestationBatch(");
        assertThat(runtimeSource)
                .contains("bytes(attestationBatches[tenantId][batchId].batchNo).length == 0")
                .contains("exists = bytes(batch.batchNo).length > 0")
                .doesNotContain("recordedTime == 0", "recordedTime != 0");
        assertThat(ContractConstants.SharingAbi).contains("\"name\":\"getAttestationBatch\"");
    }

    /**
     * 验证 ECC 与 SM 部署目录使用同一个已更新且非空的 EVM 字节码产物。
     */
    @Test
    void sharingContractBinaries_shouldBeUpdatedAndSynchronized() throws Exception {
        String ecc = Files.readString(modulePath("src/main/resources/bin/ecc/Sharing.bin"));
        String sm = Files.readString(modulePath("src/main/resources/bin/sm/Sharing.bin"));

        assertThat(ecc).isNotBlank().isEqualTo(sm);
        assertThat(ContractConstants.SharingBinary).isEqualTo(ecc);
        assertThat(ContractConstants.SharingGmBinary).isEqualTo(sm);
    }

    /**
     * 验证两份源码及打包 ABI 都声明与 catalog 对应的稳定合约身份。
     */
    @Test
    void contractArtifacts_shouldExposeStableRegistryIdentity() throws Exception {
        ContractFingerprintService fingerprintService = new ContractFingerprintService();
        ContractArtifactCatalog catalog = fingerprintService.readCatalog(Files.readAllBytes(
                modulePath("src/main/resources/contract-registry/artifacts.json")));
        List<ContractArtifactCatalog.ContractArtifact> activeArtifacts = catalog.contracts()
                .stream()
                .filter(artifact -> "ACTIVE".equals(artifact.status()))
                .toList();

        assertThat(activeArtifacts)
                .extracting(ContractArtifactCatalog.ContractArtifact::contractName)
                .containsExactlyInAnyOrder("Storage", "Sharing");
        for (ContractArtifactCatalog.ContractArtifact artifact : activeArtifacts) {
            assertStableRegistryIdentity(artifact, fingerprintService);
        }
    }

    /**
     * 依据 ACTIVE catalog 断言源码返回值、继承修饰符和打包 ABI 双字符串输出。
     */
    private void assertStableRegistryIdentity(
            ContractArtifactCatalog.ContractArtifact artifact,
            ContractFingerprintService fingerprintService
    ) throws Exception {
        assertThat(artifact.sourcePaths()).hasSize(2);
        String deploySource = Files.readString(repositoryPath(artifact.sourcePaths().get(0)));
        String runtimeSource = Files.readString(repositoryPath(artifact.sourcePaths().get(1)));
        String expectedReturn = "return (\"" + artifact.contractName()
                + "\", \"" + artifact.semanticVersion() + "\")";

        assertThat(runtimeSource)
                .isEqualTo(deploySource)
                .contains("function contractIdentity()")
                .contains(expectedReturn);
        if ("Storage".equals(artifact.contractName())) {
            assertThat(runtimeSource).containsPattern("public\\s+pure\\s+virtual\\s+returns");
        } else if ("Sharing".equals(artifact.contractName())) {
            assertThat(runtimeSource).containsPattern("public\\s+pure\\s+override\\s+returns");
        }

        String abi = Files.readString(repositoryPath(artifact.abiPath()));
        String packagedAbi = "Storage".equals(artifact.contractName())
                ? ContractConstants.StorageAbi
                : ContractConstants.SharingAbi;
        String identityAbi = "{\"inputs\":[],\"name\":\"contractIdentity\","
                + "\"outputs\":[{\"name\":\"contractName\",\"type\":\"string\"},"
                + "{\"name\":\"semanticVersion\",\"type\":\"string\"}],"
                + "\"stateMutability\":\"pure\",\"type\":\"function\"}";

        assertThat(packagedAbi).isEqualTo(abi);
        assertThat(fingerprintService.canonicalizeAbi(abi)).contains(identityAbi);
    }

    /**
     * 同时兼容从仓库根目录和 platform-fisco 模块目录启动 Maven 的路径。
     */
    private Path modulePath(String relativePath) {
        Path current = Path.of("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        return current.resolve("platform-fisco").resolve(relativePath);
    }

    /**
     * 将 catalog 中以仓库根目录为基准的路径兼容解析到当前 Maven 工作目录。
     */
    private Path repositoryPath(String relativePath) {
        Path current = Path.of("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        String modulePrefix = "platform-fisco/";
        if (relativePath.startsWith(modulePrefix)) {
            return current.resolve(relativePath.substring(modulePrefix.length()));
        }
        return direct;
    }
}
