package cn.flying.fisco_bcos.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.transaction.manager.AssembleTransactionProcessor;
import org.fisco.bcos.sdk.v3.transaction.manager.TransactionProcessorFactory;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import cn.flying.fisco_bcos.constants.ContractConstants;
import cn.flying.fisco_bcos.model.bo.StorageDeleteFileInputBO;
import cn.flying.fisco_bcos.model.bo.StorageDeleteFilesInputBO;
import cn.flying.fisco_bcos.model.bo.StorageGetFileInputBO;
import cn.flying.fisco_bcos.model.bo.StorageGetUserFilesInputBO;
import cn.flying.fisco_bcos.model.bo.StorageStoreFileInputBO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * FISCO BCOS Storage 合约服务
 * 封装与 Storage 智能合约的交互逻辑
 *
 * <p>激活条件: 当 {@code Client} bean 存在时 (local-fisco 或 bsn-fisco 模式)
 * <p>当使用 BSN Besu 时，此服务不会被创建，由 BsnBesuAdapter 直接通过 Web3j 调用合约
 */
@Service
@ConditionalOnBean(Client.class)
@NoArgsConstructor
@Data
public class StorageService {
  @Value("${contract.storageAddress}")
  private String address;

  @Resource
  private Client client;

  AssembleTransactionProcessor txProcessor;

  @PostConstruct
  public void init() {
    this.txProcessor = TransactionProcessorFactory.createAssembleTransactionProcessor(this.client, this.client.getCryptoSuite().getCryptoKeyPair());
  }

  public TransactionResponse deleteFile(StorageDeleteFileInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.StorageAbi, "deleteFile", input.toArgs());
  }

  public CallResponse getFile(StorageGetFileInputBO input) throws Exception {
    return this.txProcessor.sendCall(this.client.getCryptoSuite().getCryptoKeyPair().getAddress(), this.address, ContractConstants.StorageAbi, "getFile", input.toArgs());
  }

  public TransactionResponse deleteFiles(StorageDeleteFilesInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.StorageAbi, "deleteFiles", input.toArgs());
  }

  public CallResponse getUserFiles(StorageGetUserFilesInputBO input) throws Exception {
    return this.txProcessor.sendCall(this.client.getCryptoSuite().getCryptoKeyPair().getAddress(), this.address, ContractConstants.StorageAbi, "getUserFiles", input.toArgs());
  }

  public TransactionResponse storeFile(StorageStoreFileInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.StorageAbi, "storeFile", input.toArgs());
  }
}
