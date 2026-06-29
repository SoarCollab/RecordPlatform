package cn.flying.service.proof;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.vo.file.ProofBundleVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.attestation.MerkleTreeService;
import cn.flying.service.remote.FileRemoteClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProofBundleServiceImpl")
class ProofBundleServiceImplTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final Long FILE_ID = 11L;
    private static final Long LEAF_ID = 901L;
    private static final Long BATCH_ID = 900L;
    private static final String FILE_HASH = "hash-a";

    @Mock
    private FileMapper fileMapper;

    @Mock
    private AttestationLeafMapper leafMapper;

    @Mock
    private AttestationBatchMapper batchMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    private ProofBundleServiceImpl service;

    /**
     * 初始化服务和静态外部 ID 编码器。
     */
    @BeforeEach
    void setUp() {
        service = new ProofBundleServiceImpl(fileMapper, leafMapper, batchMapper, fileRemoteClient);
        TenantContext.setTenantId(TENANT_ID);
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234")
        );
    }

    /**
     * 清理租户上下文，避免测试间污染。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 验证按文件 ID 导出证明包时聚合文件、存储、Merkle 与链上回执元数据。
     */
    @Test
    void exportByFileId_shouldBuildProofBundle() {
        try (MockedStatic<SecurityUtils> security = mockStaticUser()) {
            mockSuccessfulBundleDependencies(file());

            ProofBundleVO bundle = service.exportByFileId(USER_ID, FILE_ID);

            assertThat(bundle.contractVersion()).isEqualTo(ProofBundleVO.CONTRACT_VERSION);
            assertThat(bundle.manifest().type()).isEqualTo("RECORD_PLATFORM_MERKLE_FILE_PROOF");
            assertThat(bundle.file().fileHash()).isEqualTo(FILE_HASH);
            assertThat(bundle.file().chunkCount()).isEqualTo(1);
            assertThat(bundle.file().contentType()).isEqualTo("text/plain");
            assertThat(bundle.file().transactionHash()).isEqualTo("tx-file");
            assertThat(bundle.merkle().proofAlgorithm()).isEqualTo(MerkleTreeService.PROOF_ALGORITHM);
            assertThat(bundle.merkle().proofPath()).hasSize(1);
            assertThat(bundle.chain().batchTransactionHash()).isEqualTo("tx-batch");
            assertThat(bundle.storage().objects()).hasSize(1);
            assertThat(bundle.storage().objects().getFirst().metadataHashMatches()).isTrue();
            assertThat(JsonConverter.toJson(bundle)).doesNotContain("initialKey", "secret-key");
            security.verify(SecurityUtils::isAdmin);
        }
    }

    /**
     * 验证按叶子 ID 导出证明包会复用叶子绑定的文件和批次。
     */
    @Test
    void exportByLeafId_shouldBuildProofBundle() {
        try (MockedStatic<SecurityUtils> ignored = mockStaticUser()) {
            mockSuccessfulBundleDependencies(file());

            ProofBundleVO bundle = service.exportByLeafId(USER_ID, LEAF_ID);

            assertThat(bundle.manifest().leafId()).isNotBlank();
            assertThat(bundle.manifest().batchNo()).isEqualTo("MB-900");
            verify(leafMapper).selectOne(any());
            verify(batchMapper).selectById(BATCH_ID);
        }
    }

    /**
     * 验证相同输入连续导出会得到相同 JSON，便于后续签名或哈希。
     */
    @Test
    void exportByFileId_shouldGenerateDeterministicJson() {
        try (MockedStatic<SecurityUtils> ignored = mockStaticUser()) {
            mockSuccessfulBundleDependencies(file());

            String first = JsonConverter.toJson(service.exportByFileId(USER_ID, FILE_ID));
            String second = JsonConverter.toJson(service.exportByFileId(USER_ID, FILE_ID));

            assertThat(second).isEqualTo(first);
        }
    }

    /**
     * 验证文件缺少 P1-1 叶子证明时会拒绝导出。
     */
    @Test
    void exportByFileId_shouldRejectMissingProofLeaf() {
        try (MockedStatic<SecurityUtils> ignored = mockStaticUser()) {
            when(fileMapper.selectById(FILE_ID)).thenReturn(file());
            when(leafMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                            .isEqualTo(ResultEnum.FILE_RECORD_ERROR));

            verify(batchMapper, never()).selectById(any());
            verify(fileRemoteClient, never()).headObject(any(), any());
        }
    }

    /**
     * 验证租户不匹配的文件不会导出证明包。
     */
    @Test
    void exportByFileId_shouldRejectTenantMismatch() {
        try (MockedStatic<SecurityUtils> ignored = mockStaticUser()) {
            when(fileMapper.selectById(FILE_ID)).thenReturn(file().setTenantId(99L));

            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                            .isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED));
        }
    }

    /**
     * 验证已删除文件不会导出证明包。
     */
    @Test
    void exportByFileId_shouldRejectDeletedFile() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(file().setDeleted(1));

        assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_NOT_EXIST));
    }

    /**
     * 组织成功导出所需的 mapper 与远程存储 HEAD 响应。
     */
    private void mockSuccessfulBundleDependencies(File file) {
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(leafMapper.selectOne(any())).thenReturn(leaf());
        when(batchMapper.selectById(BATCH_ID)).thenReturn(batch());
        when(fileRemoteClient.headObject("storage/tenant/7/chunk/hash-a", FILE_HASH))
                .thenReturn(Result.success(new StorageObjectHeadVO(
                        true,
                        "storage/tenant/7/chunk/hash-a",
                        FILE_HASH,
                        TENANT_ID,
                        TENANT_ID,
                        "node-a",
                        1024L,
                        "etag-a",
                        FILE_HASH
                )));
    }

    /**
     * 构造成功上传的文件实体。
     */
    private File file() {
        return new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileName("report.txt")
                .setFileHash(FILE_HASH)
                .setTransactionHash("tx-file")
                .setFileParam("{\"fileSize\":1024,\"contentType\":\"text/plain\",\"chunkCount\":1,\"initialKey\":\"secret-key\"}")
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setDeleted(0)
                .setVersion(2)
                .setIsLatest(1)
                .setVersionGroupId(100L)
                .setCreateTime(new Date(1710000000000L));
    }

    /**
     * 构造已完成批次里的存证叶子。
     */
    private AttestationLeaf leaf() {
        return new AttestationLeaf()
                .setId(LEAF_ID)
                .setTenantId(TENANT_ID)
                .setBatchId(BATCH_ID)
                .setFileId(FILE_ID)
                .setFileHash(FILE_HASH)
                .setLeafHash("leaf-hash")
                .setLeafIndex(0)
                .setProofPathJson("[{\"position\":\"RIGHT\",\"hash\":\"sibling-hash\"}]")
                .setProofAlgorithm(MerkleTreeService.PROOF_ALGORITHM)
                .setDeleted(0)
                .setCreateTime(new Date(1710000001000L));
    }

    /**
     * 构造已完成的批量存证记录。
     */
    private AttestationBatch batch() {
        return new AttestationBatch()
                .setId(BATCH_ID)
                .setTenantId(TENANT_ID)
                .setBatchNo("MB-900")
                .setMerkleRoot("merkle-root")
                .setProofAlgorithm(MerkleTreeService.PROOF_ALGORITHM)
                .setLeafCount(1)
                .setStatus("COMPLETED")
                .setChainTransactionHash("tx-batch")
                .setChainFileHash("chain-root")
                .setDeleted(0);
    }

    /**
     * Mock 当前用户为普通用户。
     */
    private MockedStatic<SecurityUtils> mockStaticUser() {
        MockedStatic<SecurityUtils> security = org.mockito.Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::isAdmin).thenReturn(false);
        return security;
    }
}
