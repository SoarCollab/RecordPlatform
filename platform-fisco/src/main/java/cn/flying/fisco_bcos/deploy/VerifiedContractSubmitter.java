package cn.flying.fisco_bcos.deploy;

import cn.flying.fisco_bcos.config.LocalFiscoSigner;
import cn.flying.fisco_bcos.registry.ContractArtifactCatalog;
import cn.flying.fisco_bcos.registry.ContractFingerprintService;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fisco.bcos.sdk.v3.BcosSDK;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.config.ConfigOption;
import org.fisco.bcos.sdk.v3.config.model.ConfigProperty;
import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.transaction.manager.TransactionProcessor;
import org.fisco.bcos.sdk.jni.rpc.RpcJniObj;
import org.fisco.bcos.sdk.jni.rpc.RpcCallback;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Submits only captured catalog-bound creation bytes; never loads Console sources or wrappers. */
public final class VerifiedContractSubmitter {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Runs one explicit deployment and emits only a bounded non-secret machine record. */
    public static void main(String[] args) {
        PrintStream output = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        BcosSDK sdk = null;
        int status = 1;
        try {
            if (args.length != 4) {
                throw new IllegalArgumentException("arguments");
            }
            Captured captured = readCaptured(new DataInputStream(System.in), args);
            String key = LocalFiscoSigner.requirePrivateKey(System.getenv("FISCO_PRIVATE_KEY"));
            String chain = requireIdentifier(System.getenv("FISCO_CHAIN_ID"));
            String group = requireIdentifier(System.getenv("FISCO_GROUP_ID"));
            ConfigProperty property = new ConfigProperty();
            property.setNetwork(Map.of("peers", List.of(requiredEnv("FISCO_PEER_ADDRESS"))));
            property.setCryptoMaterial(Map.of("certPath", requiredEnv("FISCO_CERT_PATH")));
            sdk = new BcosSDK(new ConfigOption(property));
            Client client = sdk.getClient(group);
            CryptoKeyPair signer = LocalFiscoSigner.explicitSigner(client, key);
            TransactionReceipt receipt = submit(captured, client, signer, chain, group);
            String address = requireHex(receipt.getContractAddress(), 40);
            String transaction = requireHex(receipt.getTransactionHash(), 64);
            String signerAddress = requireHex(signer.getAddress(), 40);
            output.println(JSON.writeValueAsString(Map.of(
                    "schemaVersion", "record-platform-verified-submission.v1",
                    "contractName", captured.name,
                    "variant", captured.variant,
                    "contractAddress", address,
                    "transactionHash", transaction,
                    "signerAddress", signerAddress)));
            status = 0;
        } catch (Exception | LinkageError failure) {
            // SDK exceptions can include credentials or signed transactions. Never forward them.
            output.println("{\"schemaVersion\":\"record-platform-verified-submission-error.v1\",\"error\":\"SUBMISSION_FAILED_OR_UNCERTAIN\"}");
        } finally {
            if (sdk != null) {
                try {
                    sdk.stopAll();
                } catch (Exception ignored) {
                    // Shutdown failure does not authorize another submission.
                }
            }
        }
        System.exit(status);
    }

    /** Validates the captured input bytes that are subsequently owned by the submission object. */
    static Captured capture(byte[] catalogBytes, byte[] abiBytes, byte[] creationText,
                            String catalogHash, String name, String variant, String rawAbiHash) throws Exception {
        if (!Set.of("Storage", "Sharing").contains(name) || !Set.of("ecc", "sm").contains(variant)) {
            throw new IllegalArgumentException("unsupported artifact");
        }
        requireHash(catalogBytes, catalogHash);
        ContractFingerprintService fingerprints = new ContractFingerprintService();
        ContractArtifactCatalog catalog = fingerprints.readCatalog(catalogBytes);
        if (!"record-platform-contract-artifacts.v2".equals(catalog.schemaVersion())) {
            throw new IllegalArgumentException("catalog schema");
        }
        var matches = catalog.contracts().stream().filter(entry -> name.equals(entry.contractName())
                && "ACTIVE".equals(entry.status())).toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("ambiguous artifact");
        }
        var artifact = matches.getFirst();
        requireHash(abiBytes, rawAbiHash);
        String abi = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(abiBytes)).toString();
        if (!artifact.abiSha256().equals(fingerprints.fingerprintAbi(abi))) {
            throw new IllegalArgumentException("canonical ABI drift");
        }
        for (JsonNode entry : JSON.readTree(abi)) {
            if ("constructor".equals(entry.path("type").asText())
                    && (!entry.path("inputs").isArray() || !entry.path("inputs").isEmpty())) {
                throw new IllegalArgumentException("constructor arguments unsupported");
            }
        }
        String hex = StandardCharsets.US_ASCII.newDecoder().decode(ByteBuffer.wrap(creationText))
                .toString().replaceAll("\\s+", "").replaceFirst("^0[xX]", "");
        if (hex.isEmpty() || !hex.matches("(?:[0-9a-fA-F]{2})+")) {
            throw new IllegalArgumentException("creation format");
        }
        byte[] creation = HexFormat.of().parseHex(hex);
        requireHash(creation, artifact.creationBytecodeSha256().get(variant));
        return new Captured(name, variant, abi, creation);
    }

    /** Validates exactly three bounded frames and EOF before SDK initialization is possible. */
    static Captured readCaptured(DataInputStream input, String[] args) throws Exception {
        byte[] catalog = readFrame(input);
        byte[] abi = readFrame(input);
        byte[] creation = readFrame(input);
        if (input.read() != -1) {
            throw new IllegalArgumentException("trailing capture input");
        }
        return capture(catalog, abi, creation, args[0], args[1], args[2], args[3]);
    }

    /** Validates the active RPC and transaction context immediately before the raw SDK boundary. */
    static TransactionReceipt submit(Captured captured, Client client, CryptoKeyPair signer,
                                     String chain, String group) throws Exception {
        return submit(captured, client, signer, chain, group, VerifiedContractSubmitter::queryGroupInfo);
    }

    /** Keeps a testable raw-RPC transport seam while production always uses the selected client handle. */
    static TransactionReceipt submit(Captured captured, Client client, CryptoKeyPair signer,
                                     String chain, String group, GroupInfoProbe probe) throws Exception {
        int expectedCrypto = "sm".equals(captured.variant) ? 1 : 0;
        if (!Integer.valueOf(expectedCrypto).equals(client.getCryptoType())
                || client.getCryptoSuite().cryptoTypeConfig != expectedCrypto
                || signer != client.getCryptoSuite().getCryptoKeyPair()
                || !Boolean.FALSE.equals(client.isWASM())
                || !chain.equals(client.getChainId()) || !group.equals(client.getGroup())) {
            throw new IllegalArgumentException("client context");
        }
        String raw = probe.inspect(client);
        if (raw == null || raw.length() > MAX_BYTES) {
            throw new IllegalArgumentException("RPC bounds");
        }
        JsonNode rpc = JSON.readTree(raw);
        if (rpc.hasNonNull("error")) {
            throw new IllegalArgumentException("RPC error");
        }
        JsonNode info = rpc.path("result");
        if (!chain.equals(info.path("chainID").asText()) || !group.equals(info.path("groupID").asText())
                || !info.path("nodeList").isArray() || info.path("nodeList").isEmpty()) {
            throw new IllegalArgumentException("RPC identity");
        }
        for (JsonNode node : info.path("nodeList")) {
            JsonNode config = node.path("iniConfig");
            if (config.isTextual()) {
                config = JSON.readTree(config.textValue());
            }
            boolean wasm = config.has("isWasm") && config.get("isWasm").isBoolean();
            boolean legacy = config.has("wasm") && config.get("wasm").isBoolean();
            if ((!wasm && !legacy) || (config.has("isWasm") && !wasm) || (config.has("wasm") && !legacy)
                    || (wasm && config.get("isWasm").booleanValue())
                    || (legacy && config.get("wasm").booleanValue())
                    || !config.path("smCryptoType").isBoolean()
                    || config.path("smCryptoType").booleanValue() != (expectedCrypto == 1)
                    || !chain.equals(config.path("chainID").asText())
                    || !group.equals(config.path("groupID").asText())) {
                throw new IllegalArgumentException("RPC VM or crypto");
            }
        }
        // Recheck cached signing fields after the RPC; no asynchronous work or file reopen follows.
        if (!chain.equals(client.getChainId()) || !group.equals(client.getGroup())
                || !Integer.valueOf(expectedCrypto).equals(client.getCryptoType())
                || client.getCryptoSuite().cryptoTypeConfig != expectedCrypto
                || signer != client.getCryptoSuite().getCryptoKeyPair() || !Boolean.FALSE.equals(client.isWASM())) {
            throw new IllegalArgumentException("signing context drift");
        }
        TransactionProcessor processor = new TransactionProcessor(client, signer, group, chain);
        // SDK 3.8.0 passes this array directly to createDeploySignedTransaction; no ABI encoding/compiler.
        TransactionReceipt receipt = processor.deployAndGetReceipt("", captured.creation, captured.abi, signer, 0);
        if (receipt == null || receipt.getStatus() != 0) {
            throw new IllegalStateException("submission unsuccessful or uncertain");
        }
        return receipt;
    }

    /** Queries raw group metadata through the exact SDK client native handle, without DTO coercion. */
    static String queryGroupInfo(Client client) throws Exception {
        return queryGroupInfo(client, (pointer, group, callback) -> RpcJniObj.build(pointer).getGroupInfo(group, callback));
    }

    /** Binds the raw query to the selected native handle and bounds the callback before JSON parsing. */
    static String queryGroupInfo(Client client, GroupInfoRequest request) throws Exception {
        long pointer = client.getNativePointer();
        if (pointer == 0) {
            throw new IllegalArgumentException("uninitialized SDK client");
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        // SDK 3.8.0 getGroupInfo() drops rawResponse. Use the same JNI call as ClientImpl,
        // retaining bytes so malformed booleans, duplicate keys and conflicting VM fields reject.
        request.send(pointer, client.getGroup(), response -> {
            byte[] bytes = response.getData();
            if (response.getErrorCode() != 0 || bytes == null || bytes.length < 1 || bytes.length > MAX_BYTES) {
                result.completeExceptionally(new IllegalStateException("group metadata unavailable"));
            } else {
                result.complete(bytes);
            }
        });
        byte[] bytes = result.get(30, TimeUnit.SECONDS);
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** Isolates transport in tests without substituting SDK signing or transaction submission. */
    @FunctionalInterface
    interface GroupInfoProbe {
        /** Returns the unmodified raw response obtained from this exact client. */
        String inspect(Client client) throws Exception;
    }

    /** Transport-only seam for exercising native pointer/group selection and callback error handling. */
    @FunctionalInterface
    interface GroupInfoRequest {
        /** Executes getGroupInfo on the supplied handle; it must not create another SDK/client. */
        void send(long pointer, String group, RpcCallback callback);
    }


    /** Reads one bounded capture frame from the parent-owned private stdin snapshot. */
    static byte[] readFrame(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 1 || length > MAX_BYTES) {
            throw new IllegalArgumentException("capture bounds");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("truncated capture");
        }
        return bytes;
    }

    /** Compares complete captured bytes with a strict lowercase SHA-256 label. */
    private static void requireHash(byte[] bytes, String expected) throws Exception {
        if (bytes == null || bytes.length < 1 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("capture bounds");
        }
        String actual = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("captured digest mismatch");
        }
    }

    /** Validates non-secret address/hash fields before they reach stdout. */
    private static String requireHex(String value, int length) {
        if (value == null || !value.matches("0x[0-9a-fA-F]{" + length + "}")
                || value.substring(2).matches("0+")) {
            throw new IllegalArgumentException("invalid result identity");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    /** Accepts only bounded chain/group identifiers. */
    private static String requireIdentifier(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("chain/group required");
        }
        return value;
    }

    /** Requires local connection configuration without ever printing its content. */
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new IllegalArgumentException("connection configuration required");
        }
        return value;
    }

    /** Owns immutable, private captured input; no caller-visible byte array accessor exists. */
    static final class Captured {
        private final String name;
        private final String variant;
        private final String abi;
        private final byte[] creation;

        /** Takes ownership of fresh capture bytes without reopening an artifact later. */
        private Captured(String name, String variant, String abi, byte[] creation) {
            this.name = name;
            this.variant = variant;
            this.abi = abi;
            this.creation = creation;
        }
    }
}
