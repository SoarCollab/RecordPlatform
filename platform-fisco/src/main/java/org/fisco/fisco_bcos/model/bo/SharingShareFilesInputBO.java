package org.fisco.fisco_bcos.model.bo;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SharingShareFilesInputBO {
  private String uploader;

  private List<byte[]> fileHashes;

  private Integer maxAccesses;

  public List<Object> toArgs() {
    List args = new ArrayList();
    args.add(uploader);
    args.add(fileHashes);
    args.add(maxAccesses);
    return args;
  }
}
