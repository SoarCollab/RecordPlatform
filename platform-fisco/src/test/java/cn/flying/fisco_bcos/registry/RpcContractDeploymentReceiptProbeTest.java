package cn.flying.fisco_bcos.registry;

import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RpcContractDeploymentReceiptProbeTest {

    private static final String TRANSACTION_HASH = "0x" + "a".repeat(64);
    private static final String OTHER_TRANSACTION_HASH = "0x" + "b".repeat(64);
    private static final String CONTRACT_ADDRESS =
            "0x1111111111111111111111111111111111111111";
    private static final long BLOCK_NUMBER = 42L;

    @Mock
    private Client client;

    @Mock
    private BcosTransactionReceipt fiscoResponse;

    @Mock
    private org.fisco.bcos.sdk.v3.model.TransactionReceipt fiscoReceipt;

    @Mock
    private Web3j web3j;

    @Mock
    private Request<?, EthGetTransactionReceipt> besuRequest;

    @Mock
    private EthGetTransactionReceipt besuResponse;

    @Mock
    private org.web3j.protocol.core.methods.response.TransactionReceipt besuReceipt;

    private RpcContractDeploymentReceiptProbe probe;

    /**
     * 为两种链客户端建立完整的默认成功回执。
     */
    @BeforeEach
    void setUp() throws IOException {
        probe = new RpcContractDeploymentReceiptProbe();

        lenient().when(client.getTransactionReceipt(TRANSACTION_HASH, false))
                .thenReturn(fiscoResponse);
        lenient().when(fiscoResponse.getTransactionReceipt()).thenReturn(fiscoReceipt);
        lenient().when(fiscoReceipt.getStatus()).thenReturn(0);
        lenient().when(fiscoReceipt.getTransactionHash()).thenReturn(TRANSACTION_HASH);
        lenient().when(fiscoReceipt.getContractAddress()).thenReturn(CONTRACT_ADDRESS);
        lenient().when(fiscoReceipt.getBlockNumber())
                .thenReturn(BigInteger.valueOf(BLOCK_NUMBER));

        lenient().doReturn(besuRequest)
                .when(web3j).ethGetTransactionReceipt(TRANSACTION_HASH);
        lenient().when(besuRequest.send()).thenReturn(besuResponse);
        lenient().when(besuResponse.getTransactionReceipt())
                .thenReturn(Optional.of(besuReceipt));
        lenient().when(besuReceipt.getStatus()).thenReturn("0x1");
        lenient().when(besuReceipt.getTransactionHash()).thenReturn(TRANSACTION_HASH);
        lenient().when(besuReceipt.getContractAddress()).thenReturn(CONTRACT_ADDRESS);
        lenient().when(besuReceipt.getBlockNumberRaw()).thenReturn("0x2a");
    }

    /**
     * 验证 FISCO status=0 回执被规范化为稳定部署证据。
     */
    @Test
    void shouldInspectSuccessfulFiscoDeploymentReceipt() {
        ContractDeploymentReceiptProbe.DeploymentReceipt receipt =
                probe.inspectFisco(client, TRANSACTION_HASH.toUpperCase());

        assertThat(receipt.transactionHash()).isEqualTo(TRANSACTION_HASH);
        assertThat(receipt.contractAddress()).isEqualTo(CONTRACT_ADDRESS);
        assertThat(receipt.blockNumber()).isEqualTo(BLOCK_NUMBER);
        verify(client).getTransactionReceipt(TRANSACTION_HASH, false);
    }

    /**
     * 验证 FISCO 非零状态不会被当作成功部署。
     */
    @Test
    void shouldRejectFailedFiscoDeploymentReceipt() {
        when(fiscoReceipt.getStatus()).thenReturn(16);

        assertThatThrownBy(() -> probe.inspectFisco(client, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not successful")
                .hasMessageContaining("status=16");
    }

    /**
     * 验证 FISCO SDK 对缺失 status 使用的未知值不会被误判为成功。
     */
    @Test
    void shouldRejectFiscoDeploymentReceiptWithoutExplicitStatus() {
        when(fiscoReceipt.getStatus()).thenReturn(-1);

        assertThatThrownBy(() -> probe.inspectFisco(client, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not successful")
                .hasMessageContaining("status=-1");
    }

    /**
     * 验证 FISCO RPC 错误或空结果不能降级为缺省证据。
     */
    @Test
    void shouldRejectUnavailableFiscoDeploymentReceipt() {
        when(fiscoResponse.hasError()).thenReturn(true);

        assertThatThrownBy(() -> probe.inspectFisco(client, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt is unavailable");
    }

    /**
     * 验证 FISCO 回执中的交易哈希必须等于实际查询参数。
     */
    @Test
    void shouldRejectFiscoReceiptTransactionHashMismatch() {
        when(fiscoReceipt.getTransactionHash()).thenReturn(OTHER_TRANSACTION_HASH);

        assertThatThrownBy(() -> probe.inspectFisco(client, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction hash mismatch");
    }

    /**
     * 验证普通交易使用的零合约地址不能伪装成部署回执。
     */
    @Test
    void shouldRejectFiscoReceiptZeroContractAddress() {
        when(fiscoReceipt.getContractAddress())
                .thenReturn("0x0000000000000000000000000000000000000000");

        assertThatThrownBy(() -> probe.inspectFisco(client, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be zero");
    }

    /**
     * 验证无法写入公共 registry Long 字段的超大区块号被明确拒绝。
     */
    @Test
    void shouldRejectFiscoReceiptBlockOverflow() {
        when(fiscoReceipt.getBlockNumber())
                .thenReturn(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));

        assertThatThrownBy(() -> probe.inspectFisco(client, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("block number is invalid");
    }

    /**
     * 验证 Besu 显式 status=1 回执被规范化为稳定部署证据。
     */
    @Test
    void shouldInspectSuccessfulBesuDeploymentReceipt() {
        ContractDeploymentReceiptProbe.DeploymentReceipt receipt =
                probe.inspectBesu(web3j, TRANSACTION_HASH);

        assertThat(receipt.transactionHash()).isEqualTo(TRANSACTION_HASH);
        assertThat(receipt.contractAddress()).isEqualTo(CONTRACT_ADDRESS);
        assertThat(receipt.blockNumber()).isEqualTo(BLOCK_NUMBER);
        verify(web3j).ethGetTransactionReceipt(TRANSACTION_HASH);
    }

    /**
     * 验证 Web3j 兼容语义中的缺失 status 不会在本项目被视为成功。
     */
    @Test
    void shouldRejectBesuReceiptWithoutExplicitStatus() {
        when(besuReceipt.getStatus()).thenReturn(null);

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not explicitly successful")
                .hasMessageContaining("status=null");
    }

    /**
     * 验证 Besu status=0 的失败交易不会进入 registry。
     */
    @Test
    void shouldRejectFailedBesuDeploymentReceipt() {
        when(besuReceipt.getStatus()).thenReturn("0x0");

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not explicitly successful");
    }

    /**
     * 验证尚未出块或不存在的 Besu 回执严格失败关闭。
     */
    @Test
    void shouldRejectMissingBesuDeploymentReceipt() {
        when(besuResponse.getTransactionReceipt()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt is unavailable");
    }

    /**
     * 验证 Besu JSON-RPC error 响应不能被空的 receipt Optional 掩盖。
     */
    @Test
    void shouldRejectBesuDeploymentReceiptRpcError() {
        when(besuResponse.hasError()).thenReturn(true);

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt RPC failed");
    }

    /**
     * 验证 Besu 回执内部交易哈希必须等于实际查询参数。
     */
    @Test
    void shouldRejectBesuReceiptTransactionHashMismatch() {
        when(besuReceipt.getTransactionHash()).thenReturn(OTHER_TRANSACTION_HASH);

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction hash mismatch");
    }

    /**
     * 验证 Besu 普通交易的零合约地址不能作为部署来源。
     */
    @Test
    void shouldRejectBesuReceiptZeroContractAddress() {
        when(besuReceipt.getContractAddress())
                .thenReturn("0x0000000000000000000000000000000000000000");

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be zero");
    }

    /**
     * 验证 Besu 超大区块号不能在转换为公共 registry Long 时截断。
     */
    @Test
    void shouldRejectBesuReceiptBlockOverflow() {
        when(besuReceipt.getBlockNumberRaw()).thenReturn("0x8000000000000000");

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("block number is invalid");
    }

    /**
     * 验证 Besu 畸形区块数量不能通过解析异常绕过校验。
     */
    @Test
    void shouldRejectMalformedBesuReceiptBlockNumber() {
        when(besuReceipt.getBlockNumberRaw()).thenReturn("not-a-quantity");

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot verify Besu deployment receipt");
    }

    /**
     * 验证 Besu JSON-RPC I/O 失败以 fail-closed 异常传播。
     */
    @Test
    void shouldRejectBesuDeploymentReceiptRpcFailure() throws IOException {
        when(besuRequest.send()).thenThrow(new IOException("RPC unavailable"));

        assertThatThrownBy(() -> probe.inspectBesu(web3j, TRANSACTION_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot verify Besu deployment receipt");
    }
}
