package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.service.FileQueryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文件 REST 新路径控制器。
 * <p>
 * 在兼容期内与旧路径并行，面向新前端调用，不改变底层业务语义。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "文件操作（REST）", description = "文件查询 REST 新路径")
public class FileRestController {

    @Resource
    private FileQueryService fileQueryService;

    /**
     * 获取文件分页列表（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @param keyword  关键词
     * @param status   状态
     * @return 分页结果
     */
    @GetMapping("")
    @Operation(summary = "获取文件分页列表（REST）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取文件分页列表（REST）")
    public Result<Page<File>> getFiles(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                       @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
                                       @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize,
                                       @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword,
                                       @Parameter(description = "文件状态") @RequestParam(required = false) Integer status) {
        Page<File> page = new Page<>(pageNum, pageSize);
        fileQueryService.getUserFilesPage(userId, page, keyword, status);
        return Result.success(page);
    }

    /**
     * 通过文件哈希查询文件详情（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param fileHash 文件哈希
     * @return 文件信息
     */
    @GetMapping("/hash/{fileHash}")
    @Operation(summary = "通过哈希查询文件详情（REST）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "通过哈希查询文件详情（REST）")
    public Result<File> getFileByHash(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                      @PathVariable String fileHash) {
        return Result.success(fileQueryService.getFileByHash(userId, fileHash));
    }

    /**
     * 获取文件下载地址列表（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param fileHash 文件哈希
     * @return 下载地址列表
     */
    @GetMapping("/hash/{fileHash}/addresses")
    @Operation(summary = "获取文件下载地址（REST）")
    @OperationLog(module = "文件操作", operationType = "下载", description = "获取文件下载地址（REST）")
    public Result<List<String>> getFileAddresses(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                                 @PathVariable String fileHash) {
        return Result.success(fileQueryService.getFileAddress(userId, fileHash));
    }

    /**
     * 获取文件加密分片（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param fileHash 文件哈希
     * @return 加密分片列表
     */
    @GetMapping("/hash/{fileHash}/chunks")
    @Operation(summary = "获取文件加密分片（REST）")
    @OperationLog(module = "文件操作", operationType = "下载", description = "获取文件加密分片（REST）")
    public Result<List<byte[]>> getFileChunks(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                              @PathVariable String fileHash) {
        return Result.success(fileQueryService.getFile(userId, fileHash));
    }

    /**
     * 获取文件解密信息（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param fileHash 文件哈希
     * @return 解密信息
     */
    @GetMapping("/hash/{fileHash}/decrypt-info")
    @Operation(summary = "获取文件解密信息（REST）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取文件解密信息（REST）")
    public Result<FileDecryptInfoVO> getFileDecryptInfo(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                                        @PathVariable String fileHash) {
        return Result.success(fileQueryService.getFileDecryptInfo(userId, fileHash));
    }
}

