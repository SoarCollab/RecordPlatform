package cn.flying.fisco_bcos.parser;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 合约响应解析器
 * 统一处理 FISCO BCOS 合约调用的返回值解析，避免硬编码索引和脆弱的类型转换
 */
public final class ContractResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ContractResponseParser.class);

    private ContractResponseParser() {
        // 工具类禁止实例化
    }

    /**
     * 解析交易响应
     *
     * @param response  交易响应
     * @param extractor 数据提取函数
     * @param operation 操作名称（用于日志）
     * @param <T>       返回值类型
     * @return 解析结果
     */
    public static <T> Result<T> parseTransaction(
            TransactionResponse response,
            Function<List<?>, T> extractor,
            String operation) {

        if (response == null) {
            log.warn("[{}] 合约响应为空", operation);
            return Result.error(ResultEnum.CONTRACT_ERROR, null);
        }

        if (response.getReturnCode() != 0) {
            log.warn("[{}] 合约执行失败: code={}, msg={}",
                    operation, response.getReturnCode(), response.getReturnMessage());
            return Result.error(ResultEnum.CONTRACT_ERROR, null);
        }

        Object returnValue = response.getReturnObject();
        if (!(returnValue instanceof List<?> returnList) || returnList.isEmpty()) {
            log.warn("[{}] 合约返回值格式错误: {}", operation, returnValue);
            return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
        }

        try {
            T result = extractor.apply(returnList);
            if (result == null) {
                log.warn("[{}] 数据提取结果为空", operation);
                return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
            }
            return Result.success(result);
        } catch (Exception e) {
            log.error("[{}] 数据提取异常: {}", operation, e.getMessage());
            return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
        }
    }

    /**
     * 解析调用响应（只读操作）
     *
     * @param response  调用响应
     * @param extractor 数据提取函数
     * @param operation 操作名称
     * @param <T>       返回值类型
     * @return 解析结果
     */
    public static <T> Result<T> parseCall(
            CallResponse response,
            Function<List<?>, T> extractor,
            String operation) {

        if (response == null) {
            log.warn("[{}] 调用响应为空", operation);
            return Result.error(ResultEnum.CONTRACT_ERROR, null);
        }

        Object returnValue = response.getReturnObject();
        if (!(returnValue instanceof List<?> returnList)) {
            log.warn("[{}] 调用返回值格式错误: {}", operation, returnValue);
            return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
        }

        try {
            T result = extractor.apply(returnList);
            return Result.success(result);
        } catch (Exception e) {
            log.error("[{}] 数据提取异常: {}", operation, e.getMessage());
            return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
        }
    }

    /**
     * 安全获取列表元素
     *
     * @param list  列表
     * @param index 索引
     * @param type  期望类型
     * @param <T>   返回类型
     * @return Optional 包装的元素
     */
    public static <T> Optional<T> safeGet(List<?> list, int index, Class<T> type) {
        if (list == null || index < 0 || index >= list.size()) {
            return Optional.empty();
        }
        Object element = list.get(index);
        if (type.isInstance(element)) {
            return Optional.of(type.cast(element));
        }
        return Optional.empty();
    }

    /**
     * 安全获取字符串元素
     */
    public static Optional<String> safeGetString(List<?> list, int index) {
        if (list == null || index < 0 || index >= list.size()) {
            return Optional.empty();
        }
        Object element = list.get(index);
        return Optional.ofNullable(element).map(String::valueOf);
    }

    /**
     * 安全获取嵌套列表
     */
    public static Optional<List<?>> safeGetList(List<?> list, int index) {
        return safeGet(list, index, List.class).map(l -> (List<?>) l);
    }

    /**
     * 验证列表长度
     */
    public static boolean validateSize(List<?> list, int expectedSize) {
        return list != null && list.size() == expectedSize;
    }

    /**
     * 验证列表最小长度
     */
    public static boolean validateMinSize(List<?> list, int minSize) {
        return list != null && list.size() >= minSize;
    }
}
