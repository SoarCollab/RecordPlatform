package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.request.DeleteFilesRequest;
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.*;

import java.util.List;

/**
 * 区块链调用接口 v2.0.0
 * 提供文件存证、查询、删除、分享等区块链操作。
 *
 * <p>v2.0.0 破坏性变更：
 * <ul>
 *   <li>移除 deleteFile(单个) 方法，统一使用 deleteFiles</li>
 *   <li>storeFile 改用 StoreFileRequest/StoreFileResponse DTO</li>
 *   <li>deleteFiles 改用 DeleteFilesRequest DTO</li>
 *   <li>shareFiles 改用 ShareFilesRequest DTO</li>
 * </ul>
 */
public interface BlockChainService {

    /** Dubbo 服务版本号 */
    String VERSION = "2.0.0";

    /**
     * 存储文件到区块链
     *
     * @param request 存储请求
     * @return 存储结果（包含交易哈希和文件哈希）
     */
    Result<StoreFileResponse> storeFile(StoreFileRequest request);

    /**
     * 获取用户所有文件列表
     *
     * @param uploader 上传者标识
     * @return 文件列表
     */
    Result<List<FileVO>> getUserFiles(String uploader);

    /**
     * 获取单个文件详情
     *
     * @param uploader 上传者标识
     * @param fileHash 文件哈希
     * @return 文件详情
     */
    Result<FileDetailVO> getFile(String uploader, String fileHash);

    /**
     * 批量删除文件
     *
     * @param request 删除请求
     * @return 删除结果
     */
    Result<Boolean> deleteFiles(DeleteFilesRequest request);

    /**
     * 分享文件
     *
     * @param request 分享请求
     * @return 分享码
     */
    Result<String> shareFiles(ShareFilesRequest request);

    /**
     * 获取分享文件信息
     *
     * @param shareCode 分享码
     * @return 分享文件信息（取消分享时 expirationTime 为 -1）
     */
    Result<SharingVO> getSharedFiles(String shareCode);

    /**
     * 取消分享
     *
     * @param request 取消分享请求
     * @return 取消结果
     */
    Result<Boolean> cancelShare(CancelShareRequest request);

    /**
     * 获取用户的所有分享码列表
     *
     * @param uploader 上传者标识
     * @return 分享码列表
     */
    Result<List<String>> getUserShareCodes(String uploader);

    /**
     * 获取分享详情（不校验有效性，包含已取消的分享）
     *
     * @param shareCode 分享码
     * @return 分享详情
     */
    Result<SharingVO> getShareInfo(String shareCode);

    /**
     * 获取当前区块链状态
     *
     * @return 区块链状态信息
     */
    Result<BlockChainMessage> getCurrentBlockChainMessage();

    /**
     * 根据交易哈希获取交易详情
     *
     * @param transactionHash 交易哈希
     * @return 交易详情
     */
    Result<TransactionVO> getTransactionByHash(String transactionHash);
}
