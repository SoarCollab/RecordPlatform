package cn.flying.common.constant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * @program: RecordPlatform
 * @description: 返回结果封装
 * @author: flyingcoding
 * @create: 2025-01-15 15:38
 */

@Getter
@Setter
@Schema(description = "返回结果封装")
public class Result<T> {

    // 操作代码
    @Schema(description = "操作代码")
    private Integer code;

    // 提示信息
    @Schema(description = "提示信息")
    private String message;

    // 结果数据
    @Schema(description = "结果数据")
    private T data;

    @JsonCreator
    public Result(
            @JsonProperty("code") Integer code,
            @JsonProperty("message") String message,
            @JsonProperty("data") T data
    ) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public Result(ResultEnum resultCode) {
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
    }

    public Result(ResultEnum resultCode, T data) {
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
        this.data = data;
    }
    public Result(String message) {
        this.message = message;
    }
    //成功返回封装-无数据
    public static Result<String> success() {
        return new Result<>(ResultEnum.SUCCESS.getCode(), ResultEnum.SUCCESS.getMessage(), null);
    }
    //成功返回封装-带数据
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultEnum.SUCCESS.getCode(), ResultEnum.SUCCESS.getMessage(), data);
    }
    //失败返回封装-使用默认提示信息
    public static Result<String> error() {
        return new Result<>(ResultEnum.FAIL.getCode(), ResultEnum.FAIL.getMessage(), null);
    }
    //失败返回封装-使用返回结果枚举提示信息
    public static Result<String> error(ResultEnum resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }
    //失败返回封装-携带数据与错误信息一起返回
    public static <T> Result<T> error(ResultEnum resultCode, T data) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), data);
    }
    //失败返回封装-使用自定义提示信息
    public static Result<String> error(String message) {
        return new Result<>(500, message, null);
    }
}
