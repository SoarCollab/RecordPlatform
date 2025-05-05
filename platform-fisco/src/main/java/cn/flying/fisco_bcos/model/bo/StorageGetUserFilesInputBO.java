package cn.flying.fisco_bcos.model.bo;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StorageGetUserFilesInputBO implements Serializable {
  @Serial
  private static final long serialVersionUID = 1L;

  private String uploader;

  public List<Object> toArgs() {
    List args = new ArrayList();
    args.add(uploader);
    return args;
  }
}
