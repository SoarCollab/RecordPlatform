package cn.flying.common.util;

import cn.flying.common.constant.Result;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * @program: RecordPlatform
 * @description: 控制器工具类
 * @author: flyingcoding
 * @create: 2025-01-16 14:31
 */
@Component
public class ControllerUtils {
    public Result<String> messageHandle(Supplier<String> action){
        String message = action.get();
        if(message == null) {
            return Result.success();
        } else {
            return Result.error(message);
        }
    }
}
