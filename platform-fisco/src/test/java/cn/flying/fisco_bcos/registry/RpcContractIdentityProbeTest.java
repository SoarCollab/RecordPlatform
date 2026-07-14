package cn.flying.fisco_bcos.registry;

import cn.flying.fisco_bcos.constants.ContractConstants;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.Call;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;
import org.fisco.bcos.sdk.v3.model.CryptoType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RpcContractIdentityProbeTest {

    private static final String ADDRESS =
            "0x1111111111111111111111111111111111111111";

    @Mock
    private Client client;

    @Mock
    private Call fiscoResponse;

    @Mock
    private Call.CallOutput fiscoOutput;

    @Mock
    private Web3j web3j;

    @Mock
    private Request<?, EthCall> ethCallRequest;

    @Mock
    private EthCall ethCallResponse;

    private CryptoSuite cryptoSuite;
    private RpcContractIdentityProbe probe;

    /**
     * 创建真实 FISCO ABI codec 上下文和默认成功响应。
     */
    @BeforeEach
    void setUp() throws IOException {
        cryptoSuite = new CryptoSuite(CryptoType.ECDSA_TYPE);
        probe = new RpcContractIdentityProbe();
        lenient().when(client.getCryptoSuite()).thenReturn(cryptoSuite);
        lenient().when(client.isWASM()).thenReturn(false);
        lenient().when(client.call(
                        any(org.fisco.bcos.sdk.v3.client.protocol.request.Transaction.class)))
                .thenReturn(fiscoResponse);
        lenient().when(fiscoResponse.getCallResult()).thenReturn(fiscoOutput);
        lenient().when(fiscoOutput.getStatus()).thenReturn(0);
        lenient().when(fiscoOutput.getOutput())
                .thenReturn(encodedIdentity("Sharing", "2.0.0"));

        lenient().doReturn(ethCallRequest).when(web3j).ethCall(
                any(org.web3j.protocol.core.methods.request.Transaction.class),
                any(DefaultBlockParameter.class));
        lenient().when(ethCallRequest.send()).thenReturn(ethCallResponse);
        lenient().when(ethCallResponse.getValue())
                .thenReturn(encodedIdentity("Sharing", "2.0.0"));
    }

    /**
     * 释放测试创建的 FISCO native codec 资源。
     */
    @AfterEach
    void tearDown() {
        cryptoSuite.destroy();
    }

    /**
     * 验证 FISCO 调用编码、目标地址和双字符串返回值解析。
     */
    @Test
    void shouldInspectFiscoContractIdentity() {
        ContractIdentityProbe.ContractIdentity identity = probe.inspectFisco(
                client,
                ADDRESS,
                ContractConstants.SharingAbi);

        assertThat(identity.contractName()).isEqualTo("Sharing");
        assertThat(identity.semanticVersion()).isEqualTo("2.0.0");
        ArgumentCaptor<org.fisco.bcos.sdk.v3.client.protocol.request.Transaction> captor =
                ArgumentCaptor.forClass(
                        org.fisco.bcos.sdk.v3.client.protocol.request.Transaction.class);
        verify(client).call(captor.capture());
        assertThat(captor.getValue().getTo()).isEqualTo(ADDRESS);
        assertThat(captor.getValue().getData()).isNotEmpty();
    }

    /**
     * 验证 FISCO 非零执行状态不会被当作有效身份。
     */
    @Test
    void shouldRejectFailedFiscoIdentityCall() {
        when(fiscoOutput.getStatus()).thenReturn(1);

        assertThatThrownBy(() -> probe.inspectFisco(
                client,
                ADDRESS,
                ContractConstants.SharingAbi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity call failed");
    }

    /**
     * 验证 FISCO 返回空名称时严格拒绝畸形身份。
     */
    @Test
    void shouldRejectMalformedFiscoIdentity() {
        when(fiscoOutput.getOutput()).thenReturn(encodedIdentity("", "2.0.0"));

        assertThatThrownBy(() -> probe.inspectFisco(
                client,
                ADDRESS,
                ContractConstants.SharingAbi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed contract identity response");
    }

    /**
     * 验证 Besu eth_call 返回值使用同一身份合同解析。
     */
    @Test
    void shouldInspectBesuContractIdentity() {
        ContractIdentityProbe.ContractIdentity identity = probe.inspectBesu(web3j, ADDRESS);

        assertThat(identity.contractName()).isEqualTo("Sharing");
        assertThat(identity.semanticVersion()).isEqualTo("2.0.0");
        verify(web3j).ethCall(
                any(org.web3j.protocol.core.methods.request.Transaction.class),
                eq(org.web3j.protocol.core.DefaultBlockParameterName.LATEST));
    }

    /**
     * 验证 Besu revert 响应不会被解码为有效身份。
     */
    @Test
    void shouldRejectRevertedBesuIdentityCall() {
        when(ethCallResponse.isReverted()).thenReturn(true);

        assertThatThrownBy(() -> probe.inspectBesu(web3j, ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity call failed");
    }

    /**
     * 验证 Besu JSON-RPC 错误响应不会进入 ABI 解码。
     */
    @Test
    void shouldRejectBesuIdentityJsonRpcError() {
        when(ethCallResponse.hasError()).thenReturn(true);

        assertThatThrownBy(() -> probe.inspectBesu(web3j, ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity call failed");
    }

    /**
     * 验证 Besu 空返回数据不会被当作有效身份。
     */
    @Test
    void shouldRejectEmptyBesuIdentityOutput() {
        when(ethCallResponse.getValue()).thenReturn("0x");

        assertThatThrownBy(() -> probe.inspectBesu(web3j, ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity call failed");
    }

    /**
     * 验证 Besu 畸形 ABI 返回数据必须失败关闭。
     */
    @Test
    void shouldRejectMalformedBesuIdentityOutput() {
        when(ethCallResponse.getValue()).thenReturn("0x1234");

        assertThatThrownBy(() -> probe.inspectBesu(web3j, ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Besu contract identity");
    }

    /**
     * 验证 Besu RPC I/O 错误以 fail-closed 方式传播。
     */
    @Test
    void shouldRejectBesuIdentityRpcError() throws IOException {
        when(ethCallRequest.send()).thenThrow(new IOException("RPC unavailable"));

        assertThatThrownBy(() -> probe.inspectBesu(web3j, ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot call Besu contract identity");
    }

    /**
     * 使用标准 EVM ABI 编码双字符串返回值。
     */
    private String encodedIdentity(String contractName, String semanticVersion) {
        return FunctionEncoder.encodeConstructor(List.of(
                new Utf8String(contractName),
                new Utf8String(semanticVersion)));
    }
}
