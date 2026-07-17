package cn.flying.fisco_bcos.registry;

import cn.flying.fisco_bcos.constants.ContractConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractFingerprintServiceTest {

    private final ContractFingerprintService service = new ContractFingerprintService();

    /**
     * 验证 Java 与 Python 对共享 golden vector 生成完全相同的 canonical JSON 和指纹。
     */
    @Test
    void shouldMatchCrossLanguageGoldenVector() throws Exception {
        JsonNode fixture;
        try (InputStream inputStream = getClass().getResourceAsStream(
                "/contract-registry/fingerprint-vectors.json")) {
            fixture = new ObjectMapper().readTree(inputStream);
        }
        for (JsonNode testCase : fixture.path("abiCases")) {
            for (JsonNode document : testCase.path("documents")) {
                String abi = document.toString();
                assertThat(service.canonicalizeAbi(abi))
                        .as(testCase.path("name").asText())
                        .isEqualTo(testCase.path("canonicalJson").asText());
                assertThat(service.fingerprintAbi(abi))
                        .as(testCase.path("name").asText())
                        .isEqualTo(testCase.path("abiSha256").asText());
            }
        }
    }

    /**
     * 验证 Java 运行时重算的打包 ABI 指纹与 catalog 一致。
     */
    @Test
    void shouldMatchPackagedArtifactCatalog() throws Exception {
        ContractArtifactCatalog catalog;
        try (InputStream inputStream = getClass().getResourceAsStream(
                "/contract-registry/artifacts.json")) {
            catalog = service.readCatalog(inputStream.readAllBytes());
        }

        ContractArtifactCatalog.ContractArtifact sharing = catalog.contracts().stream()
                .filter(entry -> "Sharing".equals(entry.contractName()))
                .findFirst()
                .orElseThrow();
        ContractArtifactCatalog.ContractArtifact storage = catalog.contracts().stream()
                .filter(entry -> "Storage".equals(entry.contractName()))
                .findFirst()
                .orElseThrow();

        assertThat(service.fingerprintAbi(ContractConstants.SharingAbi))
                .isEqualTo(sharing.abiSha256());
        assertThat(service.fingerprintBytecode(ContractConstants.SharingBinary))
                .isEqualTo(sharing.creationBytecodeSha256().get("ecc"));
        assertThat(service.fingerprintBytecode(ContractConstants.SharingGmBinary))
                .isEqualTo(sharing.creationBytecodeSha256().get("sm"));
        assertThat(service.fingerprintBytecode(ContractConstants.SharingRuntimeBinary))
                .isEqualTo(sharing.runtimeBytecodeSha256().get("ecc"));
        assertThat(service.fingerprintBytecode(ContractConstants.SharingGmRuntimeBinary))
                .isEqualTo(sharing.runtimeBytecodeSha256().get("sm"));
        assertThat(service.fingerprintAbi(ContractConstants.StorageAbi))
                .isEqualTo(storage.abiSha256());
        assertThat(service.fingerprintBytecode(ContractConstants.StorageBinary))
                .isEqualTo(storage.creationBytecodeSha256().get("ecc"));
        assertThat(service.fingerprintBytecode(ContractConstants.StorageGmBinary))
                .isEqualTo(storage.creationBytecodeSha256().get("sm"));
        assertThat(service.fingerprintBytecode(ContractConstants.StorageRuntimeBinary))
                .isEqualTo(storage.runtimeBytecodeSha256().get("ecc"));
        assertThat(service.fingerprintBytecode(ContractConstants.StorageGmRuntimeBinary))
                .isEqualTo(storage.runtimeBytecodeSha256().get("sm"));
    }

    /**
     * 验证重复 JSON key、空 bytecode 和非法十六进制均 fail closed。
     */
    @Test
    void shouldRejectAmbiguousAbiAndInvalidBytecode() {
        assertThatThrownBy(() -> service.fingerprintAbi(
                "[{\"type\":\"function\",\"type\":\"event\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ABI JSON");
        assertThatThrownBy(() -> service.fingerprintBytecode("0x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.fingerprintBytecode("0x123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.fingerprintBytecode("0xzz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证 Java 运行时与 Python 工具使用同一 5 MiB artifact 输入上限。
     */
    @Test
    void shouldRejectOversizedArtifactInputs() {
        String oversized = " ".repeat(5 * 1024 * 1024 + 1);

        assertThatThrownBy(() -> service.canonicalizeAbi(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ABI exceeds size limit");
        assertThatThrownBy(() -> service.fingerprintBytecode(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bytecode exceeds size limit");
    }

    /**
     * 验证合法 JSON 后追加第二个根值时不会被 Java 解析器静默忽略。
     */
    @Test
    void shouldRejectTrailingJsonTokens() throws Exception {
        assertThatThrownBy(() -> service.fingerprintAbi(
                "[{\"type\":\"function\"}] []"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ABI JSON");

        byte[] catalog;
        try (InputStream inputStream = getClass().getResourceAsStream(
                "/contract-registry/artifacts.json")) {
            catalog = inputStream.readAllBytes();
        }
        byte[] trailingCatalog = (new String(catalog, StandardCharsets.UTF_8) + "\n{}")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.readCatalog(trailingCatalog))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid contract artifact catalog");
    }

    /**
     * 验证 catalog 未声明字段不会被 Java 解析器静默忽略。
     */
    @Test
    void shouldRejectUnknownCatalogFields() throws Exception {
        byte[] catalog;
        try (InputStream inputStream = getClass().getResourceAsStream(
                "/contract-registry/artifacts.json")) {
            catalog = inputStream.readAllBytes();
        }
        byte[] catalogWithUnknownField = new String(catalog, StandardCharsets.UTF_8)
                .replace(
                        "\"contracts\": [",
                        "\"unsupportedField\": true,\n  \"contracts\": [")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.readCatalog(catalogWithUnknownField))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid contract artifact catalog");
    }
}
