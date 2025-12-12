package cn.flying.fisco_bcos.adapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 区块链文件基本信息
 * 用于文件列表查询结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainFileInfo {

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件哈希 (链上唯一标识)
     */
    private String fileHash;
}
