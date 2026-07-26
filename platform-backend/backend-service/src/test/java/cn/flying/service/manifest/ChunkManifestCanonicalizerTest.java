package cn.flying.service.manifest;

import cn.flying.common.exception.GeneralException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChunkManifestCanonicalizer")
class ChunkManifestCanonicalizerTest {

    private static final String LEGACY_CANONICAL_JSON =
            "{\"chunkSize\":10,\"chunks\":["
                    + "{\"checksumAlgorithm\":\"SHA-256\",\"cipherHash\":\"cipher-0\","
                    + "\"index\":0,\"plainHash\":\"plain-0\",\"size\":6,"
                    + "\"storageBackend\":\"S3\",\"storagePath\":\"storage/tenant/7/chunk/0\"},"
                    + "{\"checksumAlgorithm\":\"SHA-256\",\"cipherHash\":\"cipher-1\","
                    + "\"index\":1,\"plainHash\":\"plain-1\",\"size\":4,"
                    + "\"storageBackend\":\"S3\",\"storagePath\":\"storage/tenant/7/chunk/1\"}],"
                    + "\"encryptionAlgorithm\":\"CHACHA20_POLY1305\",\"fileHash\":\"file-hash\","
                    + "\"hashAlgorithm\":\"SHA-256\",\"schema\":\"cn.flying.chunk-manifest.v1\","
                    + "\"storageBackend\":\"S3\",\"totalSize\":10}";
    private static final String LEGACY_MANIFEST_HASH =
            "sha256:5ca61f7ea9db649d569246fef159cd785d79e20d820b1fe19deed4e1b7450791";

    private final ChunkManifestCanonicalizer canonicalizer = new ChunkManifestCanonicalizer();

    /**
     * 验证旧 manifest 的 null encryption/plainSize/frameCount 字段仍产生与 main 基线完全相同的字节和 hash。
     */
    @Test
    void legacyNullFields_shouldRemainByteIdenticalToMainCanonicalContract() {
        ChunkManifestDraft legacyDraft = draft(List.of(
                chunk(1, " plain-1 ", " cipher-1 ", 4L, " storage/tenant/7/chunk/1 "),
                chunk(0, "plain-0", "cipher-0", 6L, "storage/tenant/7/chunk/0")
        ));

        assertThat(canonicalizer.canonicalJson(legacyDraft)).isEqualTo(LEGACY_CANONICAL_JSON);
        assertThat(canonicalizer.manifestHash(legacyDraft)).isEqualTo(LEGACY_MANIFEST_HASH);
    }

    /**
     * Verifies canonical hashing is stable across input chunk order and whitespace.
     */
    @Test
    void manifestHash_shouldBeDeterministicForCanonicalPayload() {
        ChunkManifestDraft first = draft(List.of(
                chunk(1, " plain-1 ", " cipher-1 ", 4L, " storage/tenant/7/chunk/1 "),
                chunk(0, "plain-0", "cipher-0", 6L, "storage/tenant/7/chunk/0")
        ));
        ChunkManifestDraft second = draft(List.of(
                chunk(0, "plain-0", "cipher-0", 6L, "storage/tenant/7/chunk/0"),
                chunk(1, "plain-1", "cipher-1", 4L, "storage/tenant/7/chunk/1")
        ));

        String firstHash = canonicalizer.manifestHash(first);
        String secondHash = canonicalizer.manifestHash(second);

        assertThat(firstHash).isEqualTo(secondHash);
        assertThat(firstHash).startsWith(ChunkManifestCanonicalizer.MANIFEST_HASH_PREFIX);
        assertThat(firstHash).hasSize(ChunkManifestCanonicalizer.MANIFEST_HASH_PREFIX.length() + 64);
        assertThat(canonicalizer.normalize(first).chunks())
                .extracting(ChunkManifestChunk::index)
                .containsExactly(0, 1);
    }

    /**
     * Verifies chunk indexes must form a contiguous zero-based sequence.
     */
    @Test
    void normalize_shouldRejectNonContiguousChunkIndexes() {
        ChunkManifestDraft draft = draft(List.of(
                chunk(0, "plain-0", "cipher-0", 4L, "storage/tenant/7/chunk/0"),
                chunk(2, "plain-2", "cipher-2", 6L, "storage/tenant/7/chunk/2")
        ));

        assertThatThrownBy(() -> canonicalizer.normalize(draft))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("contiguous"));
    }

    /**
     * Verifies the manifest total size must match persisted chunk sizes.
     */
    @Test
    void normalize_shouldRejectTotalSizeMismatch() {
        ChunkManifestDraft draft = new ChunkManifestDraft(
                null,
                "file-hash",
                null,
                10L,
                11L,
                null,
                null,
                null,
                List.of(
                        chunk(0, "plain-0", "cipher-0", 4L, "storage/tenant/7/chunk/0"),
                        chunk(1, "plain-1", "cipher-1", 6L, "storage/tenant/7/chunk/1")
                )
        );

        assertThatThrownBy(() -> canonicalizer.normalize(draft))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("sum of chunk sizes"));
    }

    /**
     * 验证 framed v2 的 totalSize 使用明文分片总量，而不是密文对象字节总量。
     */
    @Test
    void normalize_shouldUsePlainSizeForFramedV2Total() {
        ChunkManifestEncryption encryption = framedEncryption();
        ChunkManifestDraft draft = new ChunkManifestDraft(
                null,
                "file-hash",
                null,
                64 * 1024L,
                64 * 1024L + 3,
                null,
                "FRAMED_AEAD_V2",
                "S3",
                encryption,
                List.of(
                        new ChunkManifestChunk(0, "sha256:" + "a".repeat(64),
                                "sha256:" + "b".repeat(64), 65_608L,
                                "storage/tenant/7/chunk/sha256:" + "b".repeat(64),
                                "S3", null, "SHA-256", 64 * 1024L, 1),
                        new ChunkManifestChunk(1, "sha256:" + "c".repeat(64),
                                "sha256:" + "d".repeat(64), 75L,
                                "storage/tenant/7/chunk/sha256:" + "d".repeat(64),
                                "S3", null, "SHA-256", 3L, 1)
                ));

        assertThat(canonicalizer.normalize(draft).totalSize()).isEqualTo(64 * 1024L + 3);
        assertThat(canonicalizer.manifestHash(draft)).startsWith("sha256:");
    }

    /**
     * 验证 framed v2 descriptor 的持久化 JSON 能复用同一 allowlist 并无损往返。
     */
    @Test
    void encryptionJson_shouldRoundTripValidatedFramedDescriptor() {
        ChunkManifestEncryption encryption = framedEncryption();

        String json = canonicalizer.encryptionJson(encryption);

        assertThat(json).contains("\"formatVersion\":2", "\"algorithmSuite\":\"RP-AES256-GCM-FRAMED-V2\"");
        assertThat(canonicalizer.parseEncryptionJson(json)).isEqualTo(encryption);
        assertThat(canonicalizer.encryptionJson(null)).isNull();
        assertThat(canonicalizer.parseEncryptionJson("  ")).isNull();
    }

    /**
     * 验证数据库中的损坏 descriptor 会转换为稳定的文件记录错误。
     */
    @Test
    void parseEncryptionJson_shouldRejectMalformedDescriptor() {
        assertThatThrownBy(() -> canonicalizer.parseEncryptionJson("{not-json"))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("格式无效"));
    }

    /**
     * 验证 NONE/legacy descriptor 不得夹带任何 framed v2 专属字段。
     */
    @Test
    void normalize_shouldRejectLegacyDescriptorContainingFramedFields() {
        ChunkManifestEncryption invalid = new ChunkManifestEncryption(
                ChunkManifestEncryption.FORMAT_NONE,
                ChunkManifestEncryption.SUITE_FRAMED_V2,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> canonicalizer.encryptionJson(invalid))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("must not contain v2 fields"));
    }

    /**
     * 验证未知格式、套件、frame 上限和非规范 nonce 均失败关闭。
     */
    @Test
    void normalize_shouldRejectInvalidFramedDescriptorVariants() {
        assertThatThrownBy(() -> canonicalizer.encryptionJson(new ChunkManifestEncryption(
                99, null, null, null, null, null, null, null)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("formatVersion"));

        assertThatThrownBy(() -> canonicalizer.encryptionJson(new ChunkManifestEncryption(
                ChunkManifestEncryption.FORMAT_FRAMED_V2,
                "UNKNOWN-SUITE",
                "AAAAAAAAAAAAAAAAAAAAAA",
                64 * 1024,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2,
                ChunkManifestEncryption.TAG_SIZE_BYTES)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("allowlist"));

        assertThatThrownBy(() -> canonicalizer.encryptionJson(new ChunkManifestEncryption(
                ChunkManifestEncryption.FORMAT_FRAMED_V2,
                ChunkManifestEncryption.SUITE_FRAMED_V2,
                "AAAAAAAAAAAAAAAAAAAAAA",
                ChunkManifestEncryption.MIN_FRAME_PLAIN_SIZE - 1,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2,
                ChunkManifestEncryption.TAG_SIZE_BYTES)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("framePlainSize"));

        assertThatThrownBy(() -> canonicalizer.encryptionJson(new ChunkManifestEncryption(
                ChunkManifestEncryption.FORMAT_FRAMED_V2,
                ChunkManifestEncryption.SUITE_FRAMED_V2,
                "%%%",
                64 * 1024,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2,
                ChunkManifestEncryption.TAG_SIZE_BYTES)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("fileNonce"));
    }

    /**
     * 验证 v2 分片必须携带正数 plainSize/frameCount，并满足精确密文字节公式。
     */
    @Test
    void normalize_shouldRejectInvalidFramedChunkEvidenceVariants() {
        assertThatThrownBy(() -> canonicalizer.normalize(framedDraft(
                new ChunkManifestChunk(0, canonicalHash('a'), canonicalHash('b'), 72L,
                        "storage/tenant/7/chunk/0", "S3", null, "SHA-256", 0L, 1),
                1L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("plainSize must be positive"));

        assertThatThrownBy(() -> canonicalizer.normalize(framedDraft(
                new ChunkManifestChunk(0, canonicalHash('a'), canonicalHash('b'), 73L,
                        "storage/tenant/7/chunk/0", "S3", null, "SHA-256", 1L, 0),
                1L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("frameCount must be positive"));

        assertThatThrownBy(() -> canonicalizer.normalize(framedDraft(
                new ChunkManifestChunk(0, canonicalHash('a'), canonicalHash('b'), 73L,
                        "storage/tenant/7/chunk/0", "S3", null, "SHA-256", null, null),
                1L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("plainSize and frameCount are required"));

        assertThatThrownBy(() -> canonicalizer.normalize(framedDraft(
                new ChunkManifestChunk(0, canonicalHash('a'), canonicalHash('b'), 74L,
                        "storage/tenant/7/chunk/0", "S3", null, "SHA-256", 1L, 1),
                1L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("inconsistent"));
    }

    /**
     * 验证 v2 manifest 的 totalSize 明确按明文总量校验。
     */
    @Test
    void normalize_shouldRejectFramedPlainTotalMismatch() {
        ChunkManifestChunk chunk = new ChunkManifestChunk(
                0, canonicalHash('a'), canonicalHash('b'), 73L,
                "storage/tenant/7/chunk/0", "S3", null, "SHA-256", 1L, 1);

        assertThatThrownBy(() -> canonicalizer.normalize(framedDraft(chunk, 2L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("sum of chunk plain sizes"));
    }

    private ChunkManifestDraft draft(List<ChunkManifestChunk> chunks) {
        return new ChunkManifestDraft(
                null,
                "file-hash",
                null,
                10L,
                10L,
                null,
                "CHACHA20_POLY1305",
                "S3",
                chunks
        );
    }

    private ChunkManifestChunk chunk(int index, String plainHash, String cipherHash, long size, String storagePath) {
        return new ChunkManifestChunk(index, plainHash, cipherHash, size, storagePath, null, null, null);
    }

    /**
     * 构造 allowlist 内的 framed v2 descriptor。
     */
    private ChunkManifestEncryption framedEncryption() {
        return new ChunkManifestEncryption(
                ChunkManifestEncryption.FORMAT_FRAMED_V2,
                ChunkManifestEncryption.SUITE_FRAMED_V2,
                "AAAAAAAAAAAAAAAAAAAAAA",
                64 * 1024,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2,
                ChunkManifestEncryption.TAG_SIZE_BYTES);
    }

    /**
     * 构造单分片 framed v2 draft，便于逐项验证分片证据。
     */
    private ChunkManifestDraft framedDraft(ChunkManifestChunk chunk, long totalSize) {
        return new ChunkManifestDraft(
                null,
                "file-hash",
                null,
                64 * 1024L,
                totalSize,
                null,
                "FRAMED_AEAD_V2",
                "S3",
                framedEncryption(),
                List.of(chunk));
    }

    /**
     * 构造规范 sha256 摘要。
     */
    private String canonicalHash(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
