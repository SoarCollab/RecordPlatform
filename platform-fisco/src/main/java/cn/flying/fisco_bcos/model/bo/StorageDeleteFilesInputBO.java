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
public class StorageDeleteFilesInputBO implements Serializable {
  @Serial
  private static final long serialVersionUID = 1L;

  private String uploader;

  private List<byte[]> fileHashes;

  public List<Object> toArgs() {
    List<Object> args = new ArrayList<>();
    args.add(uploader);
    args.add(fileHashes);
    return args;
  }
}
