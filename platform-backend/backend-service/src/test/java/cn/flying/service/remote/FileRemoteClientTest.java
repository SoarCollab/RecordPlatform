package cn.flying.service.remote;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.request.GetShareInfoRequest;
import cn.flying.platformapi.request.GetAttestationBatchRequest;
import cn.flying.platformapi.request.GetUserShareCodesRequest;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.response.StoreAttestationBatchResponse;
import cn.flying.platformapi.response.GetAttestationBatchResponse;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.BlockChainMessage;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.security.BlockChainRpcAuth;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileRemoteClient blockchain RPC auth")
class FileRemoteClientTest {

    private static final String RPC_TOKEN = "backend-to-fisco-rpc-token";

    @Mock
    private BlockChainService blockChainService;

    @Mock
    private DistributedStorageService storageService;

    private FileRemoteClient fileRemoteClient;

    @BeforeEach
    void setUp() {
        fileRemoteClient = new FileRemoteClient();
        ReflectionTestUtils.setField(fileRemoteClient, "blockChainService", blockChainService);
        ReflectionTestUtils.setField(fileRemoteClient, "storageService", storageService);
        ReflectionTestUtils.setField(fileRemoteClient, "blockchainRpcToken", RPC_TOKEN);
        RpcContext.removeClientAttachment();
    }

    @AfterEach
    void tearDown() {
        RpcContext.removeClientAttachment();
    }

    /**
     * 验证后端启动时必须配置区块链 RPC 共享令牌。
     */
    @Test
    void validateRpcTokenConfiguration_shouldRejectMissingToken() {
        ReflectionTestUtils.setField(fileRemoteClient, "blockchainRpcToken", " ");

        assertThatThrownBy(fileRemoteClient::validateRpcTokenConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BlockChainRpcAuth.TOKEN_PROPERTY_NAME);
    }

    /**
     * 验证后端调用区块链服务时会携带共享令牌，且调用结束后清理 attachment。
     */
    @Test
    void storeFileOnChain_shouldAttachAndCleanRpcToken() {
        StoreFileRequest request = new StoreFileRequest(
                "user-1",
                "a.txt",
                "{}",
                "content-hash"
        );
        Result<StoreFileResponse> expected = Result.success(new StoreFileResponse("tx-1", "file-hash"));

        when(blockChainService.storeFile(request)).thenAnswer(invocation -> {
            assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<StoreFileResponse> actual = fileRemoteClient.storeFileOnChain(request);

        assertThat(actual).isSameAs(expected);
        assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                .isNull();
    }

    /**
     * 验证批量 Merkle 根使用专用区块链 RPC 边界，不再复用 storeFile。
     */
    @Test
    void storeAttestationBatch_shouldUseDedicatedRpcAndAttachRpcToken() {
        ContractRegistryEntryResponse registry = contractRegistry();
        StoreAttestationBatchRequest request = new StoreAttestationBatchRequest(
                7L,
                900L,
                "MB-900",
                "SHA-256-MERKLE-V1",
                "root-hash",
                2,
                registry
        );
        Result<StoreAttestationBatchResponse> expected =
                Result.success(new StoreAttestationBatchResponse("tx-root", "root-hash"));

        when(blockChainService.storeAttestationBatch(request)).thenAnswer(invocation -> {
            assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<StoreAttestationBatchResponse> actual = fileRemoteClient.storeAttestationBatch(request);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<StoreAttestationBatchRequest> requestCaptor =
                ArgumentCaptor.forClass(StoreAttestationBatchRequest.class);
        verify(blockChainService).storeAttestationBatch(requestCaptor.capture());
        verify(blockChainService, never()).storeFile(any(StoreFileRequest.class));
        assertThat(requestCaptor.getValue()).isEqualTo(request);
        assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                .isNull();
    }

    /**
     * 验证批量存证查询使用专用只读 RPC 并携带共享令牌。
     */
    @Test
    void getAttestationBatch_shouldUseDedicatedRpcAndAttachRpcToken() {
        GetAttestationBatchRequest request = new GetAttestationBatchRequest(
                7L, 900L, contractRegistry());
        Result<GetAttestationBatchResponse> expected =
                Result.success(GetAttestationBatchResponse.notFound(7L, 900L));
        when(blockChainService.getAttestationBatch(request)).thenAnswer(invocation -> {
            assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<GetAttestationBatchResponse> actual = fileRemoteClient.getAttestationBatch(request);

        assertThat(actual).isSameAs(expected);
        assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                .isNull();
    }

    /**
     * 验证注册表只读 RPC 携带共享令牌并返回完整列表。
     */
    @Test
    void getContractRegistry_shouldAttachAndCleanRpcToken() {
        Result<List<ContractRegistryEntryResponse>> expected =
                Result.success(List.of(contractRegistry()));
        when(blockChainService.getContractRegistry()).thenAnswer(invocation -> {
            assertThat(RpcContext.getClientAttachment()
                    .getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<List<ContractRegistryEntryResponse>> actual =
                fileRemoteClient.getContractRegistry();

        assertThat(actual).isSameAs(expected);
        assertThat(RpcContext.getClientAttachment()
                .getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY)).isNull();
    }

    /**
     * 验证非幂等 batch 写方法不再被框架透明重试，而只读查询仍允许安全重试。
     */
    @Test
    void attestationBatchRpcAnnotations_shouldOnlyRetryReadQuery() throws Exception {
        Method write = FileRemoteClient.class.getMethod(
                "storeAttestationBatch", StoreAttestationBatchRequest.class);
        Method query = FileRemoteClient.class.getMethod(
                "getAttestationBatch", GetAttestationBatchRequest.class);

        assertThat(write.getAnnotation(io.github.resilience4j.retry.annotation.Retry.class)).isNull();
        assertThat(query.getAnnotation(io.github.resilience4j.retry.annotation.Retry.class)).isNotNull();
    }

    /**
     * 构造批次 RPC 使用的完整合约注册表快照。
     */
    private ContractRegistryEntryResponse contractRegistry() {
        return new ContractRegistryEntryResponse(
                "record-platform-contract-registry-entry.v1",
                "sha256:" + "1".repeat(64),
                "Sharing",
                "2.0.0",
                "LOCAL_FISCO",
                "chain0",
                "group0",
                "0x1111111111111111111111111111111111111111",
                "ABI-CANONICAL-JSON-SHA256-V1",
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                "sha256:" + "4".repeat(64),
                null,
                null,
                "ACTIVE",
                "2026-07-13T00:00:00Z",
                "REDEPLOY_ADDRESS");
    }

    /**
     * 验证缺失共享令牌时后端不会发起区块链 Dubbo 调用。
     */
    @Test
    void getFile_shouldRejectWhenRpcTokenMissing() {
        ReflectionTestUtils.setField(fileRemoteClient, "blockchainRpcToken", "");

        assertThatThrownBy(() -> fileRemoteClient.getFile("user-1", "file-hash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Blockchain RPC token is not configured");

        verifyNoInteractions(blockChainService);
    }

    /**
     * 验证已有同名 attachment 时调用结束后会恢复旧值。
     */
    @Test
    void getFile_shouldRestorePreviousRpcTokenAttachment() {
        RpcContext.getClientAttachment().setAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY, "previous-token");
        Result<FileDetailVO> expected = Result.success(null);

        when(blockChainService.getFile("user-1", "file-hash")).thenAnswer(invocation -> {
            assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<FileDetailVO> actual = fileRemoteClient.getFile("user-1", "file-hash");

        assertThat(actual).isSameAs(expected);
        assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                .isEqualTo("previous-token");
    }

    /**
     * 验证获取分享码时会把查看者身份封装进区块链请求。
     */
    @Test
    void getUserShareCodes_shouldWrapUploaderAndRequesterInRpcRequest() {
        Result<List<String>> expected = Result.success(List.of("share-1"));
        when(blockChainService.getUserShareCodes(any(GetUserShareCodesRequest.class))).thenAnswer(invocation -> {
            GetUserShareCodesRequest request = invocation.getArgument(0);
            assertThat(request.uploader()).isEqualTo("owner");
            assertThat(request.requester()).isEqualTo("viewer");
            assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<List<String>> actual = fileRemoteClient.getUserShareCodes("owner", "viewer");

        assertThat(actual).isSameAs(expected);
        assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                .isNull();
    }

    /**
     * 验证获取分享详情时会把分享码和查看者身份封装进区块链请求。
     */
    @Test
    void getShareInfo_shouldWrapShareCodeAndRequesterInRpcRequest() {
        Result<SharingVO> expected = Result.success(null);
        when(blockChainService.getShareInfo(any(GetShareInfoRequest.class))).thenAnswer(invocation -> {
            GetShareInfoRequest request = invocation.getArgument(0);
            assertThat(request.shareCode()).isEqualTo("share-code");
            assertThat(request.requester()).isEqualTo("viewer");
            assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<SharingVO> actual = fileRemoteClient.getShareInfo("share-code", "viewer");

        assertThat(actual).isSameAs(expected);
        assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                .isNull();
    }

    /**
     * 验证区块链健康信息调用同样会携带共享令牌。
     */
    @Test
    void getCurrentBlockChainMessage_shouldAttachRpcToken() {
        Result<BlockChainMessage> expected = Result.success(null);
        when(blockChainService.getCurrentBlockChainMessage()).thenAnswer(invocation -> {
            assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<BlockChainMessage> actual = fileRemoteClient.getCurrentBlockChainMessage();

        assertThat(actual).isSameAs(expected);
        assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                .isNull();
    }

    /**
     * 验证分享码列表降级方法签名与 Resilience4j 原方法参数保持一致。
     */
    @Test
    void getUserShareCodesFallback_shouldMatchCircuitBreakerSignature() throws Exception {
        Method fallback = FileRemoteClient.class.getDeclaredMethod(
                "getUserShareCodesFallback",
                String.class,
                String.class,
                Throwable.class
        );

        assertThat(fallback.getReturnType()).isEqualTo(Result.class);
        @SuppressWarnings("unchecked")
        Result<List<String>> result = (Result<List<String>>) ReflectionTestUtils.invokeMethod(
                fileRemoteClient,
                "getUserShareCodesFallback",
                "owner",
                "viewer",
                new RuntimeException("boom")
        );

        assertThat(result.getCode()).isEqualTo(ResultEnum.BLOCKCHAIN_ERROR.getCode());
        assertThat(result.getData()).isEmpty();
    }

    /**
     * 验证分享详情降级方法签名与 Resilience4j 原方法参数保持一致。
     */
    @Test
    void getShareInfoFallback_shouldMatchCircuitBreakerSignature() throws Exception {
        Method fallback = FileRemoteClient.class.getDeclaredMethod(
                "getShareInfoFallback",
                String.class,
                String.class,
                Throwable.class
        );

        assertThat(fallback.getReturnType()).isEqualTo(Result.class);
        @SuppressWarnings("unchecked")
        Result<SharingVO> result = (Result<SharingVO>) ReflectionTestUtils.invokeMethod(
                fileRemoteClient,
                "getShareInfoFallback",
                "share-code",
                "viewer",
                new RuntimeException("boom")
        );

        assertThat(result.getCode()).isEqualTo(ResultEnum.GET_USER_SHARE_FILE_ERROR.getCode());
        assertThat(result.getData()).isNull();
    }

    /**
     * 验证批次查询降级在空请求和完整请求下都返回显式区块链错误。
     */
    @Test
    void getAttestationBatchFallback_shouldHandleNullAndBoundRequest() {
        @SuppressWarnings("unchecked")
        Result<GetAttestationBatchResponse> nullRequest = (Result<GetAttestationBatchResponse>)
                ReflectionTestUtils.invokeMethod(
                        fileRemoteClient,
                        "getAttestationBatchFallback",
                        null,
                        new RuntimeException("boom"));
        @SuppressWarnings("unchecked")
        Result<GetAttestationBatchResponse> boundRequest = (Result<GetAttestationBatchResponse>)
                ReflectionTestUtils.invokeMethod(
                        fileRemoteClient,
                        "getAttestationBatchFallback",
                        new GetAttestationBatchRequest(7L, 900L, null),
                        new RuntimeException("boom"));

        assertThat(nullRequest.getCode()).isEqualTo(ResultEnum.BLOCKCHAIN_ERROR.getCode());
        assertThat(boundRequest.getCode()).isEqualTo(ResultEnum.BLOCKCHAIN_ERROR.getCode());
        assertThat(nullRequest.getData()).isNull();
        assertThat(boundRequest.getData()).isNull();
    }

    /**
     * 验证合约注册表查询降级返回空列表而非不确定的 null 数据。
     */
    @Test
    void getContractRegistryFallback_shouldReturnExplicitEmptyRegistry() {
        @SuppressWarnings("unchecked")
        Result<List<ContractRegistryEntryResponse>> result =
                (Result<List<ContractRegistryEntryResponse>>) ReflectionTestUtils.invokeMethod(
                        fileRemoteClient,
                        "getContractRegistryFallback",
                        new RuntimeException("boom"));

        assertThat(result.getCode()).isEqualTo(ResultEnum.BLOCKCHAIN_ERROR.getCode());
        assertThat(result.getData()).isEmpty();
    }
}
