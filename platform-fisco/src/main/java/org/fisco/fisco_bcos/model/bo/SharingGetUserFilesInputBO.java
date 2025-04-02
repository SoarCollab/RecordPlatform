package org.fisco.fisco_bcos.model.bo;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SharingGetUserFilesInputBO {
  private String uploader;

  public List<Object> toArgs() {
    List args = new ArrayList();
    args.add(uploader);
    return args;
  }
}
