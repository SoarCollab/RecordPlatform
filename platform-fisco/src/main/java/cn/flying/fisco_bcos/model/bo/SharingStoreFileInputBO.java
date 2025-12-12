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
public class SharingStoreFileInputBO implements Serializable {
  @Serial
  private static final long serialVersionUID = 1L;

  private String fileName;

  private String uploader;

  private String content;

  private String param;

  public List<Object> toArgs() {
    List<Object> args = new ArrayList<>();
    args.add(fileName);
    args.add(uploader);
    args.add(content);
    args.add(param);
    return args;
  }
}
