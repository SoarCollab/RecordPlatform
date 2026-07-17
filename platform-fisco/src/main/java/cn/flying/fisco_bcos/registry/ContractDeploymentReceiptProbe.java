package cn.flying.fisco_bcos.registry;

import org.fisco.bcos.sdk.v3.client.Client;
import org.web3j.protocol.Web3j;

/**
 * 从活动链读取并验证合约部署交易回执。
 */
public interface ContractDeploymentReceiptProbe {

    /**
     * 读取 FISCO 部署回执，并仅在状态和核心字段完整有效时返回。
     *
     * @param client 已绑定目标 chain/group 的 FISCO 客户端
     * @param transactionHash 配置中的部署交易哈希
     * @return 规范化后的成功部署回执
     */
    DeploymentReceipt inspectFisco(Client client, String transactionHash);

    /**
     * 读取 Besu 部署回执，并仅在状态和核心字段完整有效时返回。
     *
     * @param web3j 已绑定目标 chain 的 Web3j 客户端
     * @param transactionHash 配置中的部署交易哈希
     * @return 规范化后的成功部署回执
     */
    DeploymentReceipt inspectBesu(Web3j web3j, String transactionHash);

    /**
     * 已确认成功且字段规范化的合约部署回执。
     */
    record DeploymentReceipt(
            String transactionHash,
            String contractAddress,
            long blockNumber
    ) {
    }
}
