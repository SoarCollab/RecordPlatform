package cn.flying.service;

import cn.flying.dao.dto.File;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.data.relational.core.sql.In;

import java.util.List;

/**
 * 文件服务接口（经过多层封装后对外暴露的统一接口）
 * @program: RecordPlatform
 * @author: flyingcoding
 * @create: 2025-03-12 21:22
 */
public interface FileService extends IService<File> {

    /**
     * 在完成分片上传后预存储文件（此时设置文件状态为）
     * @param Uid 用户ID
     * @param OriginFileName 原始文件名
     * @return
     */
    void prepareStoreFile(String Uid,String OriginFileName);
    /**
     * 存储文件
     * @param Uid 用户ID
     * @param fileList 加密后的文件分片列表
     * @param fileHashList 文件分片对应的哈希列表
     * @param fileParam 文件参数(JSON)
     * @return
     */
    File storeFile(String Uid, String OriginFileName, List<java.io.File> fileList,List<String> fileHashList, String fileParam);

    /**
     * 修改文件状态
     * @param Uid
     * @param fileHash
     * @param fileStatus
     */
    void changeFileStatus(String Uid, String fileHash, Integer fileStatus);

    /**
     * 删除文件
     * @param Uid 用户ID
     * @param fileHash 文件哈希
     * @return
     */
    void deleteFile(String Uid, String fileHash);

    /**
     * 根据用户Id获取用户文件列表（只包含文件元信息，没有实际的文件数据）
     * @param Uid 用户ID
     * @return 文件元信息列表
     */
    List<File> getUserFiles(String Uid);

    /**
     * 获取文件分片地址
     * @param Uid 用户ID
     * @param fileHash 文件哈希
     * @return 文件分片地址
     */
    List<String> getFileAddress(String Uid, String fileHash);

    /**
     * 获取文件分片
     * @param Uid 用户ID
     * @param fileHash 文件哈希
     * @return 文件分片列表
     */
    List<java.io.File> getFile(String Uid, String fileHash);

    /**
     * 分享文件给其它用户
     * @param Uid 用户ID
     * @param fileHash 待分享的文件哈希
     * @param maxAccesses 最大访问次数
     * @return 分享码
     */
    String generateSharingCode(String Uid, List<String> fileHash, Integer maxAccesses);

    /**
     * 获取根据分享码获取他人分享的文件
     * @param sharingCode 分享码
     * @return
     */
    List<File> getShareFile(String sharingCode);

    /**
     * 保存他人分享的文件
     * @param sharingFileIdList 分享文件Id列表
     */
    void saveShareFile(List<String> sharingFileIdList);


}
