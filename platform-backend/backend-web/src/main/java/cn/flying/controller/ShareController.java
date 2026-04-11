package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.util.CommonUtils;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.vo.file.ShareInfoVO;
import cn.flying.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分享相关接口（面向分享页/分享访问）
 */
@Slf4j
@RestController
@Validated
@RequestMapping("/api/v1/share")
@Tag(name = "分享相关接口", description = "用于分享详情查看等操作")


@RequiredArgsConstructor
public class ShareController {

    private final FileService fileService;

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
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        ShareInfoVO info = fileService.getShareInfo(shareCode);
        if (info == null) {
            return Result.error(ResultEnum.SHARE_NOT_FOUND, null);
        }

        // 处理服务层返回的异常状态
        if (info.getStatus() != null) {
            if (info.getStatus() == FileShare.STATUS_CANCELLED) {
                return Result.error(ResultEnum.SHARE_CANCELLED, null);
            }
            if (info.getStatus() == FileShare.STATUS_EXPIRED) {
                return Result.error(ResultEnum.SHARE_EXPIRED, null);
            }
            if (info.getStatus() == ShareInfoVO.STATUS_EMPTY_FILES) {
                return Result.error(ResultEnum.FAIL, null);
            }
        }

        return Result.success(info);
    }
}
