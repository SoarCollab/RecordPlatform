package cn.flying.fisco_bcos.registry;

import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.Call;
import org.fisco.bcos.sdk.v3.codec.ContractCodec;
import org.fisco.bcos.sdk.v3.codec.ContractCodecException;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.IOException;
import java.util.List;

/**
 * 使用 FISCO SDK 或 Web3j 执行无状态 contractIdentity 只读调用。
 */
@Component
public class RpcContractIdentityProbe implements ContractIdentityProbe {

    private static final String IDENTITY_FUNCTION = "contractIdentity";
    private static final String ZERO_ADDRESS =
            "0x0000000000000000000000000000000000000000";

    /**
     * 编码、执行并严格解析 FISCO contractIdentity 调用。
     */
    @Override
    public ContractIdentity inspectFisco(Client client, String address, String abi) {
        if (client == null || address == null || abi == null) {
            throw new IllegalArgumentException("FISCO identity probe inputs must not be null");
        }
        CryptoSuite cryptoSuite = client.getCryptoSuite();
        Boolean wasm = client.isWASM();
        if (cryptoSuite == null || cryptoSuite.getHashImpl() == null || wasm == null) {
            throw new IllegalStateException("FISCO codec context is unavailable");
        }
        try {
            ContractCodec codec = new ContractCodec(cryptoSuite.getHashImpl(), wasm);
            byte[] encoded = codec.encodeMethod(abi, IDENTITY_FUNCTION, List.of());
            Call response = client.call(new org.fisco.bcos.sdk.v3.client.protocol.request.Transaction(
                    ZERO_ADDRESS,
                    address,
                    encoded));
            Call.CallOutput output = requireSuccessfulFiscoResponse(response, address);
            List<Object> values = codec.decodeMethodOutputAndGetObject(
                            abi,
                            IDENTITY_FUNCTION,
                            output.getOutput())
                    .getLeft();
            return requireIdentityValues(values, address);
        } catch (ContractCodecException e) {
            throw new IllegalStateException(
                    "Cannot encode or decode FISCO contract identity at " + address,
                    e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(
                    "Cannot call FISCO contract identity at " + address,
                    e);
        }
    }

    /**
     * 编码、执行并严格解析 Besu contractIdentity 调用。
     */
    @Override
    public ContractIdentity inspectBesu(Web3j web3j, String address) {
        if (web3j == null || address == null) {
            throw new IllegalArgumentException("Besu identity probe inputs must not be null");
        }
        Function function = identityFunction();
        org.web3j.protocol.core.methods.request.Transaction transaction =
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                        ZERO_ADDRESS,
                        address,
                        FunctionEncoder.encode(function));
        try {
            EthCall response = web3j.ethCall(
                            transaction,
                            DefaultBlockParameterName.LATEST)
                    .send();
            String output = requireSuccessfulBesuResponse(response, address);
            List<Type> values = FunctionReturnDecoder.decode(
                    output,
                    function.getOutputParameters());
            if (values.size() != 2
                    || !(values.get(0) instanceof Utf8String contractName)
                    || !(values.get(1) instanceof Utf8String semanticVersion)) {
                throw new IllegalStateException(
                        "Malformed Besu contract identity response at " + address);
            }
            return requireIdentityValues(
                    List.of(contractName.getValue(), semanticVersion.getValue()),
                    address);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot call Besu contract identity at " + address,
                    e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(
                    "Cannot decode Besu contract identity at " + address,
                    e);
        }
    }

    /**
     * 构造无输入、双字符串输出的 Web3j ABI 函数描述。
     */
    private Function identityFunction() {
        return new Function(
                IDENTITY_FUNCTION,
                List.of(),
                List.of(
                        new TypeReference<Utf8String>() {
                        },
                        new TypeReference<Utf8String>() {
                        }));
    }

    /**
     * 校验 FISCO RPC、执行状态和返回数据均可用于解码。
     */
    private Call.CallOutput requireSuccessfulFiscoResponse(Call response, String address) {
        if (response == null || response.hasError() || response.getCallResult() == null) {
            throw new IllegalStateException(
                    "FISCO contract identity RPC failed at " + address);
        }
        Call.CallOutput output = response.getCallResult();
        if (output.getStatus() != 0 || isEmptyOutput(output.getOutput())) {
            throw new IllegalStateException(
                    "FISCO contract identity call failed at " + address
                            + ": status=" + output.getStatus());
        }
        return output;
    }

    /**
     * 校验 Besu RPC、revert 状态和返回数据均可用于解码。
     */
    private String requireSuccessfulBesuResponse(EthCall response, String address) {
        if (response == null
                || response.hasError()
                || response.isReverted()
                || isEmptyOutput(response.getValue())) {
            throw new IllegalStateException(
                    "Besu contract identity call failed at " + address);
        }
        return response.getValue();
    }

    /**
     * 校验返回值必须恰好包含两个非空字符串。
     */
    private ContractIdentity requireIdentityValues(List<?> values, String address) {
        if (values == null
                || values.size() != 2
                || !(values.get(0) instanceof String contractName)
                || !(values.get(1) instanceof String semanticVersion)
                || contractName.isBlank()
                || semanticVersion.isBlank()) {
            throw new IllegalStateException(
                    "Malformed contract identity response at " + address);
        }
        return new ContractIdentity(contractName, semanticVersion);
    }

    /**
     * 判断 RPC 输出是否为空或仅含 EVM 空十六进制前缀。
     */
    private boolean isEmptyOutput(String output) {
        if (output == null) {
            return true;
        }
        String compact = output.replaceAll("\\s+", "");
        return compact.isEmpty()
                || "0x".equalsIgnoreCase(compact)
                || "0x0".equalsIgnoreCase(compact);
    }
}
