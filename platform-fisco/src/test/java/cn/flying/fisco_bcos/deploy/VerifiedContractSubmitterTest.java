package cn.flying.fisco_bcos.deploy;

import org.fisco.bcos.sdk.jni.utilities.tx.TransactionBuilderJniObj;
import cn.flying.fisco_bcos.config.LocalFiscoSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerifiedContractSubmitterTest {
    @TempDir Path temporary;
    private static final String KEY = "1".repeat(64);
    private static final String ADDRESS = "0x" + "2".repeat(40);
    private static final String TX = "0x" + "3".repeat(64);

    /** Observes actual SDK JNI signing bytes and the subsequent client submission in both crypto modes. */
    @ParameterizedTest
    @ValueSource(strings = {"ecc", "sm"})
    void submitsCapturedBytesDespiteDecoyConsoleAndPostCaptureMutation(String variant) throws Exception {
        for (String name : new String[]{"Storage", "Sharing"}) {
            Fixture fixture = fixture(name, variant);
            var captured = fixture.capture();
            String expectedHex = Files.readString(fixture.creation).trim();
            Path decoy = fixture.root.resolve("console/contracts/solidity");
            Files.createDirectories(decoy);
            Files.writeString(decoy.resolve(name + ".sol"), "revert decoy compiler input");
            Files.writeString(fixture.creation, "6000");
            Files.writeString(fixture.abi, "[]");
            Client client = client(variant);
            var signer = LocalFiscoSigner.explicitSigner(client, KEY);
            TransactionReceipt receipt = new TransactionReceipt();
            receipt.setStatus(0);
            receipt.setContractAddress(ADDRESS);
            BcosTransactionReceipt response = new BcosTransactionReceipt();
            response.setResult(receipt);
            when(client.sendTransaction(anyString(), eq(false))).thenAnswer(invocation -> {
                String signed = invocation.getArgument(0);
                var transaction = new ObjectMapper().readTree(
                        TransactionBuilderJniObj.decodeTransactionToJsonObj(signed));
                var data = transaction.path("data");
                assertThat(data.path("input").asText().replaceFirst("^0x", "")).isEqualTo(expectedHex);
                assertThat(data.path("chainID").asText()).isEqualTo("chain0");
                assertThat(data.path("groupID").asText()).isEqualTo("group0");
                assertThat(data.path("to").asText()).isEmpty();
                return response;
            });
            assertThat(VerifiedContractSubmitter.submit(captured, client, signer, "chain0", "group0",
                    selected -> {
                        assertThat(selected).isSameAs(client);
                        return rawInfo(variant);
                    })).isSameAs(receipt);
            verify(client, times(1)).sendTransaction(anyString(), eq(false));
            // SDK fills input/hash: these are intentionally not accepted as independent evidence.
            assertThat(receipt.getInput()).isEqualTo("0x" + expectedHex);
        }
    }

    /** Pre-capture artifact mutations and noncanonical ABI changes cannot reach the SDK. */
    @ParameterizedTest
    @ValueSource(strings = {"catalog", "abi", "creation", "empty", "oversized"})
    void rejectsUnsafeOrMutatedArtifacts(String mutation) throws Exception {
        Fixture fixture = fixture("Sharing", "ecc");
        switch (mutation) {
            case "catalog" -> Files.writeString(fixture.catalog, "{}");
            case "abi" -> Files.writeString(fixture.abi, "[]");
            case "creation" -> Files.writeString(fixture.creation, "6000");
            case "empty" -> Files.write(fixture.creation, new byte[0]);
            case "oversized" -> Files.write(fixture.creation, new byte[VerifiedContractSubmitter.MAX_BYTES + 1]);
            default -> throw new AssertionError();
        }
        assertThatThrownBy(fixture::capture).isInstanceOf(Exception.class);
    }

    /** Same-client fresh RPC mismatch, unknown metadata and post-query drift all stop before signing. */
    @ParameterizedTest
    @ValueSource(strings = {"chain", "group", "crypto", "wasm", "missing", "conflict", "rpc-error", "drift", "signer"})
    void rejectsContextBeforeAnySubmission(String mutation) throws Exception {
        var captured = fixture("Storage", "ecc").capture();
        Client client = client("ecc");
        var signer = LocalFiscoSigner.explicitSigner(client, KEY);
        String raw = rawInfo("ecc");
        switch (mutation) {
            case "chain" -> raw = raw.replace("chain0", "wrong");
            case "group" -> raw = raw.replace("group0", "wrong");
            case "crypto" -> raw = raw.replace("\"smCryptoType\":false", "\"smCryptoType\":true");
            case "wasm" -> raw = raw.replace("\"isWasm\":false", "\"isWasm\":true");
            case "missing" -> raw = raw.replace("\"isWasm\":false,", "");
            case "conflict" -> raw = raw.replace("\"isWasm\":false", "\"isWasm\":false,\"wasm\":true");
            case "rpc-error" -> raw = "{\"error\":{\"code\":-1},\"result\":null}";
            case "signer" -> LocalFiscoSigner.explicitSigner(client, "2".repeat(64));
            default -> { }
        }
        String finalRaw = raw;
        VerifiedContractSubmitter.GroupInfoProbe probe = selected -> {
            assertThat(selected).isSameAs(client);
            if (mutation.equals("drift")) {
                when(client.getChainId()).thenReturn("other-chain");
            }
            return finalRaw;
        };
        try (MockedStatic<TransactionBuilderJniObj> nativeSigner = mockStatic(TransactionBuilderJniObj.class)) {
            assertThatThrownBy(() -> VerifiedContractSubmitter.submit(captured, client, signer, "chain0", "group0", probe))
                    .isInstanceOf(Exception.class);
            nativeSigner.verifyNoInteractions();
            verify(client, never()).sendTransaction(anyString(), anyBoolean());
        }
    }

    /** Deployment and provider reject absent/invalid signers instead of accepting SDK random keys. */
    @Test
    void requiresExplicitValidSignerAndSupportedCrypto() {
        Client client = client("ecc");
        for (String invalid : new String[]{null, "", " ", "0".repeat(64), "abc", "1".repeat(65)}) {
            assertThatThrownBy(() -> LocalFiscoSigner.explicitSigner(client, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var signer = LocalFiscoSigner.explicitSigner(client, "0x" + KEY);
        assertThat(client.getCryptoSuite().getCryptoKeyPair()).isSameAs(signer);
        when(client.getCryptoType()).thenReturn(2);
        assertThatThrownBy(() -> LocalFiscoSigner.explicitSigner(client, KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** IPC cannot allocate beyond its bound or accept truncated/empty capture frames. */
    @Test
    void rejectsInvalidCaptureFrames() throws Exception {
        for (int length : new int[]{-1, 0, VerifiedContractSubmitter.MAX_BYTES + 1, 100}) {
            byte[] header = ByteBuffer.allocate(4).putInt(length).array();
            assertThatThrownBy(() -> VerifiedContractSubmitter.readFrame(
                    new DataInputStream(new ByteArrayInputStream(header)))).isInstanceOf(Exception.class);
        }
        assertThatThrownBy(() -> VerifiedContractSubmitter.readFrame(
                new DataInputStream(new ByteArrayInputStream(new byte[2])))).isInstanceOf(Exception.class);
        byte[] framed = ByteBuffer.allocate(7).putInt(3).put(new byte[]{1, 2, 3}).array();
        assertThat(VerifiedContractSubmitter.readFrame(new DataInputStream(new ByteArrayInputStream(framed))))
                .containsExactly(1, 2, 3);
    }

    /** Complete, duplicate, truncated and tampered IPC frames are checked before any SDK is created. */
    @Test
    void verifiesWholeCaptureProtocolBeforeSdkInitialization() throws Exception {
        Fixture fixture = fixture("Storage", "ecc");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        for (Path path : new Path[]{fixture.catalog, fixture.abi, fixture.creation}) {
            byte[] frame = Files.readAllBytes(path);
            output.writeInt(frame.length);
            output.write(frame);
        }
        String[] args = {fixture.catalogHash, fixture.name, fixture.variant, fixture.abiHash};
        byte[] valid = bytes.toByteArray();
        try (var sdk = mockConstruction(org.fisco.bcos.sdk.v3.BcosSDK.class)) {
            assertThat(VerifiedContractSubmitter.readCaptured(
                    new DataInputStream(new ByteArrayInputStream(valid)), args)).isNotNull();
            byte[] duplicate = new byte[valid.length * 2];
            System.arraycopy(valid, 0, duplicate, 0, valid.length);
            System.arraycopy(valid, 0, duplicate, valid.length, valid.length);
            byte[] tampered = valid.clone();
            tampered[tampered.length - 2] ^= 1;
            for (byte[] invalid : new byte[][]{duplicate, tampered, java.util.Arrays.copyOf(valid, valid.length - 1)}) {
                assertThatThrownBy(() -> VerifiedContractSubmitter.readCaptured(
                        new DataInputStream(new ByteArrayInputStream(invalid)), args)).isInstanceOf(Exception.class);
            }
            assertThat(sdk.constructed()).isEmpty();
        }
    }

    /** Raw metadata uses the exact client handle and preserves bytes instead of an unpopulated SDK field. */
    @Test
    void rawGroupQueryUsesSameNativeHandleAndRejectsTransportErrors() throws Exception {
        Client client = client("ecc");
        when(client.getNativePointer()).thenReturn(42L);
        assertThat(VerifiedContractSubmitter.queryGroupInfo(client, (pointer, group, callback) -> {
            assertThat(pointer).isEqualTo(42L);
            assertThat(group).isEqualTo("group0");
            var response = new org.fisco.bcos.sdk.jni.common.Response();
            response.setErrorCode(0);
            response.setData(rawInfo("ecc").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            callback.onResponse(response);
        })).isEqualTo(rawInfo("ecc"));
        for (byte[] value : new byte[][]{null, new byte[0], new byte[VerifiedContractSubmitter.MAX_BYTES + 1]}) {
            assertThatThrownBy(() -> VerifiedContractSubmitter.queryGroupInfo(client, (pointer, group, callback) -> {
                var response = new org.fisco.bcos.sdk.jni.common.Response();
                response.setData(value);
                callback.onResponse(response);
            })).isInstanceOf(Exception.class);
        }
        assertThatThrownBy(() -> VerifiedContractSubmitter.queryGroupInfo(client, (pointer, group, callback) -> {
            var response = new org.fisco.bcos.sdk.jni.common.Response();
            response.setErrorCode(-1);
            response.setErrorMessage("sensitive external error");
            callback.onResponse(response);
        })).hasMessageNotContaining("sensitive external error");
        when(client.getNativePointer()).thenReturn(0L);
        assertThatThrownBy(() -> VerifiedContractSubmitter.queryGroupInfo(client))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Builds an isolated subset of real signed artifacts, never synthetic expected deployment bytes. */
    private Fixture fixture(String name, String variant) throws Exception {
        Path root = Files.createTempDirectory(temporary.toRealPath(), "capture");
        Path sourceRoot = Path.of("..").toRealPath();
        Path catalog = root.resolve("catalog.json");
        Files.copy(sourceRoot.resolve("platform-fisco/src/main/resources/contract-registry/artifacts.json"), catalog);
        Path abi = root.resolve("platform-fisco/src/main/resources/abi/" + name + ".abi");
        Path creation = root.resolve("platform-fisco/src/main/resources/bin/" + variant + "/" + name + ".bin");
        Files.createDirectories(abi.getParent());
        Files.createDirectories(creation.getParent());
        Files.copy(sourceRoot.resolve(root.relativize(abi)), abi);
        Files.copy(sourceRoot.resolve(root.relativize(creation)), creation);
        return new Fixture(root, catalog, abi, creation, hash(catalog), hash(abi), name, variant);
    }

    /** Mocks transport only; production TransactionProcessor and its byte conversion still execute. */
    private Client client(String variant) {
        Client client = mock(Client.class);
        int type = "sm".equals(variant) ? 1 : 0;
        when(client.getCryptoSuite()).thenReturn(new CryptoSuite(type));
        when(client.getCryptoType()).thenReturn(type);
        when(client.getChainId()).thenReturn("chain0");
        when(client.getGroup()).thenReturn("group0");
        when(client.isWASM()).thenReturn(false);
        when(client.getBlockLimit()).thenReturn(BigInteger.valueOf(500));
        when(client.getExtraData()).thenReturn("");
        return client;
    }

    /** Produces a complete official-style response with explicit node VM and crypto fields. */
    private String rawInfo(String variant) {
        return "{\"result\":{\"chainID\":\"chain0\",\"groupID\":\"group0\",\"nodeList\":[{\"iniConfig\":{"
                + "\"chainID\":\"chain0\",\"groupID\":\"group0\",\"isWasm\":false,\"smCryptoType\":"
                + "sm".equals(variant) + "}}]}}";
    }

    /** Computes raw-byte capture hashes, deliberately distinct from canonical ABI fingerprints. */
    private static String hash(Path path) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private record Fixture(Path root, Path catalog, Path abi, Path creation, String catalogHash,
                           String abiHash, String name, String variant) {
        /** Captures this fixture through the unmodified production filesystem boundary. */
        VerifiedContractSubmitter.Captured capture() throws Exception {
            return VerifiedContractSubmitter.capture(Files.readAllBytes(catalog), Files.readAllBytes(abi),
                    Files.readAllBytes(creation), catalogHash, name, variant, abiHash);
        }
    }
}
