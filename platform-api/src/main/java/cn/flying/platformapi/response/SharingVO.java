package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 文件分享信息视图对象
 * 用于返回文件分享相关的数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SharingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String uploader;
    private List<String> fileHashList;
}
