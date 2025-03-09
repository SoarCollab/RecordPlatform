package org.fisco.fisco_bcos.model.bo;

import java.lang.String;
import java.math.BigInteger;
import org.fisco.bcos.sdk.v3.codec.datatypes.DynamicStruct;
import org.fisco.bcos.sdk.v3.codec.datatypes.Utf8String;
import org.fisco.bcos.sdk.v3.codec.datatypes.generated.Bytes32;
import org.fisco.bcos.sdk.v3.codec.datatypes.generated.Uint256;

public class File extends DynamicStruct {
  public String fileName;

  public String uploader;

  public String content;

  public String param;

  public byte[] fileHash;

  public BigInteger uploadTime;

  public File(Utf8String fileName, Utf8String uploader, Utf8String content, Utf8String param,
      Bytes32 fileHash, Uint256 uploadTime) {
    super(fileName,uploader,content,param,fileHash,uploadTime);
    this.fileName = fileName.getValue();
    this.uploader = uploader.getValue();
    this.content = content.getValue();
    this.param = param.getValue();
    this.fileHash = fileHash.getValue();
    this.uploadTime = uploadTime.getValue();
  }

  public File(String fileName, String uploader, String content, String param, byte[] fileHash,
      BigInteger uploadTime) {
    super(new org.fisco.bcos.sdk.v3.codec.datatypes.Utf8String(fileName),new org.fisco.bcos.sdk.v3.codec.datatypes.Utf8String(uploader),new org.fisco.bcos.sdk.v3.codec.datatypes.Utf8String(content),new org.fisco.bcos.sdk.v3.codec.datatypes.Utf8String(param),new org.fisco.bcos.sdk.v3.codec.datatypes.generated.Bytes32(fileHash),new org.fisco.bcos.sdk.v3.codec.datatypes.generated.Uint256(uploadTime));
    this.fileName = fileName;
    this.uploader = uploader;
    this.content = content;
    this.param = param;
    this.fileHash = fileHash;
    this.uploadTime = uploadTime;
  }
}
