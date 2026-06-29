package cn.flying.service.manifest;

import cn.flying.common.exception.GeneralException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChunkManifestCanonicalizer")
class ChunkManifestCanonicalizerTest {

    private final ChunkManifestCanonicalizer canonicalizer = new ChunkManifestCanonicalizer();

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
