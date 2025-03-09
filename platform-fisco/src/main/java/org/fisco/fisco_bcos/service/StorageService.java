package org.fisco.fisco_bcos.service;

import java.lang.Exception;
import java.lang.String;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.transaction.manager.AssembleTransactionProcessor;
import org.fisco.bcos.sdk.v3.transaction.manager.TransactionProcessorFactory;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import org.fisco.fisco_bcos.constants.ContractConstants;
import org.fisco.fisco_bcos.model.bo.StorageDeleteFileInputBO;
import org.fisco.fisco_bcos.model.bo.StorageDeleteFilesInputBO;
import org.fisco.fisco_bcos.model.bo.StorageGetFileInputBO;
import org.fisco.fisco_bcos.model.bo.StorageGetUserFilesInputBO;
import org.fisco.fisco_bcos.model.bo.StorageStoreFileInputBO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@NoArgsConstructor
@Data
public class StorageService {
  @Value("${contract.storageAddress}")
  private String address;

  @Autowired
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
