package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
public class SharingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String uploader;
    private List<String> fileHashList;
}
