package cn.flying.controller;

import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.ErrorPayloadFactory;
import cn.flying.service.ImageService;
import io.minio.errors.ErrorResponseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * @program: forum
 * @description: 文件上传相关接口
 * @author flyingcoding
 * @create: 2024-06-10 12:28
 */
@Slf4j
@RestController
@Tag(name = "图片上传下载相关", description = "包括头像、图片等文件的上传下载操作。")
@RequestMapping("/api/v1/images")
public class ImageController {
    @Resource
    ImageService imageService;

    private static final Pattern AVATAR_PATH_PATTERN = Pattern.compile("^/avatar/[0-9a-fA-F]{32}$");
    private static final Pattern CACHE_PATH_PATTERN = Pattern.compile("^/cache\\d{8}/[0-9a-fA-F]{32}$");
    /**
     * 上传头像
     * @param file 头像文件
     * @param userId 用户ID
     * @return 上传结果
     * @throws IOException IO异常
     */
    @PostMapping("/upload/avatar")
    @Operation(summary = "上传头像")
    @OperationLog(module = "图片上传模块", operationType = "上传", description = "上传头像")
    public Result<String> uploadAvatar(@Schema(description = "头像文件") @RequestParam("file") MultipartFile file,
                                       @RequestAttribute(Const.ATTR_USER_ID) Long userId) throws IOException {
        if (file.isEmpty()) {
            throw new GeneralException(ResultEnum.FILE_EMPTY);
        } else if (file.getSize() > 100 * 1024) {
            throw new GeneralException(ResultEnum.FILE_MAX_SIZE_OVERFLOW, "图片文件过大，请上传小于100KB的图片！");
        }
        log.info("头像文件：{} 上传中", file.getOriginalFilename());
        String fileName = imageService.uploadAvatar(file, userId);
        if (fileName != null) {
            log.info("头像文件上传成功，大小:{}", file.getSize());
            return Result.success(fileName);
        } else {
             throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR);
        }
    }
    /**
     * 上传图片
     * @param file 图片文件
     * @param userId 用户ID
     * @return 上传结果
     * @throws IOException IO异常
     */
    @PostMapping("/upload/image")
    @Operation(summary = "上传图片")
    @OperationLog(module = "图片上传模块", operationType = "上传", description = "上传图片")
    public Result<String> uploadImage(@Schema(description = "图片文件") @RequestParam("file") MultipartFile file,
                                        @RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                        HttpServletResponse response) throws IOException {
        if (file.isEmpty()) {
            throw new GeneralException(ResultEnum.FILE_EMPTY);
        } else if (file.getSize() > 5* 1024*1024) {
            throw new GeneralException(ResultEnum.FILE_MAX_SIZE_OVERFLOW);
        }
        log.info("图像文件：{} 上传中", file.getOriginalFilename());
        String fileName = imageService.uploadImage(file, userId);
        if (fileName != null) {
            log.info("图像文件上传成功，大小:{}", file.getSize());
            return Result.success(fileName);
        } else {
            response.setStatus(400);
            throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR);
        }
    }
    /**
     * 下载头像
     * @param request 请求
     * @param response 响应
     * @throws Exception 异常
     */
    @GetMapping("/download/images/**")
    @Operation(summary = "下载图片")
    @OperationLog(module = "图片上传模块", operationType = "下载", description = "下载图片")
    public void avatarFetch(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.setHeader("Content-Type","image/jpeg");
        this.fetchImage(request,response);
    }
    /**
     * 下载图片
     * @param request 请求
     * @param response 响应
     * @throws Exception 异常
     */
    private void fetchImage(HttpServletRequest request, HttpServletResponse response) throws Exception {
        // 路径格式: /api/v1/images/download/images/{imagePath}
        // 注意：在 Spring Boot 默认 servlet 映射下，request.getServletPath() 可能为空，需使用 requestUri 解析
        String imagePath = extractImagePath(request);
        if (imagePath == null) {
            writeJsonError(request, response, 404, ResultEnum.FILE_NOT_EXIST, "图片不存在");
            return;
        }

        // 安全验证：防止路径遍历攻击
        if (!isValidImagePath(imagePath)) {
            log.warn("检测到路径遍历攻击尝试: {}", imagePath);
            writeJsonError(request, response, 400, ResultEnum.PARAM_IS_INVALID, "图片路径非法");
            return;
        }

        try (ServletOutputStream outputStream = response.getOutputStream()) {
            if (imagePath.length() <= 13) {
                writeJsonError(request, response, 404, ResultEnum.FILE_NOT_EXIST, "图片不存在");
                return;
            }
            imageService.fetchImage(outputStream, imagePath);
            response.setHeader("Cache-Control", "max-age=2592000");
        } catch (ErrorResponseException e) {
            if (e.response() != null && e.response().code() == 404) {
                throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
            } else {
                log.error("从 S3 存储读取图片出现异常：{}", e.getMessage(), e);
                throw new GeneralException(ResultEnum.FAIL);
            }
        }
    }

    /**
     * 从请求中提取图片存储路径（保留前导 /），用于后续安全校验与 S3 读取。
     *
     * @param request 请求
     * @return 图片路径（例如：/avatar/xxx 或 /cache20250101/xxx），无法解析则返回 null
     */
    private String extractImagePath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri == null) {
            return null;
        }

        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath))
                ? requestUri.substring(contextPath.length())
                : requestUri;

        String prefix = "/api/v1/images/download/images";
        if (!path.startsWith(prefix)) {
            return null;
        }
        return path.substring(prefix.length());
    }

    /**
     * 验证图片路径是否安全，防止路径遍历攻击
     */
    private boolean isValidImagePath(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) {
            return false;
        }
        if (AVATAR_PATH_PATTERN.matcher(imagePath).matches() || CACHE_PATH_PATTERN.matcher(imagePath).matches()) {
            return true;
        }
        // 检测路径遍历特征
        if (imagePath.contains("..") || imagePath.contains("//") || imagePath.contains("\\") || imagePath.contains("%00")) {
            return false;
        }
        // 允许以单个 / 开头的相对路径（S3 存储格式）
        String pathToCheck = imagePath.startsWith("/") ? imagePath.substring(1) : imagePath;
        // 多级路径但不符合已知安全格式（/avatar/{uuid} 或 /cacheYYYYMMDD/{uuid}），视为可疑请求
        if (pathToCheck.contains("/")) {
            return false;
        }
        // 单段短路径用于返回 404（由长度校验处理），避免把所有未知路径都当作参数错误
        return imagePath.length() <= 13;
    }

    /**
     * 向客户端写入统一错误 JSON 响应。
     *
     * @param request    当前请求
     * @param response   当前响应
     * @param status     HTTP 状态码
     * @param resultEnum 业务错误码
     * @param detail     错误细节
     * @throws IOException IO 异常
     */
    private void writeJsonError(HttpServletRequest request,
                                HttpServletResponse response,
                                int status,
                                ResultEnum resultEnum,
                                Object detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=utf-8");
        ErrorPayload payload = ErrorPayloadFactory.of(resolveTraceId(request), detail);
        response.getWriter().write(Result.error(resultEnum, payload).toJson());
    }

    /**
     * 提取当前请求 traceId，优先读取请求属性，回退到 MDC。
     *
     * @param request 当前请求
     * @return traceId
     */
    private String resolveTraceId(HttpServletRequest request) {
        Object requestTraceId = request.getAttribute(Const.TRACE_ID);
        if (requestTraceId != null) {
            return String.valueOf(requestTraceId);
        }
        return MDC.get(Const.TRACE_ID);
    }
}
