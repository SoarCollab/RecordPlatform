package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.vo.file.ShareInfoVO;
import cn.flying.service.FileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 分享相关接口（面向分享页/分享访问）
 */
@Slf4j
@RestController
@Validated
@RequestMapping("/api/v1/share")
@Tag(name = "分享相关接口", description = "用于分享详情查看等操作")
public class ShareController {

    @Resource
    private FileService fileService;

    @Resource
    private FileMapper fileMapper;

    /**
     * 获取分享详情（包含分享文件列表）。
     * <p>
     * 约定：保持 HTTP 200，通过业务 code 表达分享不存在/已取消/已过期等状态。
     * </p>
     *
     * @param shareCode 分享码
     * @return 分享详情
     */
    @GetMapping("/{shareCode}/info")
    @Operation(summary = "获取分享详情")
    @OperationLog(module = "分享", operationType = "查询", description = "获取分享详情")
    public Result<ShareInfoVO> getShareInfo(@Parameter(description = "分享码") @PathVariable String shareCode) {
        if (CommonUtils.isBlank(shareCode) || shareCode.length() > 64) {
            return Result.failure(ResultEnum.PARAM_IS_INVALID.getCode(), "分享码格式错误");
        }

        FileShare fileShare = fileService.getShareByCode(shareCode);
        if (fileShare == null) {
            return Result.failure(404, "分享不存在");
        }

        if (fileShare.getStatus() != null) {
            if (fileShare.getStatus() == FileShare.STATUS_CANCELLED) {
                return Result.failure(ResultEnum.SHARE_CANCELLED.getCode(), ResultEnum.SHARE_CANCELLED.getMessage());
            }
            if (fileShare.getStatus() == FileShare.STATUS_EXPIRED) {
                return Result.failure(ResultEnum.SHARE_EXPIRED.getCode(), ResultEnum.SHARE_EXPIRED.getMessage());
            }
        }

        if (fileShare.getExpireTime() != null && fileShare.getExpireTime().before(new Date())) {
            return Result.failure(ResultEnum.SHARE_EXPIRED.getCode(), ResultEnum.SHARE_EXPIRED.getMessage());
        }

        List<String> fileHashes = parseFileHashes(fileShare.getFileHashes());
        if (CommonUtils.isEmpty(fileHashes)) {
            return Result.failure(ResultEnum.FAIL.getCode(), "分享文件为空");
        }

        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, fileShare.getUserId())
                .in(File::getFileHash, fileHashes);

        // 公开分享接口可能绕过 TenantFilter（无租户上下文），这里根据分享记录的 tenantId 临时补齐租户上下文以完成文件查询
        List<File> files;
        if (TenantContext.isSet()) {
            files = fileMapper.selectList(wrapper);
        } else {
            Long shareTenantId = fileShare.getTenantId() != null ? fileShare.getTenantId() : 0L;
            files = TenantContext.callWithTenant(shareTenantId, () -> fileMapper.selectList(wrapper));
        }

        ShareInfoVO info = new ShareInfoVO();
        info.setShareCode(fileShare.getShareCode());
        info.setShareType(fileShare.getShareType());
        info.setExpireTime(fileShare.getExpireTime());
        info.setFiles(files != null ? files : List.of());

        return Result.success(info);
    }

    /**
     * 解析 FileShare.fileHashes 的 JSON 数组字符串。
     *
     * @param fileHashesJson JSON 数组字符串
     * @return 文件哈希列表
     */
    private List<String> parseFileHashes(String fileHashesJson) {
        if (CommonUtils.isEmpty(fileHashesJson)) {
            return List.of();
        }
        try {
            String[] hashes = JsonConverter.parse(fileHashesJson, String[].class);
            return hashes != null ? Arrays.asList(hashes) : List.of();
        } catch (Exception e) {
            log.warn("解析分享文件哈希列表失败: {}", fileHashesJson);
            return List.of();
        }
    }

}
