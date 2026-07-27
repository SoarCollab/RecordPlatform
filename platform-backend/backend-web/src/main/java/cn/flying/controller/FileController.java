package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.CreateVersionVO;
import cn.flying.dao.vo.file.FileProvenanceVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.FileVO;
import cn.flying.dao.vo.file.FileVersionVO;
import cn.flying.dao.vo.file.ProofBundleVO;
import cn.flying.dao.vo.file.ProofStatusVO;
import cn.flying.dao.vo.file.RevokeProofRequest;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import cn.flying.dao.vo.file.UserFileStatsVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.proof.ProofBundleService;
import cn.flying.service.proof.signed.ProofArchive;
import cn.flying.service.proof.signed.SignedProofArchiveService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件操作相关接口。
 * <p>
 * 采用 CQRS 模式：
 * Query 操作（读）使用 FileQueryService；Command 操作（写）使用 FileService。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "文件操作相关接口", description = "包括获取文件列表、删除文件、获取文件地址等操作。")


@RequiredArgsConstructor
public class FileController {

    private static final Set<String> SENSITIVE_FILE_PARAM_FIELDS = Set.of(
            "initialkey", "decryptkey", "decryptionkey", "filekey", "filedatakey",
            "wrappeddatakey", "encrypteddatakey", "wrappedkey", "encryptedkey",
            "wrappingiv", "ciphertext", "kmskeyid", "keyid", "keyreference",
            "providerkeyversion", "keyversion", "keygrant", "grantreference",
            "sessionid", "downloadsessionid", "presignedurl", "downloadurl");

    private final FileQueryService fileQueryService;

    private final FileService fileService;

    private final ShareAuditService shareAuditService;

    private final ProofBundleService proofBundleService;

    private final SignedProofArchiveService signedProofArchiveService;

    /**
     * 根据文件 ID 获取文件详情。
     *
     * @param userId 用户 ID
     * @param id     文件外部 ID
     * @return 文件详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据文件ID获取文件详情")
    @OperationLog(module = "文件操作", operationType = "查询", description = "根据ID获取文件详情")
    public Result<FileVO> getFileById(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "文件ID") @PathVariable String id) {
        Long fileId = IdUtils.fromExternalId(id);
        File file = fileQueryService.getFileById(userId, fileId);
        return Result.success(toFileVO(file));
    }

    /**
     * 根据文件 ID 导出第三方可检查的证明包。
     *
     * @param userId 用户 ID
     * @param id     文件外部 ID
     * @return 证明包
     */
    @GetMapping("/{id}/proof-bundle")
    @Operation(summary = "导出旧版 JSON 文件证明包", deprecated = true,
            description = "兼容 proof-bundle.v1.1；新集成请使用同路径的 .zip 端点")
    @OperationLog(module = "文件操作", operationType = "查询", description = "导出文件证明包")
    public Result<ProofBundleVO> exportProofBundleByFile(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "文件ID") @PathVariable String id) {
        Long fileId = IdUtils.fromExternalId(id);
        if (fileId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "无效的文件ID");
        }
        return Result.success(proofBundleService.exportByFileId(userId, fileId));
    }

    /**
     * 根据存证叶子 ID 导出第三方可检查的证明包。
     *
     * @param userId 用户 ID
     * @param leafId 存证叶子外部 ID
     * @return 证明包
     */
    @GetMapping("/attestation-leaves/{leafId}/proof-bundle")
    @Operation(summary = "按存证叶子导出旧版 JSON 文件证明包", deprecated = true,
            description = "兼容 proof-bundle.v1.1；新集成请使用同路径的 .zip 端点")
    @OperationLog(module = "文件操作", operationType = "查询", description = "按存证叶子导出文件证明包")
    public Result<ProofBundleVO> exportProofBundleByLeaf(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "存证叶子ID") @PathVariable String leafId) {
        Long internalLeafId = IdUtils.fromExternalId(leafId);
        if (internalLeafId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "无效的存证叶子ID");
        }
        return Result.success(proofBundleService.exportByLeafId(userId, internalLeafId));
    }

    /**
     * 根据文件 ID 流式导出固定八条目的签名 proof ZIP。
     *
     * @param userId 用户 ID
     * @param id 文件外部 ID
     * @return application/zip 流式响应
     */
    @GetMapping(value = "/{id}/proof-bundle.zip", produces = "application/zip")
    @Operation(summary = "导出签名文件证明 ZIP")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "签名证明 ZIP",
                    headers = {
                            @Header(name = HttpHeaders.CONTENT_DISPOSITION,
                                    description = "RFC 5987 编码的安全附件文件名",
                                    schema = @Schema(type = "string")),
                            @Header(name = HttpHeaders.CACHE_CONTROL,
                                    description = "证明包禁止缓存并要求重新验证",
                                    schema = @Schema(type = "string")),
                            @Header(name = "X-Proof-Manifest-Hash",
                                    description = "ZIP 顶层 manifest 的 SHA-256 摘要",
                                    schema = @Schema(type = "string"))
                    },
                    content = @Content(mediaType = "application/zip",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "503", description = "证明生成容量已满或依赖暂时不可用",
                    headers = @Header(name = HttpHeaders.RETRY_AFTER,
                            description = "客户端再次尝试前需等待的秒数",
                            schema = @Schema(type = "integer", format = "int32", example = "5")),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Result.class)))
    })
    @RateLimit(
            limit = 10,
            period = 60,
            adminLimit = 30,
            monitorLimit = 30,
            type = RateLimit.LimitType.USER,
            key = "proof:archive")
    @OperationLog(module = "文件操作", operationType = "查询", description = "导出签名文件证明 ZIP")
    public ResponseEntity<StreamingResponseBody> exportSignedProofArchiveByFile(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "文件ID") @PathVariable String id) {
        Long fileId = requireExternalId(id, "无效的文件ID");
        return proofArchiveResponse(signedProofArchiveService.exportByFileId(userId, fileId));
    }

    /**
     * 根据存证叶子 ID 流式导出固定八条目的签名 proof ZIP。
     *
     * @param userId 用户 ID
     * @param leafId 存证叶子外部 ID
     * @return application/zip 流式响应
     */
    @GetMapping(value = "/attestation-leaves/{leafId}/proof-bundle.zip", produces = "application/zip")
    @Operation(summary = "按存证叶子导出签名文件证明 ZIP")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "签名证明 ZIP",
                    headers = {
                            @Header(name = HttpHeaders.CONTENT_DISPOSITION,
                                    description = "RFC 5987 编码的安全附件文件名",
                                    schema = @Schema(type = "string")),
                            @Header(name = HttpHeaders.CACHE_CONTROL,
                                    description = "证明包禁止缓存并要求重新验证",
                                    schema = @Schema(type = "string")),
                            @Header(name = "X-Proof-Manifest-Hash",
                                    description = "ZIP 顶层 manifest 的 SHA-256 摘要",
                                    schema = @Schema(type = "string"))
                    },
                    content = @Content(mediaType = "application/zip",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "503", description = "证明生成容量已满或依赖暂时不可用",
                    headers = @Header(name = HttpHeaders.RETRY_AFTER,
                            description = "客户端再次尝试前需等待的秒数",
                            schema = @Schema(type = "integer", format = "int32", example = "5")),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Result.class)))
    })
    @RateLimit(
            limit = 10,
            period = 60,
            adminLimit = 30,
            monitorLimit = 30,
            type = RateLimit.LimitType.USER,
            key = "proof:archive")
    @OperationLog(module = "文件操作", operationType = "查询", description = "按存证叶子导出签名文件证明 ZIP")
    public ResponseEntity<StreamingResponseBody> exportSignedProofArchiveByLeaf(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "存证叶子ID") @PathVariable String leafId) {
        Long internalLeafId = requireExternalId(leafId, "无效的存证叶子ID");
        return proofArchiveResponse(signedProofArchiveService.exportByLeafId(userId, internalLeafId));
    }

    /**
     * 撤销当前用户有权管理的叶子证明，重复撤销保持幂等。
     *
     * @param userId 用户 ID
     * @param leafId 存证叶子外部 ID
     * @param request 可选撤销原因
     * @return 当前公开状态
     */
    @PostMapping("/attestation-leaves/{leafId}/proof-status/revoke")
    @Operation(summary = "撤销签名文件证明")
    @RateLimit(
            limit = 10,
            period = 60,
            adminLimit = 30,
            monitorLimit = 30,
            type = RateLimit.LimitType.USER,
            key = "proof:revoke")
    @OperationLog(module = "文件操作", operationType = "更新", description = "撤销签名文件证明")
    public Result<ProofStatusVO> revokeSignedProof(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "存证叶子ID") @PathVariable String leafId,
            @Valid @RequestBody(required = false) RevokeProofRequest request) {
        Long internalLeafId = requireExternalId(leafId, "无效的存证叶子ID");
        String reason = request == null ? null : request.reason();
        return Result.success(signedProofArchiveService.revokeByLeafId(userId, internalLeafId, reason));
    }

    /**
     * 构建不缓存的安全附件响应，ZIP 内容由受限 archive 直接写入 HTTP 输出流。
     */
    private ResponseEntity<StreamingResponseBody> proofArchiveResponse(ProofArchive archive) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(archive.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl(CacheControl.noStore().mustRevalidate());
        headers.set("X-Proof-Manifest-Hash", archive.manifestHash());
        return ResponseEntity.ok()
                .headers(headers)
                .body(archive::writeTo);
    }

    /**
     * 把外部 ID 转换为内部 ID，并统一拒绝非法输入。
     */
    private Long requireExternalId(String externalId, String errorMessage) {
        Long internalId = IdUtils.fromExternalId(externalId);
        if (internalId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, errorMessage);
        }
        return internalId;
    }

    /**
     * 获取用户文件统计信息（用于 Dashboard）。
     *
     * @param userId 用户 ID
     * @return 文件统计信息
     */
    @GetMapping("/stats")
    @Operation(summary = "获取用户文件统计信息（用于 Dashboard）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取用户文件统计")
    public Result<UserFileStatsVO> getUserFileStats(@RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        UserFileStatsVO stats = fileQueryService.getUserFileStats(userId);
        return Result.success(stats);
    }

    /**
     * 获取我的分享列表。
     *
     * @param userId   用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分享分页
     */
    @GetMapping("/shares")
    @Operation(summary = "获取我的分享列表")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取我的分享列表")
    public Result<IPage<FileShareVO>> getMyShares(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<FileShareVO> shares = fileQueryService.getUserShares(userId, page);
        return Result.success(shares);
    }

    /**
     * 批量删除文件。
     *
     * @param userId      用户 ID
     * @param identifiers 文件哈希或文件 ID 列表
     * @return 操作结果
     */
    @DeleteMapping
    @Operation(summary = "批量删除文件（支持通过文件哈希或文件ID）")
    @OperationLog(module = "文件操作", operationType = "删除", description = "批量删除文件")
    public Result<String> deleteFiles(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "待删除文件标识列表（支持文件哈希或文件ID）") @RequestParam("identifiers") List<String> identifiers) {
        fileService.deleteFiles(userId, identifiers);
        return Result.success("文件删除成功");
    }

    /**
     * 管理员按文件 ID 删除文件。
     *
     * @param id 文件 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "根据文件ID删除文件（管理员专用）")
    @PreAuthorize("hasPerm('file:admin')")
    @OperationLog(module = "文件操作", operationType = "删除", description = "删除文件")
    public Result<String> deleteFileById(
            @Schema(description = "待删除文件ID") @PathVariable("id") String externalId) {
        Long internalId = IdUtils.fromExternalId(externalId);
        if (internalId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "无效的文件ID");
        }
        fileService.removeByIds(List.of(internalId));
        return Result.success("文件删除成功");
    }

    /**
     * 取消分享（调用区块链）。
     *
     * @param userId    用户 ID
     * @param shareCode 分享码
     * @return 操作结果
     */
    @DeleteMapping("/share/{shareCode}")
    @Operation(summary = "取消分享")
    @OperationLog(module = "文件操作", operationType = "删除", description = "取消分享")
    public Result<String> cancelShare(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享码") @PathVariable String shareCode) {
        fileService.cancelShare(userId, shareCode);
        return Result.success("分享已取消");
    }

    /**
     * 获取分享访问日志（管理员专用）。
     *
     * @param shareCode 分享码
     * @param pageNum   页码
     * @param pageSize  每页数量
     * @return 访问日志分页
     */
    @GetMapping("/share/{shareCode}/access-logs")
    @Operation(summary = "获取分享的访问日志（管理员专用）")
    @PreAuthorize("isAdmin()")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取分享访问日志")
    public Result<IPage<ShareAccessLogVO>> getShareAccessLogs(
            @Parameter(description = "分享码") @PathVariable String shareCode,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<ShareAccessLogVO> logs = shareAuditService.getShareAccessLogs(shareCode, page);
        return Result.success(logs);
    }

    /**
     * 获取分享访问统计（管理员专用）。
     *
     * @param shareCode 分享码
     * @return 访问统计
     */
    @GetMapping("/share/{shareCode}/stats")
    @Operation(summary = "获取分享的访问统计（管理员专用）")
    @PreAuthorize("isAdmin()")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取分享访问统计")
    public Result<ShareAccessStatsVO> getShareAccessStats(
            @Parameter(description = "分享码") @PathVariable String shareCode) {
        ShareAccessStatsVO stats = shareAuditService.getShareAccessStats(shareCode);
        return Result.success(stats);
    }

    /**
     * 获取文件溯源信息（管理员专用）。
     *
     * @param id 文件外部 ID
     * @return 溯源信息
     */
    @GetMapping("/{id}/provenance")
    @Operation(summary = "获取文件的溯源信息（管理员专用）")
    @PreAuthorize("isAdmin()")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取文件溯源信息")
    public Result<FileProvenanceVO> getFileProvenance(
            @Parameter(description = "文件ID") @PathVariable String id) {
        Long fileId = IdUtils.fromExternalId(id);
        FileProvenanceVO provenance = shareAuditService.getFileProvenance(fileId);
        return Result.success(provenance);
    }

    /**
     * 获取文件版本历史。
     *
     * @param userId 用户 ID
     * @param id     文件外部 ID（版本链中任意一个文件）
     * @return 版本历史列表
     */
    @GetMapping("/{id}/versions")
    @Operation(summary = "获取文件版本历史")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取文件版本历史")
    public Result<List<FileVersionVO>> getFileVersionHistory(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "文件ID") @PathVariable String id) {
        Long fileId = IdUtils.fromExternalId(id);
        List<FileVersionVO> versions = fileQueryService.getFileVersionHistory(userId, fileId);
        return Result.success(versions);
    }

    /**
     * 创建文件新版本。
     * 返回 PREPARE 状态的新文件，客户端拿 fileId 走现有上传流程。
     *
     * @param userId 用户 ID
     * @param id     父版本文件外部 ID
     * @param vo     创建参数
     * @return 新版本信息（fileId、version、versionGroupId 均为外部 ID）
     */
    @PostMapping("/{id}/versions")
    @Operation(summary = "创建文件新版本")
    @OperationLog(module = "文件操作", operationType = "新增", description = "创建文件新版本")
    public Result<Map<String, Object>> createNewVersion(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "父版本文件ID") @PathVariable String id,
            @Valid @RequestBody CreateVersionVO vo) {
        Long parentFileId = IdUtils.fromExternalId(id);
        File newVersion = fileService.createNewVersion(userId, parentFileId, vo.fileName(), vo.fileSize(), vo.contentType());
        Map<String, Object> result = Map.of(
                "fileId", IdUtils.toExternalId(newVersion.getId()),
                "version", newVersion.getVersion(),
                "versionGroupId", IdUtils.toExternalId(newVersion.getVersionGroupId())
        );
        return Result.success(result);
    }

    /**
     * Convert File entity to FileVO, mapping internal IDs to external IDs.
     */
    static FileVO toFileVO(File file) {
        if (file == null) {
            return null;
        }
        return new FileVO(
                IdUtils.toExternalId(file.getId()),
                file.getFileName(),
                file.getClassification(),
                sanitizeFileParamForResponse(file.getFileParam()),
                file.getFileHash(),
                file.getTransactionHash(),
                file.getStatus(),
                file.getFileSize(),
                file.getContentType(),
                file.getVersion(),
                file.getIsLatest(),
                IdUtils.toExternalId(file.getVersionGroupId()),
                IdUtils.toExternalId(file.getParentVersionId()),
                file.getOwnerName(),
                file.getOriginOwnerName(),
                file.getSharedFromUserName(),
                file.getCreateTime()
        );
    }

    /**
     * 从通用文件视图剥离历史明文、包封材料和密钥标识，阻断 grant 之外的下载密钥旁路。
     */
    private static String sanitizeFileParamForResponse(String fileParam) {
        if (fileParam == null || fileParam.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> parsed = JsonConverter.parse(fileParam, Map.class);
            return JsonConverter.toJson(sanitizeFileParamValue(parsed));
        } catch (GeneralException exception) {
            return null;
        }
    }

    /**
     * 递归清理 JSON 对象和数组，防止把敏感字段嵌套后绕过顶层字段过滤。
     */
    private static Object sanitizeFileParamValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((rawKey, rawValue) -> {
                if (!(rawKey instanceof String key)
                        || SENSITIVE_FILE_PARAM_FIELDS.contains(normalizeFileParamFieldName(key))) {
                    return;
                }
                sanitized.put(key, sanitizeFileParamValue(rawValue));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FileController::sanitizeFileParamValue).toList();
        }
        return value;
    }

    /**
     * 统一大小写及分隔符后识别敏感字段，阻断 initial_key 等等价拼写绕过。
     */
    private static String normalizeFileParamFieldName(String fieldName) {
        String lowerCase = fieldName.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lowerCase.length());
        for (int index = 0; index < lowerCase.length(); index++) {
            char character = lowerCase.charAt(index);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }
}
