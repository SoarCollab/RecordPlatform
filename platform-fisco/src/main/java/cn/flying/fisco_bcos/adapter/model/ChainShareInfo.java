package cn.flying.fisco_bcos.adapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 区块链分享信息
 * 包含分享的文件列表和分享者信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainShareInfo {

    /**
     * 分享者标识
     */
    private String uploader;

    /**
     * 分享的文件哈希列表
     */
    private List<String> fileHashList;

    /**
     * 分享码
     */
    private String shareCode;

    /**
     * 最大访问次数
     */
    private Integer maxAccesses;

    /**
     * 已访问次数
     */
    private Integer accessCount;

    /**
     * 过期时间戳
     */
    private Long expireTimestamp;

    /**
     * 是否有效（未被取消）
     */
    private Boolean isValid;
}
