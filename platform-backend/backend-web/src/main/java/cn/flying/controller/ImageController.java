package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

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
            return Result.error(ResultEnum.FILE_EMPTY);
        } else if (file.getSize() > 100 * 1024) {
            return Result.error("图片文件过大，请上传小于100KB的图片！");
        }
        log.info("头像文件：{} 上传中", file.getOriginalFilename());
        String fileName = imageService.uploadAvatar(file, userId);
        if (fileName != null) {
            log.info("头像文件上传成功，大小:{}", file.getSize());
            return Result.success(fileName);
        } else {
             return Result.error(ResultEnum.FILE_UPLOAD_ERROR);
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
            return Result.error(ResultEnum.FILE_EMPTY);
        } else if (file.getSize() > 5* 1024*1024) {
            return Result.error(ResultEnum.FILE_MAX_SIZE_OVERFLOW);
        }
        log.info("图像文件：{} 上传中", file.getOriginalFilename());
        String fileName = imageService.uploadImage(file, userId);
        if (fileName != null) {
            log.info("图像文件上传成功，大小:{}", file.getSize());
            return Result.success(fileName);
        } else {
            response.setStatus(400);
            return Result.error(ResultEnum.FILE_UPLOAD_ERROR);
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
        // 前缀长度: "/api/v1/images/download/images/" = 31 字符
        String servletPath = request.getServletPath();
        String prefix = "/api/v1/images/download/images/";
        String imagePath = servletPath.startsWith(prefix) ? servletPath.substring(prefix.length()) : servletPath.substring(31);

        // 安全验证：防止路径遍历攻击
        if (!isValidImagePath(imagePath)) {
            log.warn("检测到路径遍历攻击尝试: {}", imagePath);
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write(Result.error(ResultEnum.PARAM_IS_INVALID).toString());
            return;
        }

        try (ServletOutputStream outputStream = response.getOutputStream()) {
            if (imagePath.length() <= 13) {
                response.setStatus(404);
                response.setContentType("application/json");
                response.getWriter().write(Result.error(ResultEnum.FILE_NOT_EXIST).toString());
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
     * 验证图片路径是否安全，防止路径遍历攻击
     */
    private boolean isValidImagePath(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) {
            return false;
        }
        // 检测路径遍历特征
        if (imagePath.contains("..") || imagePath.contains("//")) {
            return false;
        }
        // 检测绝对路径（Unix 和 Windows）
        if (imagePath.startsWith("/") || imagePath.startsWith("\\") ||
            (imagePath.length() >= 2 && imagePath.charAt(1) == ':')) {
            return false;
        }
        // 规范化路径并验证
        try {
            Path normalized = Paths.get(imagePath).normalize();
            // 确保规范化后的路径不以 .. 开头（防止绕过）
            if (normalized.startsWith("..") || normalized.isAbsolute()) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }
}
