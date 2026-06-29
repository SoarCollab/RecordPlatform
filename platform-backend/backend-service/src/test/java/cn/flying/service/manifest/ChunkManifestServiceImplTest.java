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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    private File successfulFile() {
        return FileTestBuilder.aFile(file -> file
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileHash("file-hash")
                .setVersion(3));
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
