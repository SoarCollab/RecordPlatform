package cn.flying.service.manifest;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FileChunkManifest;
import cn.flying.dao.entity.FileChunkManifestItem;
import cn.flying.dao.mapper.FileChunkManifestItemMapper;
import cn.flying.dao.mapper.FileChunkManifestMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.test.builders.BuilderResetExtension;
import cn.flying.test.builders.FileTestBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(BuilderResetExtension.class)
@DisplayName("ChunkManifestServiceImpl")
class ChunkManifestServiceImplTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final Long FILE_ID = 99L;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileChunkManifestMapper manifestMapper;

    @Mock
    private FileChunkManifestItemMapper manifestItemMapper;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private ChunkManifestServiceImpl service;

    /**
     * Initializes MyBatis-Plus lambda metadata for pure Mockito tests.
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, File.class);
        TableInfoHelper.initTableInfo(assistant, FileChunkManifest.class);
        TableInfoHelper.initTableInfo(assistant, FileChunkManifestItem.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChunkManifestServiceImpl(
                fileMapper,
                manifestMapper,
                manifestItemMapper,
                new ChunkManifestCanonicalizer(),
                snowflakeIdGenerator
        );
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Verifies saving a manifest persists one header and ordered chunk rows.
     */
    @Test
    void saveManifest_shouldPersistHeaderAndOrderedChunks() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(successfulFile());
        when(snowflakeIdGenerator.nextId()).thenReturn(1000L, 1001L, 1002L);

        ChunkManifestView view = service.saveManifest(USER_ID, FILE_ID, draft());

        ArgumentCaptor<FileChunkManifest> manifestCaptor = ArgumentCaptor.forClass(FileChunkManifest.class);
        verify(manifestMapper).insert(manifestCaptor.capture());
        FileChunkManifest manifest = manifestCaptor.getValue();
        assertThat(manifest.getId()).isEqualTo(1000L);
        assertThat(manifest.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(manifest.getFileId()).isEqualTo(FILE_ID);
        assertThat(manifest.getFileHash()).isEqualTo("file-hash");
        assertThat(manifest.getManifestHash()).startsWith("sha256:");
        assertThat(manifest.getManifestJson()).contains("\"chunks\"");
        assertThat(manifest.getChunkCount()).isEqualTo(2);

        ArgumentCaptor<FileChunkManifestItem> itemCaptor = ArgumentCaptor.forClass(FileChunkManifestItem.class);
        verify(manifestItemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues())
                .extracting(FileChunkManifestItem::getChunkIndex)
                .containsExactly(0, 1);
        assertThat(itemCaptor.getAllValues())
                .extracting(FileChunkManifestItem::getManifestId)
                .containsOnly(1000L);

        assertThat(view.manifestId()).isEqualTo(1000L);
        assertThat(view.chunks())
                .extracting(ChunkManifestChunk::index)
                .containsExactly(0, 1);
    }

    /**
     * Verifies files without a manifest return Optional.empty without reading chunks.
     */
    @Test
    void findActiveManifest_shouldReturnEmptyWhenManifestIsAbsent() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(successfulFile());
        when(manifestMapper.selectOne(any())).thenReturn(null);

        Optional<ChunkManifestView> view = service.findActiveManifest(USER_ID, FILE_ID);

        assertThat(view).isEmpty();
        verify(manifestItemMapper, never()).selectList(any());
    }

    /**
     * Verifies loading an active manifest maps header metadata and ordered chunks.
     */
    @Test
    void findActiveManifest_shouldLoadHeaderAndChunks() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(successfulFile());
        when(manifestMapper.selectOne(any())).thenReturn(new FileChunkManifest()
                .setId(1000L)
                .setFileId(FILE_ID)
                .setFileVersion(3)
                .setSchemaId(ChunkManifestCanonicalizer.SCHEMA_ID)
                .setFileHash("file-hash")
                .setManifestHash("sha256:abc")
                .setHashAlgorithm(ChunkManifestCanonicalizer.HASH_ALGORITHM)
                .setChunkSize(10L)
                .setChunkCount(2)
                .setTotalSize(10L)
                .setStorageBackend("S3"));
        when(manifestItemMapper.selectList(any())).thenReturn(List.of(
                new FileChunkManifestItem()
                        .setChunkIndex(0)
                        .setPlainHash("plain-0")
                        .setCipherHash("cipher-0")
                        .setSize(6L)
                        .setStoragePath("storage/tenant/7/chunk/0")
                        .setStorageBackend("S3")
                        .setChecksumAlgorithm("SHA-256"),
                new FileChunkManifestItem()
                        .setChunkIndex(1)
                        .setPlainHash("plain-1")
                        .setCipherHash("cipher-1")
                        .setSize(4L)
                        .setStoragePath("storage/tenant/7/chunk/1")
                        .setStorageBackend("S3")
                        .setChecksumAlgorithm("SHA-256")
        ));

        Optional<ChunkManifestView> view = service.findActiveManifest(USER_ID, FILE_ID);

        assertThat(view).isPresent();
        assertThat(view.get().manifestId()).isEqualTo(1000L);
        assertThat(view.get().fileVersion()).isEqualTo(3);
        assertThat(view.get().chunks())
                .extracting(ChunkManifestChunk::storagePath)
                .containsExactly("storage/tenant/7/chunk/0", "storage/tenant/7/chunk/1");
    }

    /**
     * Verifies system callers load a whole tenant batch in two queries and see duplicate active rows.
     */
    @Test
    void findActiveManifests_shouldBatchLoadChunksAndReportDuplicateActiveRows() {
        long secondFileId = 100L;
        FileChunkManifest selected = manifest(1000L, FILE_ID, 2);
        FileChunkManifest duplicate = manifest(999L, FILE_ID, 1);
        FileChunkManifest second = manifest(2000L, secondFileId, 1);
        when(manifestMapper.selectList(any())).thenReturn(List.of(selected, duplicate, second));
        when(manifestItemMapper.selectList(any())).thenReturn(List.of(
                item(1001L, 1000L, FILE_ID, 0),
                item(1002L, 1000L, FILE_ID, 1),
                item(2001L, 2000L, secondFileId, 0)
        ));

        ChunkManifestBatchView batch = service.findActiveManifests(List.of(FILE_ID, secondFileId, FILE_ID));

        assertThat(batch.manifests()).containsOnlyKeys(FILE_ID, secondFileId);
        assertThat(batch.duplicateFileIds()).containsExactly(FILE_ID);
        assertThat(batch.manifests().get(FILE_ID).manifestId()).isEqualTo(1000L);
        assertThat(batch.manifests().get(FILE_ID).chunks())
                .extracting(ChunkManifestChunk::index)
                .containsExactly(0, 1);
        verify(manifestMapper, times(1)).selectList(any());
        verify(manifestItemMapper, times(1)).selectList(any());
        verify(fileMapper, never()).selectById(any());
    }

    /**
     * Verifies empty batch input avoids emitting an invalid empty IN clause.
     */
    @Test
    void findActiveManifests_shouldReturnEmptyWithoutQueriesForEmptyInput() {
        ChunkManifestBatchView batch = service.findActiveManifests(List.of());

        assertThat(batch.manifests()).isEmpty();
        assertThat(batch.duplicateFileIds()).isEmpty();
        verify(manifestMapper, never()).selectList(any());
        verify(manifestItemMapper, never()).selectList(any());
    }

    /**
     * Verifies oversized IN batches fail before persistence access.
     */
    @Test
    void findActiveManifests_shouldRejectMoreThanOneThousandDistinctIds() {
        List<Long> fileIds = LongStream.rangeClosed(1, 1001).boxed().toList();

        assertThatThrownBy(() -> service.findActiveManifests(fileIds))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                        .isEqualTo(ResultEnum.PARAM_IS_INVALID));

        verify(manifestMapper, never()).selectList(any());
        verify(manifestItemMapper, never()).selectList(any());
    }

    /**
     * Verifies manifest fileHash must match the file record hash.
     */
    @Test
    void saveManifest_shouldRejectFileHashMismatch() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(successfulFile());
        ChunkManifestDraft mismatch = new ChunkManifestDraft(
                null,
                "other-hash",
                null,
                10L,
                10L,
                null,
                null,
                null,
                draft().chunks()
        );

        assertThatThrownBy(() -> service.saveManifest(USER_ID, FILE_ID, mismatch))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));

        verify(manifestMapper, never()).insert(any(FileChunkManifest.class));
        verify(manifestItemMapper, never()).insert(any(FileChunkManifestItem.class));
    }

    /**
     * 首次历史回填在文件行锁内创建 insert-only 活跃清单。
     */
    @Test
    void createBackfilledManifestIfAbsent_shouldInsertWhenNoActiveManifestExists() {
        when(fileMapper.selectByIdForManifestBackfillUpdate(TENANT_ID, FILE_ID))
                .thenReturn(successfulFile());
        when(manifestMapper.selectList(any())).thenReturn(List.of());
        when(snowflakeIdGenerator.nextId()).thenReturn(1000L, 1001L, 1002L);

        BackfilledManifestPublication publication = service.createBackfilledManifestIfAbsent(
                USER_ID, FILE_ID, draft());

        assertThat(publication.created()).isTrue();
        assertThat(publication.manifest().manifestId()).isEqualTo(1000L);
        assertThat(publication.manifest().chunks())
                .extracting(ChunkManifestChunk::index)
                .containsExactly(0, 1);
        verify(manifestMapper).insert(any(FileChunkManifest.class));
        verify(manifestItemMapper, times(2)).insert(any(FileChunkManifestItem.class));
        verify(manifestMapper, never()).update(any(), any());
    }

    /**
     * 相同冻结证据命中既有活跃清单时返回幂等结果且不写新行。
     */
    @Test
    void createBackfilledManifestIfAbsent_shouldReuseEquivalentActiveManifest() {
        String manifestHash = service.calculateManifestHash(draft());
        when(fileMapper.selectByIdForManifestBackfillUpdate(TENANT_ID, FILE_ID))
                .thenReturn(successfulFile());
        when(manifestMapper.selectList(any())).thenReturn(List.of(persistedDraftManifest(manifestHash)));
        when(manifestItemMapper.selectList(any())).thenReturn(persistedDraftItems());

        BackfilledManifestPublication publication = service.createBackfilledManifestIfAbsent(
                USER_ID, FILE_ID, draft());

        assertThat(publication.created()).isFalse();
        assertThat(publication.manifest().manifestHash()).isEqualTo(manifestHash);
        assertThat(publication.manifest().chunks())
                .extracting(ChunkManifestChunk::plainHash)
                .containsExactly("plain-0", "plain-1");
        verify(manifestMapper, never()).insert(any(FileChunkManifest.class));
        verify(manifestItemMapper, never()).insert(any(FileChunkManifestItem.class));
    }

    /**
     * 重复活跃行或与冻结证据不一致的既有清单进入人工审查而不覆盖产品数据。
     */
    @Test
    void createBackfilledManifestIfAbsent_shouldRejectDuplicateOrConflictingActiveManifest() {
        String manifestHash = service.calculateManifestHash(draft());
        when(fileMapper.selectByIdForManifestBackfillUpdate(TENANT_ID, FILE_ID))
                .thenReturn(successfulFile());
        when(manifestMapper.selectList(any())).thenReturn(
                List.of(persistedDraftManifest(manifestHash), persistedDraftManifest(manifestHash).setId(1001L)),
                List.of(persistedDraftManifest("sha256:" + "f".repeat(64))));

        assertThatThrownBy(() -> service.createBackfilledManifestIfAbsent(USER_ID, FILE_ID, draft()))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));

        when(manifestItemMapper.selectList(any())).thenReturn(persistedDraftItems());
        assertThatThrownBy(() -> service.createBackfilledManifestIfAbsent(USER_ID, FILE_ID, draft()))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));

        verify(manifestMapper, never()).insert(any(FileChunkManifest.class));
        verify(manifestItemMapper, never()).insert(any(FileChunkManifestItem.class));
    }

    /**
     * 锁定加载阶段拒绝空 ID、缺失文件、跨租户文件与其他用户文件。
     */
    @Test
    void createBackfilledManifestIfAbsent_shouldEnforceLockedFileOwnership() {
        assertThatThrownBy(() -> service.createBackfilledManifestIfAbsent(USER_ID, null, draft()))
                .isInstanceOf(GeneralException.class);

        when(fileMapper.selectByIdForManifestBackfillUpdate(TENANT_ID, FILE_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.createBackfilledManifestIfAbsent(USER_ID, FILE_ID, draft()))
                .isInstanceOf(GeneralException.class);

        long otherTenantFileId = 100L;
        when(fileMapper.selectByIdForManifestBackfillUpdate(TENANT_ID, otherTenantFileId))
                .thenReturn(new File().setId(otherTenantFileId).setTenantId(8L).setUid(USER_ID));
        assertThatThrownBy(() -> service.createBackfilledManifestIfAbsent(
                USER_ID, otherTenantFileId, draft())).isInstanceOf(GeneralException.class);

        long otherUserFileId = 101L;
        when(fileMapper.selectByIdForManifestBackfillUpdate(TENANT_ID, otherUserFileId))
                .thenReturn(new File().setId(otherUserFileId).setTenantId(TENANT_ID).setUid(43L));
        assertThatThrownBy(() -> service.createBackfilledManifestIfAbsent(
                USER_ID, otherUserFileId, draft())).isInstanceOf(GeneralException.class);

        verify(manifestMapper, never()).selectList(any());
    }

    private File successfulFile() {
        return FileTestBuilder.aFile(file -> file
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileHash("file-hash")
                .setVersion(3));
    }

    /**
     * 创建与 draft 完全一致的持久化清单头。
     */
    private FileChunkManifest persistedDraftManifest(String manifestHash) {
        return new FileChunkManifest()
                .setId(1000L)
                .setTenantId(TENANT_ID)
                .setFileId(FILE_ID)
                .setFileVersion(3)
                .setSchemaId(ChunkManifestCanonicalizer.SCHEMA_ID)
                .setFileHash("file-hash")
                .setManifestHash(manifestHash)
                .setHashAlgorithm(ChunkManifestCanonicalizer.HASH_ALGORITHM)
                .setChunkSize(10L)
                .setChunkCount(2)
                .setTotalSize(10L)
                .setEncryptionAlgorithm("CHACHA20_POLY1305")
                .setStorageBackend("S3")
                .setStatus("ACTIVE")
                .setDeleted(0);
    }

    /**
     * 创建与规范化 draft 顺序和默认值一致的持久化分片。
     */
    private List<FileChunkManifestItem> persistedDraftItems() {
        return List.of(
                new FileChunkManifestItem()
                        .setManifestId(1000L)
                        .setChunkIndex(0)
                        .setPlainHash("plain-0")
                        .setCipherHash("cipher-0")
                        .setSize(6L)
                        .setStoragePath("storage/tenant/7/chunk/0")
                        .setStorageBackend("S3")
                        .setChecksumAlgorithm("SHA-256"),
                new FileChunkManifestItem()
                        .setManifestId(1000L)
                        .setChunkIndex(1)
                        .setPlainHash("plain-1")
                        .setCipherHash("cipher-1")
                        .setSize(4L)
                        .setStoragePath("storage/tenant/7/chunk/1")
                        .setStorageBackend("S3")
                        .setChecksumAlgorithm("SHA-256")
        );
    }

    /**
     * Builds an active manifest header for batch-loading tests.
     */
    private FileChunkManifest manifest(Long manifestId, Long fileId, int chunkCount) {
        return new FileChunkManifest()
                .setId(manifestId)
                .setTenantId(TENANT_ID)
                .setFileId(fileId)
                .setFileVersion(1)
                .setSchemaId(ChunkManifestCanonicalizer.SCHEMA_ID)
                .setFileHash("file-hash")
                .setManifestHash("sha256:" + "a".repeat(64))
                .setHashAlgorithm(ChunkManifestCanonicalizer.HASH_ALGORITHM)
                .setChunkSize(5L)
                .setChunkCount(chunkCount)
                .setTotalSize(chunkCount * 5L)
                .setEncryptionAlgorithm("NONE")
                .setStorageBackend("S3")
                .setStatus("ACTIVE")
                .setDeleted(0);
    }

    /**
     * Builds a persisted chunk item for batch-loading tests.
     */
    private FileChunkManifestItem item(Long id, Long manifestId, Long fileId, int index) {
        String hash = "sha256:" + Integer.toHexString(index + 1).repeat(64).substring(0, 64);
        return new FileChunkManifestItem()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setManifestId(manifestId)
                .setFileId(fileId)
                .setChunkIndex(index)
                .setPlainHash(hash)
                .setCipherHash(hash)
                .setSize(5L)
                .setStoragePath("storage/tenant/" + TENANT_ID + "/chunk/" + hash)
                .setStorageBackend("S3")
                .setEtag("etag-" + index)
                .setChecksumAlgorithm("SHA-256")
                .setDeleted(0);
    }

    private ChunkManifestDraft draft() {
        return new ChunkManifestDraft(
                null,
                "file-hash",
                null,
                10L,
                10L,
                null,
                "CHACHA20_POLY1305",
                "S3",
                List.of(
                        new ChunkManifestChunk(1, "plain-1", "cipher-1", 4L, "storage/tenant/7/chunk/1", null, null, null),
                        new ChunkManifestChunk(0, "plain-0", "cipher-0", 6L, "storage/tenant/7/chunk/0", null, null, null)
                )
        );
    }
}
