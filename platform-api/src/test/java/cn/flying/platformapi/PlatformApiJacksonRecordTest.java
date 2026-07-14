package cn.flying.platformapi;

import cn.flying.platformapi.request.*;
import cn.flying.platformapi.response.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformApiJacksonRecordTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeAndDeserializeRequestRecords() throws Exception {
        ContractRegistryEntryResponse registry = contractRegistry();
        StoreFileRequest storeFileRequest = new StoreFileRequest("u1", "f.txt", "{\"k\":1}", "content-json");
        StoreFileRequest storeFileRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(storeFileRequest), StoreFileRequest.class);
        assertThat(storeFileRequest2).isEqualTo(storeFileRequest);

        StoreAttestationBatchRequest batchRequest = new StoreAttestationBatchRequest(
                7L,
                900L,
                "MB-900",
                "SHA-256-MERKLE-V1",
                "root-hash",
                2,
                registry
        );
        StoreAttestationBatchRequest batchRequest2 =
                objectMapper.readValue(objectMapper.writeValueAsBytes(batchRequest), StoreAttestationBatchRequest.class);
        assertThat(batchRequest2).isEqualTo(batchRequest);

        GetAttestationBatchRequest batchQuery = new GetAttestationBatchRequest(
                7L, 900L, registry);
        GetAttestationBatchRequest batchQuery2 = objectMapper.readValue(
                objectMapper.writeValueAsBytes(batchQuery), GetAttestationBatchRequest.class);
        assertThat(batchQuery2).isEqualTo(batchQuery);

        ShareFilesRequest shareFilesRequest = new ShareFilesRequest("u1", List.of("h1", "h2"), 60);
        ShareFilesRequest shareFilesRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(shareFilesRequest), ShareFilesRequest.class);
        assertThat(shareFilesRequest2).isEqualTo(shareFilesRequest);

        DeleteFilesRequest deleteFilesRequest = new DeleteFilesRequest("u1", List.of("h1"));
        DeleteFilesRequest deleteFilesRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(deleteFilesRequest), DeleteFilesRequest.class);
        assertThat(deleteFilesRequest2).isEqualTo(deleteFilesRequest);

        CancelShareRequest cancelShareRequest = new CancelShareRequest("SC123", "u1", "u1");
        CancelShareRequest cancelShareRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(cancelShareRequest), CancelShareRequest.class);
        assertThat(cancelShareRequest2).isEqualTo(cancelShareRequest);

        GetUserShareCodesRequest shareCodesRequest = new GetUserShareCodesRequest("u1", "u1");
        GetUserShareCodesRequest shareCodesRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(shareCodesRequest), GetUserShareCodesRequest.class);
        assertThat(shareCodesRequest2).isEqualTo(shareCodesRequest);

        GetShareInfoRequest shareInfoRequest = new GetShareInfoRequest("SC123", "u1");
        GetShareInfoRequest shareInfoRequest2 = objectMapper.readValue(objectMapper.writeValueAsBytes(shareInfoRequest), GetShareInfoRequest.class);
        assertThat(shareInfoRequest2).isEqualTo(shareInfoRequest);
    }

    @Test
    void shouldSerializeAndDeserializeResponseRecordsWithStableJsonFields() throws Exception {
        ContractRegistryEntryResponse registry = contractRegistry();
        ContractRegistryEntryResponse registry2 = objectMapper.readValue(
                objectMapper.writeValueAsBytes(registry),
                ContractRegistryEntryResponse.class);
        assertThat(registry2).isEqualTo(registry);

        SharingVO sharingVO = new SharingVO(
                "u1",
                List.of("h1"),
                "SC123",
                10,
                9,
                1700000000000L,
                true
        );
        JsonNode sharingJson = objectMapper.readTree(objectMapper.writeValueAsBytes(sharingVO));
        assertThat(sharingJson.has("isValid")).isTrue();
        assertThat(sharingJson.get("isValid").asBoolean()).isTrue();
        SharingVO sharingVO2 = objectMapper.readValue(objectMapper.writeValueAsBytes(sharingVO), SharingVO.class);
        assertThat(sharingVO2).isEqualTo(sharingVO);

        TransactionVO tx = new TransactionVO(
                "0xabc",
                "1",
                "1",
                "abi",
                "0xfrom",
                "0xto",
                "input",
                "sig",
                "123",
                1700000000000L
        );
        JsonNode txJson = objectMapper.readTree(objectMapper.writeValueAsBytes(tx));
        assertThat(txJson.has("from")).isTrue();
        assertThat(txJson.has("to")).isTrue();
        assertThat(txJson.get("from").asText()).isEqualTo("0xfrom");
        assertThat(txJson.get("to").asText()).isEqualTo("0xto");
        TransactionVO tx2 = objectMapper.readValue(objectMapper.writeValueAsBytes(tx), TransactionVO.class);
        assertThat(tx2).isEqualTo(tx);

        FileVO fileVO = new FileVO("a.txt", "h1", 1L, 2L, "text/plain");
        FileVO fileVO2 = objectMapper.readValue(objectMapper.writeValueAsBytes(fileVO), FileVO.class);
        assertThat(fileVO2).isEqualTo(fileVO);

        FileDetailVO detailVO = new FileDetailVO("u1", "a.txt", "p", "c", "h1", "t", 2L, 1L, "text/plain");
        FileDetailVO detailVO2 = objectMapper.readValue(objectMapper.writeValueAsBytes(detailVO), FileDetailVO.class);
        assertThat(detailVO2).isEqualTo(detailVO);

        StorageObjectHeadVO headVO = new StorageObjectHeadVO(
                true,
                "storage/tenant/1/chunk/h1",
                "h1",
                1L,
                1L,
                "node-a",
                1024L,
                "\"etag\"",
                "h1"
        );
        StorageObjectHeadVO headVO2 = objectMapper.readValue(objectMapper.writeValueAsBytes(headVO), StorageObjectHeadVO.class);
        assertThat(headVO2).isEqualTo(headVO);

        StoreAttestationBatchResponse batchResponse = new StoreAttestationBatchResponse("tx-root", "root-hash");
        StoreAttestationBatchResponse batchResponse2 = objectMapper.readValue(
                objectMapper.writeValueAsBytes(batchResponse),
                StoreAttestationBatchResponse.class
        );
        assertThat(batchResponse2).isEqualTo(batchResponse);

        GetAttestationBatchResponse batchQueryResponse = new GetAttestationBatchResponse(
                true,
                7L,
                900L,
                "MB-900",
                "SHA-256-MERKLE-V1",
                "a".repeat(64),
                2,
                1_700_000_000_000L
        );
        GetAttestationBatchResponse batchQueryResponse2 = objectMapper.readValue(
                objectMapper.writeValueAsBytes(batchQueryResponse),
                GetAttestationBatchResponse.class
        );
        assertThat(batchQueryResponse2).isEqualTo(batchQueryResponse);
    }

    /**
     * Verifies that dedicated batch attestation DTOs cross strict Java-serialization RPC boundaries.
     */
    @Test
    void shouldJavaSerializeBatchAttestationRecords() throws Exception {
        ContractRegistryEntryResponse registry = contractRegistry();
        StoreAttestationBatchRequest request = new StoreAttestationBatchRequest(
                7L,
                900L,
                "MB-900",
                "SHA-256-MERKLE-V1",
                "root-hash",
                2,
                registry
        );
        StoreAttestationBatchResponse response = new StoreAttestationBatchResponse("tx-root", "root-hash");
        GetAttestationBatchRequest query = new GetAttestationBatchRequest(7L, 900L, registry);
        GetAttestationBatchResponse queryResponse = GetAttestationBatchResponse.notFound(7L, 900L);

        assertThat(javaSerializationRoundTrip(request)).isEqualTo(request);
        assertThat(javaSerializationRoundTrip(response)).isEqualTo(response);
        assertThat(javaSerializationRoundTrip(query)).isEqualTo(query);
        assertThat(javaSerializationRoundTrip(queryResponse)).isEqualTo(queryResponse);
    }

    /**
     * 验证共享 entry.v1 算法命中固定向量，并能识别字段被替换但指纹未更新的快照。
     */
    @Test
    void contractRegistryFingerprint_shouldMatchGoldenVectorAndRejectTampering() {
        ContractRegistryEntryResponse registry = contractRegistry();
        assertThat(registry.registryFingerprint())
                .isEqualTo("sha256:7b0c1657b77b53bc54ab9e22e9cec42839e5d3be594dcc980217ac7292d1643d");
        assertThat(registry.hasValidRegistryFingerprint()).isTrue();

        ContractRegistryEntryResponse tampered = new ContractRegistryEntryResponse(
                registry.schemaVersion(),
                registry.registryFingerprint(),
                registry.contractName(),
                registry.semanticVersion(),
                registry.chainType(),
                registry.chainId(),
                registry.groupId(),
                "0x2222222222222222222222222222222222222222",
                registry.abiFingerprintAlgorithm(),
                registry.abiSha256(),
                registry.artifactBytecodeSha256(),
                registry.onChainCodeSha256(),
                registry.deploymentTransactionHash(),
                registry.deploymentBlockNumber(),
                registry.status(),
                registry.effectiveAt(),
                registry.upgradeStrategy());

        assertThat(tampered.hasValidRegistryFingerprint()).isFalse();
    }

    /**
     * 构造可跨 JSON 与 Java 序列化边界的完整注册表快照。
     */
    private ContractRegistryEntryResponse contractRegistry() {
        return new ContractRegistryEntryResponse(
                "record-platform-contract-registry-entry.v1",
                null,
                "Sharing",
                "2.0.0",
                "LOCAL_FISCO",
                "chain0",
                "group0",
                "0x1111111111111111111111111111111111111111",
                "ABI-CANONICAL-JSON-SHA256-V1",
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                "sha256:" + "4".repeat(64),
                null,
                null,
                "ACTIVE",
                "2026-07-13T00:00:00Z",
                "REDEPLOY_ADDRESS")
                .withCalculatedRegistryFingerprint();
    }

    /**
     * Serializes and deserializes one RPC value through the JDK object stream contract.
     */
    private Object javaSerializationRoundTrip(Object value) throws Exception {
        assertThat(value).isInstanceOf(Serializable.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readObject();
        }
    }
}
