package org.fisco.fisco_bcos.service;

import java.lang.Exception;
import java.lang.String;

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
import org.fisco.fisco_bcos.constants.ContractConstants;
import org.fisco.fisco_bcos.model.bo.SharingDeleteFileInputBO;
import org.fisco.fisco_bcos.model.bo.SharingDeleteFilesInputBO;
import org.fisco.fisco_bcos.model.bo.SharingGetFileInputBO;
import org.fisco.fisco_bcos.model.bo.SharingGetSharedFilesInputBO;
import org.fisco.fisco_bcos.model.bo.SharingGetUserFilesInputBO;
import org.fisco.fisco_bcos.model.bo.SharingShareFilesInputBO;
import org.fisco.fisco_bcos.model.bo.SharingStoreFileInputBO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
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

  public TransactionResponse deleteFile(SharingDeleteFileInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.SharingAbi, "deleteFile", input.toArgs());
  }

  public CallResponse getFile(SharingGetFileInputBO input) throws Exception {
    return this.txProcessor.sendCall(this.client.getCryptoSuite().getCryptoKeyPair().getAddress(), this.address, ContractConstants.SharingAbi, "getFile", input.toArgs());
  }

  public TransactionResponse shareFiles(SharingShareFilesInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.SharingAbi, "shareFiles", input.toArgs());
  }

  public TransactionResponse getSharedFiles(SharingGetSharedFilesInputBO input) throws Exception {
    return this.txProcessor.sendTransactionAndGetResponse(this.address, ContractConstants.SharingAbi, "getSharedFiles", input.toArgs());
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
