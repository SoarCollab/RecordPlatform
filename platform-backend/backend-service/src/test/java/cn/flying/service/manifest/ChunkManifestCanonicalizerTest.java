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
        ChunkManifestEncryption encryption = new ChunkManifestEncryption(
                ChunkManifestEncryption.FORMAT_FRAMED_V2,
                ChunkManifestEncryption.SUITE_FRAMED_V2,
                "AAAAAAAAAAAAAAAAAAAAAA",
                64 * 1024,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2,
                ChunkManifestEncryption.TAG_SIZE_BYTES);
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
}
