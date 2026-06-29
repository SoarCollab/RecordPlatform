package cn.flying.service.remote;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.request.GetShareInfoRequest;
import cn.flying.platformapi.request.GetUserShareCodesRequest;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.BlockChainMessage;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.security.BlockChainRpcAuth;
import cn.flying.service.attestation.AttestationBatchRootPayload;
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
     * 验证批量 Merkle 根上链兼容方法复用 storeFile RPC 边界并携带共享令牌。
     */
    @Test
    void storeAttestationBatchRoot_shouldWrapPayloadAndAttachRpcToken() {
        AttestationBatchRootPayload payload = AttestationBatchRootPayload.of(
                7L,
                900L,
                "MB-900",
                "SHA-256-MERKLE-V1",
                "root-hash",
                2
        );
        Result<StoreFileResponse> expected = Result.success(new StoreFileResponse("tx-root", "chain-root"));

        when(blockChainService.storeFile(any(StoreFileRequest.class))).thenAnswer(invocation -> {
            assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                    .isEqualTo(RPC_TOKEN);
            return expected;
        });

        Result<StoreFileResponse> actual = fileRemoteClient.storeAttestationBatchRoot(payload);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<StoreFileRequest> requestCaptor = ArgumentCaptor.forClass(StoreFileRequest.class);
        org.mockito.Mockito.verify(blockChainService).storeFile(requestCaptor.capture());
        assertThat(requestCaptor.getValue().uploader()).isEqualTo("tenant:7");
        assertThat(requestCaptor.getValue().fileName()).isEqualTo("MERKLE_ATTESTATION_BATCH_ROOT:MB-900");
        assertThat(requestCaptor.getValue().param()).contains("\"merkleRoot\":\"root-hash\"");
        assertThat(requestCaptor.getValue().content()).isEqualTo("root-hash");
        assertThat(RpcContext.getClientAttachment().getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY))
                .isNull();
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
}
