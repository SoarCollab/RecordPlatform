package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.adapter.model.ChainException;
import cn.flying.fisco_bcos.config.BsnBesuConfig;
import cn.flying.fisco_bcos.registry.ContractRegistryService;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.gas.StaticGasProvider;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BsnBesuAdapterTest {

    private static final String SHARING_ADDRESS =
            "0x3333333333333333333333333333333333333333";
    private static final String FILE_HASH = "0x" + "4".repeat(64);
    private static final String MERKLE_ROOT = "0x" + "5".repeat(64);
    private static final Credentials CREDENTIALS = Credentials.create("3".repeat(64));

    @Mock
    private Web3j web3j;

    @Mock
    private ContractRegistryService contractRegistryService;

    @Mock
    private ContractRegistryEntryResponse registryEntry;

    @Mock
    private Request<?, EthGetTransactionCount> nonceRequest;

    private final AtomicReference<BigInteger> nodePending =
            new AtomicReference<>(BigInteger.ZERO);
    private final List<String> capturedRawTransactions =
            Collections.synchronizedList(new ArrayList<>());

    private BsnBesuAdapter adapter;

    /**
     * 构造使用真实签名和内存 durable store 的 Besu adapter 测试实例。
     */
    @BeforeEach
    void setUp() throws Exception {
        when(contractRegistryService.getActiveEntry("Sharing")).thenReturn(registryEntry);
        when(registryEntry.contractAddress()).thenReturn(SHARING_ADDRESS);
        doReturn(nonceRequest).when(web3j).ethGetTransactionCount(
                eq(CREDENTIALS.getAddress()),
                eq(DefaultBlockParameterName.PENDING)
        );
        when(nonceRequest.send()).thenAnswer(invocation -> nonceResponse(nodePending.get()));
        lenient().doAnswer(invocation -> {
            String raw = invocation.getArgument(0);
            capturedRawTransactions.add(raw);
            return requestReturning(accepted(raw));
        }).when(web3j).ethSendRawTransaction(anyString());
        lenient().doAnswer(invocation -> requestReturning(successfulReceiptResponse()))
                .when(web3j).ethGetTransactionReceipt(anyString());

        BsnBesuConfig config = new BsnBesuConfig();
        config.setChainId(1337L);
        BsnBesuNonceCoordinator coordinator =
                new BsnBesuNonceCoordinator(new InMemoryStateStore());
        adapter = new BsnBesuAdapter(
                web3j,
                CREDENTIALS,
                new StaticGasProvider(BigInteger.ONE, BigInteger.valueOf(4_500_000L)),
                config,
                contractRegistryService,
                coordinator
        );
        adapter.init();
    }

    /**
     * 验证 batch 与普通文件写并发时获得唯一连续 nonce 和不同 payload。
     */
    @Test
    void shouldCoordinateConcurrentBatchAndFileWrites() throws Exception {
        nodePending.set(BigInteger.valueOf(50));

        runConcurrently(
                () -> adapter.storeAttestationBatch(
                        1L,
                        101L,
                        "BATCH-101",
                        "SHA-256-MERKLE-V1",
                        MERKLE_ROOT,
                        2
                ),
                () -> adapter.storeFile("uploader", "file.txt", "[]", "{}")
        );

        assertUniqueContinuousNonces(BigInteger.valueOf(50), 2);
        assertThat(decodedTransactions())
                .extracting(RawTransaction::getData)
                .doesNotHaveDuplicates();
        verifyPendingNonceOnly();
    }

    /**
     * 验证两个 batch 写并发时不会为不同业务 payload 分配相同 nonce。
     */
    @Test
    void shouldCoordinateTwoConcurrentBatchWrites() throws Exception {
        nodePending.set(BigInteger.valueOf(60));

        runConcurrently(
                () -> adapter.storeAttestationBatch(
                        2L,
                        201L,
                        "BATCH-201",
                        "SHA-256-MERKLE-V1",
                        "0x" + "6".repeat(64),
                        3
                ),
                () -> adapter.storeAttestationBatch(
                        2L,
                        202L,
                        "BATCH-202",
                        "SHA-256-MERKLE-V1",
                        "0x" + "7".repeat(64),
                        4
                )
        );

        assertUniqueContinuousNonces(BigInteger.valueOf(60), 2);
        assertThat(decodedTransactions())
                .extracting(RawTransaction::getData)
                .doesNotHaveDuplicates();
    }

    /**
     * 验证广播异常后的 public 写路径不会复用可能已提交的 nonce。
     */
    @Test
    void shouldNotReuseNonceAfterUnknownBroadcast() throws Exception {
        nodePending.set(BigInteger.valueOf(70));
        AtomicInteger broadcastCount = new AtomicInteger();
        doAnswer(invocation -> {
            String raw = invocation.getArgument(0);
            capturedRawTransactions.add(raw);
            if (broadcastCount.getAndIncrement() == 0) {
                return requestThrowing(new IOException("connection reset"));
            }
            return requestReturning(accepted(raw));
        }).when(web3j).ethSendRawTransaction(anyString());

        assertThatThrownBy(() -> adapter.storeFile("uploader", "first", "[]", "{}"))
                .isInstanceOf(ChainException.class)
                .hasMessageContaining("connection reset");

        nodePending.set(BigInteger.valueOf(71));
        adapter.storeFile("uploader", "second", "[]", "{}");

        assertThat(decodedTransactions())
                .extracting(RawTransaction::getNonce)
                .containsExactly(BigInteger.valueOf(70), BigInteger.valueOf(71));
    }

    /**
     * 验证明确 JSON-RPC 拒绝后重新同步可安全复用未广播 nonce。
     */
    @Test
    void shouldResyncAfterDefiniteReject() throws Exception {
        nodePending.set(BigInteger.valueOf(80));
        AtomicInteger broadcastCount = new AtomicInteger();
        doAnswer(invocation -> {
            String raw = invocation.getArgument(0);
            capturedRawTransactions.add(raw);
            if (broadcastCount.getAndIncrement() == 0) {
                EthSendTransaction rejected = new EthSendTransaction();
                rejected.setError(new Response.Error(-32602, "invalid params"));
                return requestReturning(rejected);
            }
            return requestReturning(accepted(raw));
        }).when(web3j).ethSendRawTransaction(anyString());

        assertThatThrownBy(() -> adapter.storeFile("uploader", "first", "[]", "{}"))
                .isInstanceOf(ChainException.class)
                .hasMessageContaining("invalid params");
        adapter.storeFile("uploader", "second", "[]", "{}");

        assertThat(decodedTransactions())
                .extracting(RawTransaction::getNonce)
                .containsExactly(BigInteger.valueOf(80), BigInteger.valueOf(80));
        assertThat(decodedTransactions())
                .extracting(RawTransaction::getData)
                .doesNotHaveDuplicates();
    }

    /**
     * 验证 receipt 等待不占用 signer nonce 临界区。
     */
    @Test
    void shouldNotHoldSignerLockWhileWaitingForReceipt() throws Exception {
        nodePending.set(BigInteger.valueOf(90));
        CountDownLatch firstReceiptEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstReceipt = new CountDownLatch(1);
        CountDownLatch secondBroadcasted = new CountDownLatch(1);
        AtomicReference<String> firstTransactionHash = new AtomicReference<>();
        AtomicInteger broadcastCount = new AtomicInteger();

        doAnswer(invocation -> {
            String raw = invocation.getArgument(0);
            capturedRawTransactions.add(raw);
            String transactionHash = Hash.sha3(raw);
            if (broadcastCount.getAndIncrement() == 0) {
                firstTransactionHash.set(transactionHash);
            } else {
                secondBroadcasted.countDown();
            }
            return requestReturning(responseWithHash(transactionHash));
        }).when(web3j).ethSendRawTransaction(anyString());
        doAnswer(invocation -> {
            String transactionHash = invocation.getArgument(0);
            if (transactionHash.equals(firstTransactionHash.get())) {
                return receiptRequestWaiting(firstReceiptEntered, releaseFirstReceipt);
            }
            return requestReturning(successfulReceiptResponse());
        }).when(web3j).ethGetTransactionReceipt(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(
                    () -> adapter.storeFile("uploader", "first", "[]", "{}")
            );
            assertThat(firstReceiptEntered.await(3, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(
                    () -> adapter.storeFile("uploader", "second", "[]", "{}")
            );

            assertThat(secondBroadcasted.await(3, TimeUnit.SECONDS)).isTrue();
            releaseFirstReceipt.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstReceipt.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 验证五类 Besu 写入口全部共享同一 PENDING nonce 协调发送缝。
     */
    @Test
    void shouldRouteAllWriteOperationsThroughOneCoordinator() {
        nodePending.set(BigInteger.valueOf(100));

        adapter.storeFile("uploader", "file", "[]", "{}");
        adapter.storeAttestationBatch(
                3L,
                301L,
                "BATCH-301",
                "SHA-256-MERKLE-V1",
                MERKLE_ROOT,
                1
        );
        adapter.deleteFiles("uploader", List.of(FILE_HASH));
        adapter.shareFiles("uploader", List.of(FILE_HASH), 30);
        adapter.cancelShare("share-code", "uploader");

        assertUniqueContinuousNonces(BigInteger.valueOf(100), 5);
        assertThat(decodedTransactions())
                .extracting(RawTransaction::getData)
                .doesNotHaveDuplicates();
        verifyPendingNonceOnly();
    }

    /**
     * 同步启动两个写调用，并在有界时间内等待完成。
     */
    private void runConcurrently(Runnable firstAction, Runnable secondAction) throws Exception {
        CyclicBarrier startBarrier = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> runAfterBarrier(startBarrier, firstAction));
            Future<?> second = executor.submit(() -> runAfterBarrier(startBarrier, secondAction));
            startBarrier.await(3, TimeUnit.SECONDS);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 等待并发起点 barrier 后执行写操作。
     */
    private void runAfterBarrier(CyclicBarrier barrier, Runnable action) {
        try {
            barrier.await(3, TimeUnit.SECONDS);
            action.run();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 断言捕获的签名交易从指定 nonce 开始唯一连续。
     */
    private void assertUniqueContinuousNonces(BigInteger firstNonce, int count) {
        List<BigInteger> expected = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            expected.add(firstNonce.add(BigInteger.valueOf(index)));
        }
        assertThat(decodedTransactions())
                .extracting(RawTransaction::getNonce)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * 解码全部已捕获的真实签名 raw transaction。
     */
    private List<RawTransaction> decodedTransactions() {
        synchronized (capturedRawTransactions) {
            return capturedRawTransactions.stream()
                    .map(TransactionDecoder::decode)
                    .toList();
        }
    }

    /**
     * 验证写发送只查询 PENDING nonce，不回退到 LATEST。
     */
    private void verifyPendingNonceOnly() {
        verify(web3j, atLeastOnce()).ethGetTransactionCount(
                CREDENTIALS.getAddress(),
                DefaultBlockParameterName.PENDING
        );
        verify(web3j, never()).ethGetTransactionCount(
                CREDENTIALS.getAddress(),
                DefaultBlockParameterName.LATEST
        );
    }

    /**
     * 创建指定节点 PENDING nonce 的 Web3j 响应。
     */
    private static EthGetTransactionCount nonceResponse(BigInteger nonce) {
        EthGetTransactionCount response = new EthGetTransactionCount();
        response.setResult(Numeric.encodeQuantity(nonce));
        return response;
    }

    /**
     * 创建与签名 payload 本地哈希一致的成功广播响应。
     */
    private static EthSendTransaction accepted(String signedRawTransaction) {
        return responseWithHash(Hash.sha3(signedRawTransaction));
    }

    /**
     * 创建指定交易哈希的成功广播响应。
     */
    private static EthSendTransaction responseWithHash(String transactionHash) {
        EthSendTransaction response = new EthSendTransaction();
        response.setResult(transactionHash);
        return response;
    }

    /**
     * 创建 status=1 的确定成功交易回执响应。
     */
    private static EthGetTransactionReceipt successfulReceiptResponse() {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1");
        receipt.setBlockNumber("0x1");
        receipt.setGasUsed("0x5208");
        receipt.setLogs(List.of());
        EthGetTransactionReceipt response = new EthGetTransactionReceipt();
        response.setResult(receipt);
        return response;
    }

    /**
     * 创建在首笔 receipt 上受 latch 控制的 Web3j Request。
     */
    @SuppressWarnings("unchecked")
    private static Request<?, EthGetTransactionReceipt> receiptRequestWaiting(
            CountDownLatch entered,
            CountDownLatch release
    ) throws IOException {
        Request<?, EthGetTransactionReceipt> request = mock(Request.class);
        when(request.send()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) {
                throw new IOException("receipt wait timed out");
            }
            return successfulReceiptResponse();
        });
        return request;
    }

    /**
     * 创建返回固定响应的 Web3j Request。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Response<?>> Request<?, T> requestReturning(T response)
            throws IOException {
        Request<?, T> request = mock(Request.class);
        doReturn(response).when(request).send();
        return request;
    }

    /**
     * 创建在同步 send 阶段抛出指定异常的 Web3j Request。
     */
    @SuppressWarnings("unchecked")
    private static Request<?, EthSendTransaction> requestThrowing(IOException exception)
            throws IOException {
        Request<?, EthSendTransaction> request = mock(Request.class);
        when(request.send()).thenThrow(exception);
        return request;
    }

    /**
     * 为 adapter 测试提供按 signer 隔离的线程安全 durable store。
     */
    private static final class InMemoryStateStore implements BsnBesuNonceStateStore {
        private final Map<String, PersistedState> states = new ConcurrentHashMap<>();

        /**
         * 读取规范化 signer 状态。
         */
        @Override
        public PersistedState load(String canonicalSigner) {
            return states.get(canonicalSigner.toLowerCase(Locale.ROOT));
        }

        /**
         * 保存规范化 signer 状态。
         */
        @Override
        public void save(String canonicalSigner, PersistedState state) {
            states.put(canonicalSigner.toLowerCase(Locale.ROOT), state);
        }
    }
}
