package org.fisco.fisco_bcos.model.bo;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SharingGetFileInputBO {
  private String uploader;

  private byte[] fileHash;

  public List<Object> toArgs() {
    List args = new ArrayList();
    args.add(uploader);
    args.add(fileHash);
    return args;
  }
}
