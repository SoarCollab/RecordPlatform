package cn.flying.fisco_bcos.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransaction;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.v3.client.protocol.response.TotalTransactionCount;
import org.fisco.bcos.sdk.v3.transaction.manager.AssembleTransactionProcessor;
import org.fisco.bcos.sdk.v3.transaction.manager.TransactionProcessorFactory;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import cn.flying.fisco_bcos.constants.ContractConstants;
import cn.flying.fisco_bcos.model.bo.SharingDeleteFilesInputBO;
import cn.flying.fisco_bcos.model.bo.SharingGetFileInputBO;
import cn.flying.fisco_bcos.model.bo.SharingGetSharedFilesInputBO;
import cn.flying.fisco_bcos.model.bo.SharingGetUserFilesInputBO;
import cn.flying.fisco_bcos.model.bo.SharingShareFilesInputBO;
import cn.flying.fisco_bcos.model.bo.SharingStoreFileInputBO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * FISCO BCOS Sharing 合约服务
 * 封装与 Sharing 智能合约的交互逻辑
 *
 * <p>激活条件: 当 {@code Client} bean 存在时 (local-fisco 或 bsn-fisco 模式)
 * <p>当使用 BSN Besu 时，此服务不会被创建，由 BsnBesuAdapter 直接通过 Web3j 调用合约
 */
@Service
@ConditionalOnBean(Client.class)
@NoArgsConstructor
@Data
public class SharingService {
  @Value("${contract.sharingAddress}")
  private String address;

  @Resource
  private Client client;

  AssembleTransactionProcessor txProcessor;

  @PostConstruct
  public void init() {
    this.txProcessor = TransactionProcessorFactory.createAssembleTransactionProcessor(this.client, this.client.getCryptoSuite().getCryptoKeyPair());
  }

  public CallResponse getFile(SharingGetFileInputBO input) throws Exception {
    return this.txProcessor.sendCall(this.client.getCryptoSuite().getCryptoKeyPair().getAddress(), this.address, ContractConstants.SharingAbi, "getFile", input.toArgs());
  }

  public TransactionResponse shareFiles(SharingShareFilesInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.SharingAbi, "shareFiles", input.toArgs());
  }

  /**
   * 查询分享文件信息
   * 使用 sendCall 实现只读查询，不消耗 Gas
   *
   * @deprecated 请使用 {@link #getSharedFilesReadOnly(SharingGetSharedFilesInputBO)} 替代
   * @param input 分享码输入
   * @return 交易响应（实际为只读调用结果）
   */
  @Deprecated(forRemoval = true)
  public TransactionResponse getSharedFiles(SharingGetSharedFilesInputBO input) throws Exception {
    // 注意：此方法保留仅为兼容性考虑，内部已改为只读调用
    // 返回类型保持 TransactionResponse 但实际不产生交易
    throw new UnsupportedOperationException("请使用 getSharedFilesReadOnly 方法");
  }

  /**
   * 查询分享文件信息 (只读操作)
   * 使用 sendCall 而非 sendTransaction，不消耗 Gas，适用于查询场景
   *
   * @param input 分享码输入
   * @return 调用响应
   */
  public CallResponse getSharedFilesReadOnly(SharingGetSharedFilesInputBO input) throws Exception {
    return this.txProcessor.sendCall(
        this.client.getCryptoSuite().getCryptoKeyPair().getAddress(),
        this.address,
        ContractConstants.SharingAbi,
        "getSharedFiles",
        input.toArgs()
    );
  }

  public TransactionResponse deleteFiles(SharingDeleteFilesInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.SharingAbi, "deleteFiles", input.toArgs());
  }

  public CallResponse getUserFiles(SharingGetUserFilesInputBO input) throws Exception {
    return this.txProcessor.sendCall(this.client.getCryptoSuite().getCryptoKeyPair().getAddress(), this.address, ContractConstants.SharingAbi, "getUserFiles", input.toArgs());
  }

  public TransactionResponse storeFile(SharingStoreFileInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.SharingAbi, "storeFile", input.toArgs());
  }

  public TotalTransactionCount getCurrentBlockChainMessage() {
    return this.client.getTotalTransactionCount();
  }
  
  public BcosTransaction getTransactionByHash(String transactionHash) {
    return this.client.getTransaction(transactionHash, false);
  }
  
  public BcosTransactionReceipt getTransactionReceipt(String transactionHash) {
    return this.client.getTransactionReceipt(transactionHash, false);
  }
}
