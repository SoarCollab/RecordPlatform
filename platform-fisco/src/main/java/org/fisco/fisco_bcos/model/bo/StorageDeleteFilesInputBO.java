package org.fisco.fisco_bcos.model.bo;

import java.lang.Object;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StorageDeleteFilesInputBO {
  private String uploader;

  private List<byte[]> fileHashes;

  public List<Object> toArgs() {
    List args = new ArrayList();
    args.add(uploader);
    args.add(fileHashes);
    return args;
  }
}
