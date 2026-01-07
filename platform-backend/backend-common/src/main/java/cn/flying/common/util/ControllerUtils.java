package cn.flying.common.util;

import cn.flying.common.constant.Result;
import cn.flying.common.exception.GeneralException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * @program: RecordPlatform
 * @description: 控制器工具类
 * @author flyingcoding
 * @create: 2025-01-16 14:31
 */
@Component
public class ControllerUtils {

    /**
     * 执行业务逻辑并将返回结果统一映射为接口响应。
     * <p>
     * 约定：action 返回 {@code null} 表示成功；返回非空字符串表示业务失败原因。
     * 当业务失败时抛出 {@link GeneralException}，由全局异常处理器统一转换为 400 响应。
     *
     * @param action 业务逻辑（返回 null 表示成功，否则为错误信息）
     * @return 成功时返回 {@link Result#success()}
     */
    public Result<String> messageHandle(Supplier<String> action) {
        String message = action.get();
        if (message == null) {
            return Result.success();
        }
        throw new GeneralException(message);
    }
}
