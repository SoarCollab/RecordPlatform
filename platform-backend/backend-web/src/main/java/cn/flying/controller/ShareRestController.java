package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.dao.vo.file.SaveSharingFile;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.ShareAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分享 REST 新路径控制器。
 */
@RestController
@Tag(name = "分享操作（REST）", description = "分享链路 REST 新路径")
public class ShareRestController {

    @Resource
    private FileService fileService;

    @Resource
    private FileQueryService fileQueryService;

    @Resource
    private ShareAuditService shareAuditService;

    /**
     * 创建分享（REST 新路径）。
     *
     * @param userId        用户 ID
     * @param fileSharingVO 创建分享参数
     * @return 分享码
     */
    @PostMapping("/api/v1/shares")
    @Operation(summary = "创建分享（REST）")
    @OperationLog(module = "文件操作", operationType = "分享", description = "创建分享（REST）")
    public Result<String> createShare(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                      @RequestBody @Valid FileSharingVO fileSharingVO) {
        String sharingCode = fileService.generateSharingCode(
                userId,
                fileSharingVO.getFileHash(),
                fileSharingVO.getExpireMinutes(),
                fileSharingVO.getShareType()
        );
        return Result.success(sharingCode);
    }

    /**
     * 更新分享设置（REST 新路径）。
     *
     * @param userId      用户 ID
     * @param shareCode   分享码
     * @param updateShare 更新参数
     * @return 操作结果
     */
    @PatchMapping("/api/v1/shares/{shareCode}")
    @Operation(summary = "更新分享设置（REST）")
    @OperationLog(module = "文件操作", operationType = "更新", description = "更新分享设置（REST）")
    public Result<String> updateShare(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                      @PathVariable String shareCode,
                                      @RequestBody @Valid UpdateShareVO updateShare) {
        updateShare.setShareCode(shareCode);
        fileService.updateShare(userId, updateShare);
        return Result.success("分享设置已更新");
    }

    /**
     * 获取分享文件列表（REST 新路径）。
     *
     * @param shareCode 分享码
     * @param userId    当前用户 ID（可空）
     * @param request   HTTP 请求
     * @return 文件列表
     */
    @GetMapping("/api/v1/shares/{shareCode}/files")
    @Operation(summary = "获取分享文件列表（REST）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取分享文件列表（REST）")
    public Result<List<File>> getSharedFiles(@PathVariable String shareCode,
                                             @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
                                             HttpServletRequest request) {
        List<File> files = fileQueryService.getShareFile(shareCode);
        shareAuditService.logShareView(shareCode, userId, getClientIp(request), request.getHeader("User-Agent"));
        return Result.success(files);
    }

    /**
     * 保存分享文件到我的空间（REST 新路径）。
     *
     * @param shareCode    分享码
     * @param sharingFile  保存参数
     * @param request      HTTP 请求
     * @return 操作结果
     */
    @PostMapping("/api/v1/shares/{shareCode}/files/save")
    @Operation(summary = "保存分享文件（REST）")
    @OperationLog(module = "文件操作", operationType = "保存", description = "保存分享文件（REST）")
    public Result<String> saveSharedFiles(@PathVariable String shareCode,
                                          @RequestBody @Valid SaveSharingFile sharingFile,
                                          HttpServletRequest request) {
        sharingFile.setShareCode(shareCode);
        fileService.saveShareFile(sharingFile.getSharingFileIdList(), sharingFile.getShareCode(), getClientIp(request));
        return Result.success("保存成功");
    }

    /**
     * 分享下载文件（REST 新路径，需登录）。
     *
     * @param userId    当前用户 ID
     * @param shareCode 分享码
     * @param fileHash  文件哈希
     * @param request   HTTP 请求
     * @return 文件分片
     */
    @GetMapping("/api/v1/shares/{shareCode}/files/{fileHash}/chunks")
    @Operation(summary = "分享下载文件（REST，需登录）")
    @OperationLog(module = "文件操作", operationType = "下载", description = "分享下载文件（REST）")
    public Result<List<byte[]>> downloadSharedFile(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                                   @PathVariable String shareCode,
                                                   @PathVariable String fileHash,
                                                   HttpServletRequest request) {
        List<byte[]> files = fileService.getSharedFileContent(userId, shareCode, fileHash);
        shareAuditService.logShareDownload(shareCode, userId, fileHash, null, getClientIp(request));
        return Result.success(files);
    }

    /**
     * 分享获取解密信息（REST 新路径，需登录）。
     *
     * @param userId    当前用户 ID
     * @param shareCode 分享码
     * @param fileHash  文件哈希
     * @return 解密信息
     */
    @GetMapping("/api/v1/shares/{shareCode}/files/{fileHash}/decrypt-info")
    @Operation(summary = "分享获取解密信息（REST，需登录）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "分享获取解密信息（REST）")
    public Result<FileDecryptInfoVO> getSharedDecryptInfo(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                                          @PathVariable String shareCode,
                                                          @PathVariable String fileHash) {
        return Result.success(fileService.getSharedFileDecryptInfo(userId, shareCode, fileHash));
    }

    /**
     * 公开分享下载文件（REST 新路径，无需登录）。
     *
     * @param shareCode 分享码
     * @param fileHash  文件哈希
     * @param request   HTTP 请求
     * @return 文件分片
     */
    @GetMapping("/api/v1/public/shares/{shareCode}/files/{fileHash}/chunks")
    @RateLimit(limit = 30, type = RateLimit.LimitType.IP, key = "public:download")
    @Operation(summary = "公开分享下载文件（REST）")
    public Result<List<byte[]>> publicDownload(@PathVariable String shareCode,
                                               @PathVariable String fileHash,
                                               HttpServletRequest request) {
        List<byte[]> files = fileService.getPublicFile(shareCode, fileHash);
        shareAuditService.logShareDownload(shareCode, null, fileHash, null, getClientIp(request));
        return Result.success(files);
    }

    /**
     * 公开分享获取解密信息（REST 新路径，无需登录）。
     *
     * @param shareCode 分享码
     * @param fileHash  文件哈希
     * @return 解密信息
     */
    @GetMapping("/api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info")
    @RateLimit(limit = 30, type = RateLimit.LimitType.IP, key = "public:decryptInfo")
    @Operation(summary = "公开分享获取解密信息（REST）")
    public Result<FileDecryptInfoVO> publicDecryptInfo(@Parameter(description = "分享码") @PathVariable String shareCode,
                                                       @Parameter(description = "文件哈希") @PathVariable String fileHash) {
        return Result.success(fileService.getPublicFileDecryptInfo(shareCode, fileHash));
    }

    /**
     * 获取客户端 IP。
     *
     * @param request 当前请求
     * @return 客户端 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}

