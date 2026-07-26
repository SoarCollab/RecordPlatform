package cn.flying.service.manifest.backfill;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.manifest.ChunkManifestBatchView;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.remote.FileRemoteClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Exercises the fail-closed historical evidence classifier at the AC-04 boundary.
 */
@ExtendWith(MockitoExtension.class)
class ManifestEvidenceResolverTest {

    private static final Long TENANT_ID = 11L;
    private static final Long USER_ID = 22L;
    private static final String CHAIN_RECORD_ID = "chain-record-1";
    private static final String CONTENT_HASH = "sha256:" + "1".repeat(64);
    private static final String PLAIN_HASH = "sha256:" + "2".repeat(64);
    private static final String CIPHER_HASH = "sha256:" + "3".repeat(64);
    private static final String STORAGE_PATH = "storage/tenant/11/chunk/" + CIPHER_HASH;

    @Mock
    private ChunkManifestService chunkManifestService;

    @Mock
    private FileRemoteClient fileRemoteClient;

    private ManifestEvidenceResolver resolver;
    private File file;

    /**
     * Creates one canonical direct-upload candidate and empty active-manifest state.
     */
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        resolver = new ManifestEvidenceResolver(chunkManifestService, fileRemoteClient);
        file = new File()
                .setId(33L)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setVersion(1)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setDeleted(0)
                .setFileHash(CHAIN_RECORD_ID)
                .setContentHash(CONTENT_HASH)
                .setFileParam(parameters(CONTENT_HASH))
                .setFileSize(8L);
        lenient().when(chunkManifestService.findActiveManifests(any()))
                .thenReturn(ChunkManifestBatchView.empty());
    }

    /**
     * Clears tenant state between resolver examples.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Accepts only a complete ordered direct-upload pointer with matching aggregate evidence.
     */
    @Test
    void shouldClassifyCompleteOrderedEvidenceAsBackfillable() {
        givenPointer(orderedEntry(0, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 8L));
        givenMatchingHead(STORAGE_PATH, CIPHER_HASH, 8L);
        when(chunkManifestService.calculateManifestHash(any(ChunkManifestDraft.class)))
                .thenReturn("sha256:" + "4".repeat(64));

        ManifestEvidenceResolution result = resolver.resolve(file);

        assertThat(result.classification()).isEqualTo(ManifestBackfillClassification.BACKFILLABLE);
        assertThat(result.reason()).isEqualTo(ManifestBackfillReason.BACKFILLABLE_EVIDENCE);
        assertThat(result.evidencePayload()).contains(CHAIN_RECORD_ID, CONTENT_HASH);
    }

    /**
     * Rejects a pointer whose cipher hash is not canonical SHA-256 evidence.
     */
    @Test
    void shouldRejectWrongCipherHash() {
        givenPointer(orderedEntry(0, PLAIN_HASH, "tail-derived-pseudo-hash", STORAGE_PATH, 8L));

        assertNotBackfillable(ManifestBackfillReason.MALFORMED_POINTER);
    }

    /**
     * Rejects aggregate-size evidence that disagrees with the persisted file record.
     */
    @Test
    void shouldRejectAggregateSizeMismatch() {
        givenPointer(orderedEntry(0, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 7L));

        assertNotBackfillable(ManifestBackfillReason.AGGREGATE_SIZE_MISMATCH);
    }

    /**
     * Rejects a missing storage object rather than publishing a partial manifest.
     */
    @Test
    void shouldRejectMissingChunk() {
        givenPointer(orderedEntry(0, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 8L));
        when(fileRemoteClient.headObject(STORAGE_PATH, CIPHER_HASH))
                .thenReturn(Result.success(StorageObjectHeadVO.missing(STORAGE_PATH, CIPHER_HASH, TENANT_ID)));

        assertNotBackfillable(ManifestBackfillReason.OBJECT_NOT_FOUND);
    }

    /**
     * Rejects duplicate ordered indexes even when all other fields look canonical.
     */
    @Test
    void shouldRejectDuplicateIndex() {
        String entry = orderedEntry(0, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 4L);
        givenPointer("[" + entry + "," + entry + "]");

        assertNotBackfillable(ManifestBackfillReason.DUPLICATE_INDEX);
    }

    /**
     * Rejects an index gap before making any storage call.
     */
    @Test
    void shouldRejectGappedIndex() {
        givenPointer(orderedEntry(1, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 8L));

        assertNotBackfillable(ManifestBackfillReason.GAPPED_INDEX);
    }

    /**
     * Rejects a logical object path that belongs to another tenant.
     */
    @Test
    void shouldRejectCrossTenantPath() {
        String otherPath = "storage/tenant/12/chunk/" + CIPHER_HASH;
        givenPointer(orderedEntry(0, PLAIN_HASH, CIPHER_HASH, otherPath, 8L));

        assertNotBackfillable(ManifestBackfillReason.CROSS_TENANT_PATH);
    }

    /**
     * Rejects legacy or unknown encryption instead of trusting a tail pseudo-hash.
     */
    @Test
    void shouldRejectLegacyEncryptionTailEvidence() {
        file.setFileParam(parameters(CONTENT_HASH).replace("NONE", "AES_GCM_V1"));
        givenPointer(orderedEntry(0, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 8L));

        assertNotBackfillable(ManifestBackfillReason.UNKNOWN_ENCRYPTION_FORMAT);
    }

    /**
     * Rejects a parameter digest that contradicts the separately persisted content hash.
     */
    @Test
    void shouldRejectContradictoryContentHash() {
        file.setFileParam(parameters("sha256:" + "9".repeat(64)));
        givenPointer(orderedEntry(0, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 8L));

        assertNotBackfillable(ManifestBackfillReason.CONTENT_HASH_MISMATCH);
    }

    /**
     * Rejects chain response identity that contradicts the requested record.
     */
    @Test
    void shouldRejectContradictoryChainIdentity() {
        FileDetailVO detail = detail(orderedEntry(0, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 8L));
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(Result.success(new FileDetailVO(
                        detail.uploader(), detail.fileName(), detail.param(), detail.content(),
                        "different-record", detail.uploadTime(), detail.uploadTimestamp(),
                        detail.fileSize(), detail.mimeType())));

        assertNotBackfillable(ManifestBackfillReason.MANUAL_REVIEW);
    }

    /**
     * Allows an explicit bounded legacy choice only when one chunk has complete independent identity evidence.
     */
    @Test
    void shouldAllowExplicitLegacyDownloadForVerifiedSingleChunkMap() {
        givenRawPointer("{\"" + CIPHER_HASH + "\":\"" + STORAGE_PATH + "\"}");
        givenMatchingHead(STORAGE_PATH, CIPHER_HASH, 8L);

        ManifestEvidenceResolution result = resolver.resolve(file);

        assertThat(result.classification()).isEqualTo(ManifestBackfillClassification.REUPLOAD_REQUIRED);
        assertThat(result.reason()).isEqualTo(ManifestBackfillReason.LEGACY_DOWNLOAD_ALLOWED);
        assertThat(result.legacyDownloadAllowed()).isTrue();
    }

    /**
     * Rejects multi-chunk legacy recovery because map traversal cannot prove reconstruction order.
     */
    @Test
    void shouldDenyLegacyDownloadForUnorderedMultiChunkMap() {
        String secondHash = "sha256:" + "4".repeat(64);
        String secondPath = "storage/tenant/11/chunk/" + secondHash;
        givenRawPointer("{\"" + CIPHER_HASH + "\":\"" + STORAGE_PATH
                + "\",\"" + secondHash + "\":\"" + secondPath + "\"}");
        givenMatchingHead(STORAGE_PATH, CIPHER_HASH, 4L);
        givenMatchingHead(secondPath, secondHash, 4L);

        ManifestEvidenceResolution result = resolver.resolve(file);

        assertThat(result.classification()).isEqualTo(ManifestBackfillClassification.REUPLOAD_REQUIRED);
        assertThat(result.reason()).isEqualTo(ManifestBackfillReason.LEGACY_ORDER_UNTRUSTED);
        assertThat(result.legacyDownloadAllowed()).isFalse();
    }

    /**
     * Requires source provenance for copied share rows instead of migrating shared identity blindly.
     */
    @Test
    void shouldRequireReuploadForShareCopy() {
        file.setOrigin(99L);

        assertNotBackfillable(ManifestBackfillReason.SHARE_SOURCE_UNPROVEN);
    }

    /**
     * Ignores upload rows that never reached SUCCESS.
     */
    @Test
    void shouldIgnorePrepareRecord() {
        file.setStatus(FileUploadStatus.PREPARE.getCode());

        ManifestEvidenceResolution result = resolver.resolve(file);

        assertThat(result.classification()).isEqualTo(ManifestBackfillClassification.IGNORED);
        assertThat(result.reason()).isEqualTo(ManifestBackfillReason.FILE_NOT_SUCCESS);
    }

    /**
     * Ignores logically deleted versions while preserving a deterministic evidence digest.
     */
    @Test
    void shouldIgnoreDeletedRecord() {
        file.setDeleted(1);

        ManifestEvidenceResolution result = resolver.resolve(file);

        assertThat(result.classification()).isEqualTo(ManifestBackfillClassification.IGNORED);
        assertThat(result.reason()).isEqualTo(ManifestBackfillReason.FILE_DELETED);
    }

    /**
     * Routes duplicate active manifests to manual review and never selects a winner.
     */
    @Test
    void shouldRejectDuplicateActiveManifest() {
        when(chunkManifestService.findActiveManifests(any()))
                .thenReturn(new ChunkManifestBatchView(java.util.Map.of(), java.util.Set.of(file.getId())));

        assertNotBackfillable(ManifestBackfillReason.DUPLICATE_ACTIVE_MANIFEST);
    }

    /**
     * Classifies an unavailable chain pointer lookup as bounded retryable work.
     */
    @Test
    void shouldRetryTransientChainFailure() {
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(new Result<>(503, "unavailable", null));

        ManifestEvidenceResolution result = resolver.resolve(file);

        assertThat(result.classification()).isEqualTo(ManifestBackfillClassification.FAILED);
        assertThat(result.reason()).isEqualTo(ManifestBackfillReason.CHAIN_RPC_TRANSIENT);
        assertThat(result.retryable()).isTrue();
    }

    /**
     * Classifies an unavailable storage HEAD as bounded retryable work.
     */
    @Test
    void shouldRetryTransientStorageFailure() {
        givenPointer(orderedEntry(0, PLAIN_HASH, CIPHER_HASH, STORAGE_PATH, 8L));
        when(fileRemoteClient.headObject(STORAGE_PATH, CIPHER_HASH))
                .thenReturn(new Result<>(503, "unavailable", null));

        ManifestEvidenceResolution result = resolver.resolve(file);

        assertThat(result.classification()).isEqualTo(ManifestBackfillClassification.FAILED);
        assertThat(result.reason()).isEqualTo(ManifestBackfillReason.STORAGE_RPC_TRANSIENT);
        assertThat(result.retryable()).isTrue();
    }

    /**
     * Stubs the chain pointer returned for the current candidate.
     */
    private void givenPointer(String content) {
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(Result.success(detail(content)));
    }

    /**
     * Stubs a legacy object pointer without wrapping it in the ordered array format.
     */
    private void givenRawPointer(String content) {
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(Result.success(new FileDetailVO(
                        String.valueOf(USER_ID), "file.bin", file.getFileParam(), content,
                        CHAIN_RECORD_ID, "", null, 8L, "application/octet-stream")));
    }

    /**
     * Stubs strict object identity, size, tenant metadata, and ETag evidence.
     */
    private void givenMatchingHead(String path, String cipherHash, long size) {
        when(fileRemoteClient.headObject(path, cipherHash)).thenReturn(Result.success(
                new StorageObjectHeadVO(true, path, cipherHash, TENANT_ID, TENANT_ID,
                        "node-a", size, "etag-a", cipherHash)));
    }

    /**
     * Asserts a named fail-closed reason and guards against accidental backfill classification.
     */
    private void assertNotBackfillable(ManifestBackfillReason reason) {
        ManifestEvidenceResolution result = resolver.resolve(file);

        assertThat(result.classification()).isNotEqualTo(ManifestBackfillClassification.BACKFILLABLE);
        assertThat(result.reason()).isEqualTo(reason);
    }

    /**
     * Builds one strict ordered pointer entry.
     */
    private String orderedEntry(
            int index,
            String plainHash,
            String cipherHash,
            String storagePath,
            long size
    ) {
        return """
                {"index":%d,"plainHash":"%s","cipherHash":"%s",\
                "storagePath":"%s","size":%d,"eTag":"etag-a","checksumAlgorithm":"SHA-256"}
                """.formatted(index, plainHash, cipherHash, storagePath, size).replace("\n", "");
    }

    /**
     * Wraps a single entry in the ordered chain pointer array.
     */
    private FileDetailVO detail(String entry) {
        String content = entry.startsWith("[") ? entry : "[" + entry + "]";
        return new FileDetailVO(String.valueOf(USER_ID), "file.bin", file.getFileParam(), content,
                CHAIN_RECORD_ID, "", null, 8L, "application/octet-stream");
    }

    /**
     * Builds the direct-upload parameters whose content hash is distinct from the chain ID.
     */
    private String parameters(String contentHash) {
        return """
                {"fileSize":8,"chunkSize":8,"uploadMode":"DIRECT_MULTIPART",\
                "encryptionAlgorithm":"NONE","contentHash":"%s"}
                """.formatted(contentHash).replace("\n", "");
    }
}
