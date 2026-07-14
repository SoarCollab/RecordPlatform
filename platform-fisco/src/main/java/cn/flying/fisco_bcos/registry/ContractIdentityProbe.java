package cn.flying.fisco_bcos.registry;

import org.fisco.bcos.sdk.v3.client.Client;
import org.web3j.protocol.Web3j;

/**
 * 通过只读合约调用解析链上合约自声明身份。
 */
public interface ContractIdentityProbe {

    /**
     * 调用 FISCO 合约的 contractIdentity 方法。
     *
     * @param client 当前链客户端
     * @param address 已校验的合约地址
     * @param abi 与 catalog 对应的签入 ABI
     * @return 链上合约名称与语义版本
     */
    ContractIdentity inspectFisco(Client client, String address, String abi);

    /**
     * 调用 Besu 合约的 contractIdentity 方法。
     *
     * @param web3j 当前链客户端
     * @param address 已校验的合约地址
     * @return 链上合约名称与语义版本
     */
    ContractIdentity inspectBesu(Web3j web3j, String address);

    /**
     * 表示链上合约返回的稳定名称与语义版本。
     *
     * @param contractName 合约名称
     * @param semanticVersion 语义版本
     */
    record ContractIdentity(String contractName, String semanticVersion) {
    }
}
