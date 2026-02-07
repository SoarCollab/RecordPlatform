package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易查询 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "交易查询（REST）", description = "交易查询 REST 新路径")
public class TransactionController {

    @Resource
    private FileQueryService fileQueryService;

    /**
     * 根据交易哈希查询交易详情。
     *
     * @param transactionHash 交易哈希
     * @return 交易信息
     */
    @GetMapping("/{transactionHash}")
    @Operation(summary = "根据交易哈希查询交易详情（REST）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "根据交易哈希查询交易详情（REST）")
    public Result<TransactionVO> getTransaction(@PathVariable String transactionHash) {
        return Result.success(fileQueryService.getTransactionByHash(transactionHash));
    }
}

