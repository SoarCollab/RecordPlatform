package cn.flying.service.attestation;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.response.StoreAttestationBatchResponse;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.test.builders.BuilderResetExtension;
import cn.flying.test.builders.FileTestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(BuilderResetExtension.class)
@DisplayName("AttestationBatchServiceImpl")
class AttestationBatchServiceImplTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 42L;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private AttestationBatchMapper batchMapper;

    @Mock
    private AttestationLeafMapper leafMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private AttestationBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AttestationBatchServiceImpl(
                fileMapper,
                batchMapper,
                leafMapper,
                new MerkleTreeService(),
                fileRemoteClient,
                snowflakeIdGenerator
        );
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Verifies batch creation persists leaves and records the Merkle root through RemoteClient.
     */
    @Test
    void createBatch_shouldPersistMerkleBatchAndLeaves() {
        File first = successfulFile(11L, "hash-b");
        File second = successfulFile(12L, "hash-a");
        when(fileMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(first, second));
        when(snowflakeIdGenerator.nextId()).thenReturn(900L, 901L, 902L);
        when(fileRemoteClient.storeAttestationBatch(any(StoreAttestationBatchRequest.class)))
                .thenReturn(Result.success(new StoreAttestationBatchResponse("tx-root", "root-hash")));
        doAnswer(invocation -> {
            AttestationBatch batch = invocation.getArgument(0);
            assertThat(batch.getStatus()).isEqualTo("CHAIN_PENDING");
            return 1;
        }).when(batchMapper).insert(any(AttestationBatch.class));

        AttestationBatch batch = service.createBatch(USER_ID, List.of(11L, 12L));

        assertThat(batch.getId()).isEqualTo(900L);
        assertThat(batch.getStatus()).isEqualTo("COMPLETED");
        assertThat(batch.getChainTransactionHash()).isEqualTo("tx-root");
        assertThat(batch.getChainFileHash()).isEqualTo("root-hash");

        ArgumentCaptor<AttestationLeaf> leafCaptor = ArgumentCaptor.forClass(AttestationLeaf.class);
        verify(leafMapper, times(2)).insert(leafCaptor.capture());
        assertThat(leafCaptor.getAllValues())
                .extracting(AttestationLeaf::getBatchId)
                .containsOnly(900L);
        assertThat(leafCaptor.getAllValues())
                .extracting(AttestationLeaf::getFileId)
                .containsExactly(12L, 11L);
        assertThat(leafCaptor.getAllValues())
                .allSatisfy(leaf -> {
                    assertThat(leaf.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(leaf.getProofPathJson()).contains("position");
                    assertThat(leaf.getProofAlgorithm()).isEqualTo(MerkleTreeService.PROOF_ALGORITHM);
                });

        ArgumentCaptor<StoreAttestationBatchRequest> requestCaptor =
                ArgumentCaptor.forClass(StoreAttestationBatchRequest.class);
        verify(fileRemoteClient).storeAttestationBatch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(requestCaptor.getValue().batchId()).isEqualTo(900L);
        assertThat(requestCaptor.getValue().batchNo()).isEqualTo("MB-900");
        assertThat(requestCaptor.getValue().leafCount()).isEqualTo(2);
        assertThat(requestCaptor.getValue().merkleRoot()).isEqualTo(batch.getMerkleRoot());
        verify(batchMapper).updateById(batch);
    }

    /**
     * Verifies chain failures do not mark a batch as completed.
     */
    @Test
    void createBatch_shouldRejectInvalidChainResponse() {
        File file = successfulFile(11L, "hash-a");
        when(fileMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(file));
        when(snowflakeIdGenerator.nextId()).thenReturn(900L, 901L);
        when(fileRemoteClient.storeAttestationBatch(any(StoreAttestationBatchRequest.class)))
                .thenReturn(Result.success(null));

        assertThatThrownBy(() -> service.createBatch(USER_ID, List.of(11L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                        .isEqualTo(ResultEnum.BLOCKCHAIN_ERROR));

        verify(batchMapper, never()).updateById(any(AttestationBatch.class));
    }

    /**
     * Verifies only successful, owned files can enter a Merkle batch.
     */
    @Test
    void createBatch_shouldRejectNonSuccessfulFiles() {
        File file = successfulFile(11L, "hash-a").setStatus(FileUploadStatus.PREPARE.getCode());
        when(fileMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(file));

        assertThatThrownBy(() -> service.createBatch(USER_ID, List.of(11L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));

        verify(batchMapper, never()).insert(any(AttestationBatch.class));
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    private File successfulFile(Long id, String hash) {
        return FileTestBuilder.aFile(file -> file
                .setId(id)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileHash(hash)
                .setStatus(FileUploadStatus.SUCCESS.getCode()));
    }
}
